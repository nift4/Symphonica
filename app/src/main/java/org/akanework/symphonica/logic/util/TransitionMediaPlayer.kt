package org.akanework.symphonica.logic.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaTimestamp
import android.media.TimedMetaData
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.util.Log


class Timestamp(val systemAnchorTimeNano: Long, val mediaAnchorTimeMillis: Long,
                val playbackSpeed: Float)

class MediaPlayerState(private val applicationContext: Context, private val handler: Handler,
                       private val playbackAttrs: AudioAttributes, private val callback: Callback)
	: MediaPlayer.OnErrorListener, MediaPlayer.OnBufferingUpdateListener, MediaPlayer.OnSeekCompleteListener,
	  MediaPlayer.OnCompletionListener, MediaPlayer.OnInfoListener, MediaPlayer.OnMediaTimeDiscontinuityListener,
	  MediaPlayer.OnPreparedListener, MediaPlayer.OnTimedMetaDataAvailableListener {

	private enum class StateDiagram {
		IDLE, END, ERROR, INITIALIZED, PREPARING, PREPARED, STARTED, STOPPED, PAUSED, COMPLETED, BUSY
	}

	/* package-private */ interface Callback {
		// This media player has completed playing (due to end of song or due to error)
		// It now is in state IDLE and ready to be re-used.
		fun onRecycleSelf(mp: MediaPlayerState)
		// This media player was destroyed due to an fatal error.
		fun onDestroySelf(mp: MediaPlayerState)
		// Display error to user. Player will recycle or destroy itself as appropriate. "what" is one of
		// MediaPlayer.MEDIA_ERROR_TIMED_OUT, MediaPlayer.MEDIA_ERROR_SERVER_DIED or MediaPlayer.MEDIA_ERROR_UNKNOWN
		fun onInternalPlaybackError(mp: MediaPlayerState, what: Int)
		// Display error to user. Player will recycle itself. "what" is one of MediaPlayer.MEDIA_ERROR_UNSUPPORTED,
		// MediaPlayer.MEDIA_ERROR_NOT_VALID_FOR_PROGRESSIVE_PLAYBACK, MediaPlayer.MEDIA_ERROR_MALFORMED or
		// MediaPlayer.MEDIA_ERROR_IO.
		fun onTrackPlaybackError(mp: MediaPlayerState, what: Int)
		// Called when playback has stopped for whatever reason
		fun onCompletedPlaying(mp: MediaPlayerState)
		// Called when the current media playback has decreased performance
		fun onMediaDecreasedPerformance(mp: MediaPlayerState)
		// Buffered status update. Progress is played + buffered (if 50% of song are played and
		// another 30% are buffered, progress is 80% so 0.8f)
		fun onBufferStatusUpdate(mp: MediaPlayerState, progress: Float)
		// Called when media buffering state changed. buffering is true when MediaPlayer had to stop
		// the audio playback temporarily.
		fun onMediaBuffering(mp: MediaPlayerState, buffering: Boolean)
		// Next song started playing in an optimized way
		fun onMediaStartedAsNext(mp: MediaPlayerState)
		// Called when new metadata had been extracted
		fun onMetadataUpdate(mp: MediaPlayerState)
		// Called when current track can not be seeked (e.g. live stream, web radio)
		fun onUnseekablePlayback(mp: MediaPlayerState)
		// Called if previously started seek operation succeeded
		fun onSeekCompleted(mp: MediaPlayerState)
		// Called if timestamp anchor changed (eg user seeked)
		fun onNewTimestampAvailable(mp: MediaPlayerState, mts: Timestamp)
		// Called when new livestream metadata became available.
		fun onLiveDataAvailable(mp: MediaPlayerState, text: String)
	}

	companion object {
		private const val TAG = "MediaPlayerState"
	}

	private val mediaPlayer = MediaPlayer()
	private var state = StateDiagram.IDLE
	private var prepareListener: Runnable? = null
	private val liveDataCallbacks: ArrayList<Runnable> = ArrayList()
	val durationMillis: Int
		get() = mediaPlayer.duration

	init {
		mediaPlayer.setOnErrorListener(this)
		// according to MediaPlayer javadoc, setting error listener and then
		// calling reset() allows us to catch more errors
		mediaPlayer.reset()
		mediaPlayer.setOnBufferingUpdateListener(this)
		mediaPlayer.setOnSeekCompleteListener(this)
		mediaPlayer.setOnCompletionListener(this)
		mediaPlayer.setOnInfoListener(this)
		mediaPlayer.setOnMediaTimeDiscontinuityListener(this)
		mediaPlayer.setOnPreparedListener(this)
		mediaPlayer.setOnTimedMetaDataAvailableListener(this)
	}

	override fun onError(mp: MediaPlayer, what: Int, extra: Int): Boolean {
		if (state == StateDiagram.BUSY) {
			// This error had real bad timing
			Log.w(TAG, "skipping error while busy: what=$what extra=$extra")
			return true
		}
		state = StateDiagram.ERROR
		if (what == MediaPlayer.MEDIA_ERROR_SERVER_DIED || extra == Int.MIN_VALUE /* MEDIA_ERROR_SYSTEM as per javadoc */) {
			// Unrecoverable error.
			onInternalPlaybackError(MediaPlayer.MEDIA_ERROR_SERVER_DIED)
			destroySelf()
			return true
		} else if (what == MediaPlayer.MEDIA_ERROR_UNKNOWN) { // Check extra for actual error
			if (extra == MediaPlayer.MEDIA_ERROR_NOT_VALID_FOR_PROGRESSIVE_PLAYBACK
				|| extra == MediaPlayer.MEDIA_ERROR_IO || extra == MediaPlayer.MEDIA_ERROR_MALFORMED
				|| extra == MediaPlayer.MEDIA_ERROR_UNSUPPORTED) {
				onTrackPlaybackError(extra)
				recycleSelf()
				return true
			}
			if (extra == MediaPlayer.MEDIA_ERROR_TIMED_OUT) {
				onInternalPlaybackError(MediaPlayer.MEDIA_ERROR_TIMED_OUT)
				recycleSelf()
				return true
			}
		} else if (what == -38) {
			// -38 == -ENOSYS, which in Android means INVALID_OPERATION, it means it's a bug in our code
			throw IllegalStateException("Illegal state transition in MediaPlayer")
		}
		// This error is new/unknown/vendor extension
		Log.e(TAG, "unsupported error what=$what extra=$extra")
		onInternalPlaybackError(MediaPlayer.MEDIA_ERROR_UNKNOWN)
		destroySelf()
		return true
	}

	override fun onBufferingUpdate(mp: MediaPlayer, percent: Int) {
		if (state != StateDiagram.BUSY) {
			assertState(StateDiagram.STARTED)
			onBufferStatusUpdate(percent / 100f)
		}
	}

	override fun onSeekComplete(mp: MediaPlayer) {
		if (state != StateDiagram.BUSY) {
			assertState(StateDiagram.STARTED)
			onSeekCompleted()
		}
	}

	// Only called when NOT looping.
	override fun onCompletion(mp: MediaPlayer) {
		// If state is ERROR, make sure to return true in onError()
		if (state != StateDiagram.BUSY) {
			assertNotState(
				StateDiagram.ERROR,
				StateDiagram.END,
				StateDiagram.IDLE,
				StateDiagram.COMPLETED
			)
			state = StateDiagram.COMPLETED
			recycleSelf() // includes onCompletedPlaying()
		}
	}

	override fun onInfo(mp: MediaPlayer, what: Int, extra: Int): Boolean {
		if (state == StateDiagram.BUSY) {
			// This info had real bad timing
			Log.i(TAG, "skipping info while busy: what=$what extra=$extra")
			return true
		}
		when (what) {
			MediaPlayer.MEDIA_INFO_BAD_INTERLEAVING -> {
				onMediaDecreasedPerformance()
			}
			MediaPlayer.MEDIA_INFO_BUFFERING_START -> {
				onMediaBuffering(true)
			}
			MediaPlayer.MEDIA_INFO_BUFFERING_END -> {
				onMediaBuffering(false)
			}
			MediaPlayer.MEDIA_INFO_STARTED_AS_NEXT -> {
				onMediaStartedAsNext()
			}
			MediaPlayer.MEDIA_INFO_METADATA_UPDATE -> {
				onMetadataUpdate()
			}
			MediaPlayer.MEDIA_INFO_NOT_SEEKABLE -> {
				onUnseekablePlayback()
			}
			MediaPlayer.MEDIA_INFO_AUDIO_NOT_PLAYING -> {
				Log.w(TAG, "Unreachable code reached? MEDIA_INFO_AUDIO_NOT_PLAYING")
				onInternalPlaybackError(MediaPlayer.MEDIA_ERROR_UNKNOWN)
			}
			MediaPlayer.MEDIA_INFO_VIDEO_NOT_PLAYING -> {
				Log.w(TAG, "Unreachable code reached? MEDIA_INFO_VIDEO_NOT_PLAYING on audio-only")
				onInternalPlaybackError(MediaPlayer.MEDIA_ERROR_UNKNOWN)
			}
			MediaPlayer.MEDIA_INFO_VIDEO_TRACK_LAGGING -> {
				Log.w(TAG, "Unreachable code reached? MEDIA_INFO_VIDEO_TRACK_LAGGING on audio-only")
				onInternalPlaybackError(MediaPlayer.MEDIA_ERROR_UNKNOWN)
			}
			MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START -> {
				Log.w(TAG, "Unreachable code reached? MEDIA_INFO_VIDEO_RENDERING_START on audio-only")
				onInternalPlaybackError(MediaPlayer.MEDIA_ERROR_UNKNOWN)
			}
			MediaPlayer.MEDIA_INFO_SUBTITLE_TIMED_OUT -> {
				Log.w(TAG, "Unreachable code reached? MEDIA_INFO_SUBTITLE_TIMED_OUT on audio-only")
				onInternalPlaybackError(MediaPlayer.MEDIA_ERROR_UNKNOWN)
			}
			MediaPlayer.MEDIA_INFO_UNSUPPORTED_SUBTITLE -> {
				Log.w(TAG, "Unreachable code reached? MEDIA_INFO_UNSUPPORTED_SUBTITLE on audio-only")
				onInternalPlaybackError(MediaPlayer.MEDIA_ERROR_UNKNOWN)
			}
			MediaPlayer.MEDIA_INFO_UNKNOWN -> {
				Log.i(TAG, "Dropping implementation-detail MEDIA_INFO_UNKNOWN $extra")
			}
			else -> {
				Log.w(TAG, "Dropping info what=$what extra=$extra")
			}
		}
		return true
	}

	@Suppress("Deprecation")
	override fun onMediaTimeDiscontinuity(mp: MediaPlayer, mts: MediaTimestamp) {
		if (state != StateDiagram.BUSY) {
			assertState(StateDiagram.STARTED)
			val ts = Timestamp(
				if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P)
					mts.anchorSytemNanoTime
				else
					mts.anchorSystemNanoTime
			, mts.anchorMediaTimeUs / 1000, mts.mediaClockRate)
			onNewTimestampAvailable(ts)
		}
	}

	override fun onPrepared(mp: MediaPlayer) {
		if (state != StateDiagram.BUSY) {
			assertState(StateDiagram.PREPARING)
			state = StateDiagram.PREPARED
			prepareListener?.run()
		}
	}

	override fun onTimedMetaDataAvailable(mp: MediaPlayer, data: TimedMetaData) {
		assertState(StateDiagram.STARTED)
		val ts = data.timestamp / 1000
		val timeLeftInMs = ts - mediaPlayer.currentPosition
		val text = data.metaData.decodeToString()
		if (timeLeftInMs <= 0) {
			onLiveDataAvailable(text)
		} else {
			val ldc = Runnable {
				if (state == StateDiagram.STARTED || state == StateDiagram.PAUSED || state == StateDiagram.STOPPED) {
					onLiveDataAvailable(text)
				}
			}
			liveDataCallbacks.add(ldc)
			handler.postDelayed(ldc, timeLeftInMs)
		}
	}

	private fun recycleSelf() {
		recycle()
		onRecycleSelf()
		onCompletedPlaying()
	}

	private fun destroySelf() {
		destroy()
		onDestroySelf()
		onCompletedPlaying()
	}

	private fun cleanup() {
		for (i in 0..liveDataCallbacks.size) {
			handler.removeCallbacks(liveDataCallbacks.removeFirst())
		}
		prepareListener = null
	}

	fun destroy() {
		assertNotState(StateDiagram.BUSY, StateDiagram.END, StateDiagram.STARTED)
		state = StateDiagram.BUSY
		cleanup()
		mediaPlayer.release()
		state = StateDiagram.END
	}

	fun recycle() {
		assertNotState(StateDiagram.BUSY, StateDiagram.END, StateDiagram.STARTED)
		if (state != StateDiagram.IDLE) {
			state = StateDiagram.BUSY
			cleanup()
			mediaPlayer.reset()
			state = StateDiagram.IDLE
		}
	}

	fun initialize(playable: Playable) {
		assertState(StateDiagram.IDLE)
		state = StateDiagram.BUSY
		mediaPlayer.setDataSource(applicationContext, playable.toUri())
		state = StateDiagram.INITIALIZED
	}

	fun preload(async: Boolean) {
		assertState(StateDiagram.INITIALIZED, StateDiagram.STOPPED)
		mediaPlayer.setAudioAttributes(playbackAttrs)
		if (async) {
			state = StateDiagram.PREPARING
			mediaPlayer.prepareAsync()
		} else {
			state = StateDiagram.BUSY
			mediaPlayer.prepare()
			state = StateDiagram.PREPARED
		}
	}

	fun start(async: Boolean) {
		if (state == StateDiagram.PREPARING || state == StateDiagram.INITIALIZED || state == StateDiagram.STOPPED) {
			if (async) {
				prepareListener = Runnable {
					assertState(StateDiagram.PREPARED)
					start(false)
				}
			}
			if (state != StateDiagram.PREPARING) {
				preload(async)
			}
			if (async) {
				return
			}
		}
		assertState(StateDiagram.PREPARED, StateDiagram.PAUSED)
		state = StateDiagram.STARTED
		mediaPlayer.start()
	}

	fun pause() {
		assertState(StateDiagram.STARTED)
		state = StateDiagram.PAUSED
		mediaPlayer.pause()
	}

	fun stop() {
		assertState(StateDiagram.STARTED, StateDiagram.PAUSED, StateDiagram.PREPARED)
		state = StateDiagram.STOPPED
		mediaPlayer.stop()
	}

	fun setNext(mp: MediaPlayerState?) {
		assertNotState(
			StateDiagram.BUSY,
			StateDiagram.ERROR,
			StateDiagram.END,
			StateDiagram.COMPLETED
		)
		if (mp == this) {
			// If we are the next player, just pretend to loop till we aren't anymore.
			mediaPlayer.isLooping = true
		} else {
			mediaPlayer.isLooping = false
			if (mp != null && mp.state == StateDiagram.PREPARING) {
				mp.prepareListener = Runnable {
					if (state != StateDiagram.BUSY && state != StateDiagram.ERROR
						&& state != StateDiagram.END && state != StateDiagram.COMPLETED
					) {
						if (mp.state == StateDiagram.PREPARED) {
							mediaPlayer.setNextMediaPlayer(mp.mediaPlayer)
						} else {
							Log.w(TAG, "tried to set next async with invalid state ${mp.state}")
						}
					}
				}
			} else {
				mp?.assertState(StateDiagram.PREPARED)
				mediaPlayer.setNextMediaPlayer(mp?.mediaPlayer)
			}
		}
	}

	private fun onRecycleSelf() {
		handler.post {
			callback.onRecycleSelf(this)
		}
	}

	private fun onDestroySelf() {
		handler.post {
			callback.onDestroySelf(this)
		}
	}

	private fun onInternalPlaybackError(what: Int) {
		handler.post {
			callback.onInternalPlaybackError(this, what)
		}
	}

	private fun onTrackPlaybackError(what: Int) {
		handler.post {
			callback.onTrackPlaybackError(this, what)
		}
	}

	private fun onCompletedPlaying() {
		handler.post {
			callback.onCompletedPlaying(this)
		}
	}

	private fun onMediaDecreasedPerformance() {
		handler.post {
			callback.onMediaDecreasedPerformance(this)
		}
	}

	private fun onBufferStatusUpdate(progress: Float) {
		handler.post {
			callback.onBufferStatusUpdate(this, progress)
		}
	}

	private fun onMediaBuffering(buffering: Boolean) {
		handler.post {
			callback.onMediaBuffering(this, buffering)
		}
	}

	private fun onMediaStartedAsNext() {
		handler.post {
			callback.onMediaStartedAsNext(this)
		}
	}

	private fun onMetadataUpdate() {
		handler.post {
			callback.onMetadataUpdate(this)
		}
	}

	private fun onUnseekablePlayback() {
		handler.post {
			callback.onUnseekablePlayback(this)
		}
	}

	private fun onSeekCompleted() {
		handler.post {
			callback.onSeekCompleted(this)
		}
	}

	private fun onNewTimestampAvailable(mts: Timestamp) {
		handler.post {
			callback.onNewTimestampAvailable(this, mts)
		}
	}

	private fun onLiveDataAvailable(text: String) {
		handler.post {
			callback.onLiveDataAvailable(this, text)
		}
	}

	private fun assertNotState(vararg badStates: StateDiagram) {
		if (badStates.any { state == it }) {
			throw IllegalStateException("Current state $state is in list of disallowed states: $badStates")
		}
	}

	private fun assertState(vararg goodStates: StateDiagram) {
		if (!goodStates.any { state == it }) {
			throw IllegalStateException("Current state $state is not in list of allowed states: $goodStates")
		}
	}
}

/** Opaque object that can be played by MediaPlayer. */
class Playable(private val uri: Uri) {
	fun toUri() = uri
}

abstract class NextTrackPredictor {
	private var onPredictionChangedListener: OnPredictionChangedListener? = null

	protected fun dispatchPredictionChange(currentSongImpacted: Boolean) {
		onPredictionChangedListener?.onPredictionChanged(currentSongImpacted)
	}

	protected fun dispatchPlay() {
		onPredictionChangedListener?.play()
	}

	protected fun displayPause() {
		onPredictionChangedListener?.pause()
	}

	protected fun dispatchStop() {
		onPredictionChangedListener?.stop()
	}

	/**
	 * Set listener that will be called when return of predictNextTrack() or isLooping() changes
	 */
	fun setOnPredictionChangedListener(listener: OnPredictionChangedListener?) {
		onPredictionChangedListener = listener
	}

	/**
	 * Predict next track if available. If no track is available (playback will stop), returns null.
	 * If looping, returns handle to current track.
	 */
	abstract fun predictNextTrack(consume: Boolean): Playable?

	/**
	 * If the current track is looping forever.
	 */
	abstract fun isLooping(): Boolean

	interface OnPredictionChangedListener {
		fun onPredictionChanged(currentSongImpacted: Boolean)
		fun play()
		fun pause()
		fun stop()
	}
}

interface MediaStateCallback {
	fun onPlayingStatusChanged(playing: Boolean)
	fun onLiveInfoAvailable(text: String)
	fun onTimestampInfoAvailable(timestamp: Timestamp)
	fun onSetSeekable(seekable: Boolean)
	fun onMediaBufferSlowStatus(slowBuffer: Boolean)
	fun onMediaBufferProgess(progress: Float)
	fun onMediaHasDecreasedPerformance()
	fun onPlaybackError(what: Int)
	fun onDurationAvailable(durationMillis: Int)

	open class Dispatcher : MediaStateCallback {
		private val callbacks: HashSet<MediaStateCallback> = HashSet()

		fun addMediaStateCallback(callback: MediaStateCallback) {
			callbacks.add(callback)
		}

		fun removeMediaStateCallback(callback: MediaStateCallback) {
			callbacks.remove(callback)
		}

		override fun onPlayingStatusChanged(playing: Boolean) {
			callbacks.forEach { it.onPlayingStatusChanged(playing) }
		}

		override fun onLiveInfoAvailable(text: String) {
			callbacks.forEach { it.onLiveInfoAvailable(text) }
		}

		override fun onTimestampInfoAvailable(timestamp: Timestamp) {
			callbacks.forEach { it.onTimestampInfoAvailable(timestamp) }
		}

		override fun onSetSeekable(seekable: Boolean) {
			callbacks.forEach { it.onSetSeekable(seekable) }
		}

		override fun onMediaBufferSlowStatus(slowBuffer: Boolean) {
			callbacks.forEach { it.onMediaBufferSlowStatus(slowBuffer) }
		}

		override fun onMediaBufferProgess(progress: Float) {
			callbacks.forEach { it.onMediaBufferProgess(progress) }
		}

		override fun onMediaHasDecreasedPerformance() {
			callbacks.forEach { it.onMediaHasDecreasedPerformance() }
		}

		override fun onPlaybackError(what: Int) {
			callbacks.forEach { it.onPlaybackError(what) }
		}

		override fun onDurationAvailable(durationMillis: Int) {
			callbacks.forEach { it.onPlaybackError(durationMillis) }
		}

	}
}

/**
 * Handles audio focus, basic MediaPlayer transitions, data stream and state management.
 * Intended API surface consists of setNextTrackPredictor() and destroy().
 */
class TransitionMediaPlayer(private val applicationContext: Context) : MediaStateCallback.Dispatcher(),
	MediaPlayerState.Callback, AudioManager.OnAudioFocusChangeListener, NextTrackPredictor.OnPredictionChangedListener {
	private val handler = Handler(applicationContext.mainLooper)
	private val audioManager = applicationContext.getSystemService(AudioManager::class.java)
	private val mediaPlayerPool: ArrayDeque<MediaPlayerState> = ArrayDeque()
	private val defaultTrackPredictor = object : NextTrackPredictor() {
		override fun predictNextTrack(consume: Boolean): Playable? {
			return null
		}

		override fun isLooping(): Boolean {
			return false
		}
	}
	private val playbackAttributes = AudioAttributes.Builder()
		.setUsage(AudioAttributes.USAGE_MEDIA)
		.setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
		.build()
	private var mediaPlayer: MediaPlayerState? = null
	private var nextMediaPlayer: MediaPlayerState? = null
	private var trackPredictor: NextTrackPredictor = defaultTrackPredictor
	private var playing = false
		set(value) {
			if (value != field) {
				field = value
				onPlayingStatusChanged(value)
			}
		}
	private var userPlaying = false
		set(value) {
			field = value
			if (!value) {
				playing = false
				ignoreAudioFocus = false
			}
		}
	private var ignoreAudioFocus = false
		set(value) {
			field = value
			if (playing && userPlaying && !value && !hasAudioFocus) {
				realPause()
			} else if (value && userPlaying && !playing) {
				realPlay()
			}
		}
	private var hasAudioFocus = false
		set(value) {
			if (value != field) {
				field = value
				if (value) {
					ignoreAudioFocus = false
				}
				if (!ignoreAudioFocus) {
					if (playing && userPlaying && !value) {
						realPause()
					} else if (userPlaying && !playing && value) {
						realPlay()
					}
				}
			}
		}

	companion object {
		private const val TAG = "TransitionMediaPlayer"
	}

	private fun createMediaPlayer(): MediaPlayerState {
		return MediaPlayerState(applicationContext, handler, playbackAttributes, this)
	}

	private fun getNextRecycledMediaPlayer(): MediaPlayerState {
		return mediaPlayerPool.removeFirstOrNull() ?: createMediaPlayer()
	}

	fun setNextTrackPredictor(newTrackPredictor: NextTrackPredictor?) {
		trackPredictor.setOnPredictionChangedListener(null)
		trackPredictor = newTrackPredictor ?: defaultTrackPredictor
		trackPredictor.setOnPredictionChangedListener(this)
		onPredictionChanged(true)
	}

	override fun play() {
		if (userPlaying) {
			ignoreAudioFocus = true
			return
		}
		userPlaying = true
		if (playing) return // should never happen, but well
		if (!ignoreAudioFocus) {
			val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
				.setAudioAttributes(playbackAttributes)
				.setAcceptsDelayedFocusGain(true)
				.setWillPauseWhenDucked(false)
				.setOnAudioFocusChangeListener(this, handler)
				.build()
			audioManager.requestAudioFocus(focusRequest)
		} else {
			realPlay()
		}
	}

	override fun pause() {
		userPlaying = false
		realPause()
	}

	override fun stop() {
		userPlaying = false
		try {
			mediaPlayer?.stop()
		} catch (_: IllegalStateException) {}
		mediaPlayer?.let {
			it.recycle()
			mediaPlayerPool.add(it)
		}
		mediaPlayer = null
		nextMediaPlayer?.let {
			it.recycle()
			mediaPlayerPool.add(it)
		}
		nextMediaPlayer = null
		maybeCleanupPool()
	}

	fun destroy() {
		stop()
		for (i in 0..mediaPlayerPool.size) {
			mediaPlayerPool.removeFirst().destroy()
		}
	}

	override fun onAudioFocusChange(focusChange: Int) {
		when (focusChange) {
			AudioManager.AUDIOFOCUS_GAIN -> {
				hasAudioFocus = true
			}
			AudioManager.AUDIOFOCUS_LOSS -> {
				pause()
				hasAudioFocus = false
			}
			AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
				hasAudioFocus = false
			}
			AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
				// Since Android Oreo, system handles ducking for us.
			}
		}
	}

	private fun realPlay() {
		playing = true
		if (mediaPlayer == null) {
			skip()
		} else {
			mediaPlayer?.start(true)
		}
	}

	private fun realPause() {
		playing = false
		mediaPlayer?.pause()
	}

	override fun onPredictionChanged(currentSongImpacted: Boolean) {
		if (mediaPlayer == null) {
			return
		}
		nextMediaPlayer?.let {
			it.recycle()
			mediaPlayerPool.add(it)
		}
		nextMediaPlayer = null
		if (currentSongImpacted) {
			skip()
		}
		mediaPlayer?.setNext(
			if (trackPredictor.isLooping())
				mediaPlayer
			else {
				val playable = trackPredictor.predictNextTrack(false)
				if (playable != null) {
					nextMediaPlayer = getNextRecycledMediaPlayer()
					nextMediaPlayer?.initialize(playable)
					nextMediaPlayer?.durationMillis?.let { onDurationAvailable(it) }
					nextMediaPlayer
				} else null
			}
		)
	}

	/**
	 * Note that this must be called whenever the playlist has changed in such a way that the
	 * current song is no longer played (e.g. user is now playing another song).
	 */
	private fun skip() {
		if (nextMediaPlayer == null) {
			val playable = trackPredictor.predictNextTrack(true)
			if (playable != null) {
				nextMediaPlayer = getNextRecycledMediaPlayer()
				nextMediaPlayer?.initialize(playable)
				nextMediaPlayer?.durationMillis?.let { onDurationAvailable(it) }
			}
		}
		mediaPlayer = nextMediaPlayer
		nextMediaPlayer = null
		if (mediaPlayer != null) {
			if (playing) {
				mediaPlayer?.start(true)
			} else {
				mediaPlayer?.preload(true)
			}
		} else {
			userPlaying = false
		}
		onSetSeekable(true)
		maybeCleanupPool()
	}

	private fun maybeCleanupPool() {
		for (i in 0..mediaPlayerPool.size - 3) {
			val mp = mediaPlayerPool.removeFirst()
			mp.destroy()
		}
	}

	override fun onRecycleSelf(mp: MediaPlayerState) {
		if (mp == mediaPlayer || mp == nextMediaPlayer) {
			if (mp == mediaPlayer) {
				mediaPlayer = null
			} else {
				nextMediaPlayer = null
			}
			mediaPlayerPool.add(mp)
		}
		maybeCleanupPool()
	}

	override fun onDestroySelf(mp: MediaPlayerState) {
		if (mp == mediaPlayer || mp == nextMediaPlayer) {
			if (mp == mediaPlayer) {
				mediaPlayer = null
				skip()
			} else {
				nextMediaPlayer = null
			}
			mediaPlayerPool.add(mp)
		}
		maybeCleanupPool()
	}

	override fun onInternalPlaybackError(mp: MediaPlayerState, what: Int) {
		if (mp == nextMediaPlayer) {
			Log.w(TAG, "Next media player has internal error $what")
			nextMediaPlayer = null
			return
		}
		if (mp != mediaPlayer) {
			Log.w(TAG, "Non-active media player has internal error $what")
			return
		}
		onPlaybackError(what)
	}

	override fun onTrackPlaybackError(mp: MediaPlayerState, what: Int) {
		if (mp == nextMediaPlayer) {
			Log.w(TAG, "Next media player has track error $what")
			nextMediaPlayer = null
			return
		}
		if (mp != mediaPlayer) {
			Log.w(TAG, "Non-active media player has track error $what")
			return
		}
		onPlaybackError(what)
	}

	override fun onCompletedPlaying(mp: MediaPlayerState) {
		// If there's nothing next and looping is unset, we end up here.
		// This means the last song has played.
		if (nextMediaPlayer == null) {
			val playable = trackPredictor.predictNextTrack(false)
			if (playable == null) {
				mediaPlayer?.let {
					it.recycle()
					mediaPlayerPool.add(it)
				}
				mediaPlayer = null
				userPlaying = false
			} else {
				// This will only occur in error cases, but we want to properly show the user if
				// something breaks.
				skip()
			}
		}
		maybeCleanupPool()
	}

	override fun onMediaDecreasedPerformance(mp: MediaPlayerState) {
		if (mp != mediaPlayer) {
			throw IllegalStateException("Non-active media player has decreased performance")
		}
		onMediaHasDecreasedPerformance()
	}

	override fun onBufferStatusUpdate(mp: MediaPlayerState, progress: Float) {
		if (mp != mediaPlayer) {
			throw IllegalStateException("Non-active media player has buffer progress")
		}
		onMediaBufferProgess(progress)
	}

	override fun onMediaBuffering(mp: MediaPlayerState, buffering: Boolean) {
		if (mp != mediaPlayer) {
			throw IllegalStateException("Non-active media player is buffering slow")
		}
		onMediaBufferSlowStatus(buffering)
	}

	override fun onMediaStartedAsNext(mp: MediaPlayerState) {
		if (mediaPlayer != null) {
			throw IllegalStateException()
		}
		onSetSeekable(true)
		mediaPlayer = mp
		// Consume track now that we started playing it.
		trackPredictor.predictNextTrack(true)
		maybeCleanupPool()
	}

	override fun onMetadataUpdate(mp: MediaPlayerState) {
		// This seems to be triggered when [live] metadata is deemed existing, and if metadata
		// without buffer gets found. Probably useless?
	}

	override fun onUnseekablePlayback(mp: MediaPlayerState) {
		onSetSeekable(false)
	}

	override fun onSeekCompleted(mp: MediaPlayerState) {
		// TODO
	}

	override fun onNewTimestampAvailable(mp: MediaPlayerState, mts: Timestamp) {
		if (mp != mediaPlayer) {
			throw IllegalStateException("Non-active media player has new timestamps")
		}
		onTimestampInfoAvailable(mts)
	}

	override fun onLiveDataAvailable(mp: MediaPlayerState, text: String) {
		if (mp != mediaPlayer) {
			throw IllegalStateException("Non-active media player has live data")
		}
		onLiveInfoAvailable(text)
	}
}