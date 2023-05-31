package org.akanework.symphonica.logic.util

import android.content.Context

enum class LoopingMode {
	LOOPING_MODE_NONE, LOOPING_MODE_PLAYLIST, LOOPING_MODE_TRACK
}

class Playlist<T>(initialList: List<T>?) {

	private inner class Entry<T>(private val track: T) {
		fun toTrack(): T = track
	}

	private var currentEntry: Entry<T>? = null
	var callbacks: PlaylistCallbacks<T>? = null

	var currentPosition: Int
		get() = currentEntry?.let { trackList.indexOf(it) } ?: 0
		set(value) {
			// Explicitly allow to set 0 on empty playlist.
			if (value > 0 && value >= size) {
				throw IllegalArgumentException("playlist of size $size trying to set pos to $value")
			} else if (size == 0) {
				currentEntry = null
			}
			if (currentPosition != value) {
				currentEntry = if (size == 0) null else {
					// It is intended that this is NOT called if we add/remove track and thus the
					// current position changes. We have two other callback methods for that case.
					callbacks?.onPlaylistPositionChanged(currentPosition, value)
					trackList[value]
				}
			}
		}
	private val trackList = ArrayList<Entry<T>>()
	val size: Int
		get() = trackList.size

	init {
		initialList?.forEach { t -> add(t, size) }
	}

	fun add(track: T, to: Int) {
		trackList.add(to, Entry(track))
		callbacks?.onPlaylistItemAdded(to)
	}

	fun remove(at: Int) {
		val oldPos = currentPosition
		trackList.removeAt(at)
		callbacks?.onPlaylistItemRemoved(at)
		if (at == oldPos) {
			// Currently playing item got removed.
			currentPosition = oldPos.mod(size)
		}
	}

	fun getItem(pos: Int?): T? {
		if (pos == null || pos < 0 || pos >= size)
			return null
		return trackList[pos].toTrack()
	}
}

interface PlaylistCallbacks<T> {
	fun onPlaylistReplaced(oldPlaylist: Playlist<T>?, newPlaylist: Playlist<T>?)
	fun onPlaylistPositionChanged(oldPosition: Int, newPosition: Int)
	fun onPlaylistItemAdded(position: Int)
	fun onPlaylistItemRemoved(position: Int)

	class Dispatcher<T> : PlaylistCallbacks<T> {
		private val callbacks = ArrayList<PlaylistCallbacks<T>>()

		fun registerPlaylistCallback(callback: PlaylistCallbacks<T>) {
			callbacks.add(callback)
		}

		fun unregisterPlaylistCallback(callback: PlaylistCallbacks<T>) {
			callbacks.remove(callback)
		}

		override fun onPlaylistReplaced(oldPlaylist: Playlist<T>?, newPlaylist: Playlist<T>?) {
			callbacks.forEach { it.onPlaylistReplaced(oldPlaylist, newPlaylist) }
		}

		override fun onPlaylistPositionChanged(oldPosition: Int, newPosition: Int) {
			callbacks.forEach { it.onPlaylistPositionChanged(oldPosition, newPosition) }
		}

		override fun onPlaylistItemAdded(position: Int) {
			callbacks.forEach { it.onPlaylistItemAdded(position) }
		}

		override fun onPlaylistItemRemoved(position: Int) {
			callbacks.forEach { it.onPlaylistItemRemoved(position) }
		}
	}

}

class MusicPlayer<T : Playable>(applicationContext: Context) : NextTrackPredictor(),
	MediaStateCallback, PlaylistCallbacks<T> {
	private val mediaPlayer = TransitionMediaPlayer(applicationContext)
	private val playlistCallbacks = PlaylistCallbacks.Dispatcher<T>()
	private var hasConsumedFirst = false
	var volume = 1f
		set(value) {
			if (field != value) {
				field = value
				dispatchPlaybackSettings(volume, speed, pitch)
			}
		}
	var pitch = 1f
		set(value) {
		if (field != value) {
			field = value
			dispatchPlaybackSettings(volume, speed, pitch)
		}
	}
	var speed = 1f
		set(value) {
		if (field != value) {
			field = value
			dispatchPlaybackSettings(volume, speed, pitch)
		}
	}
	var loopingMode = LoopingMode.LOOPING_MODE_NONE
		set(value) {
			if (field != value) {
				val oldNext = predictNextTrack(false)
				field = value
				val newNext = predictNextTrack(false)
				if (oldNext != newNext) {
					dispatchPredictionChange(false)
				}
			}
		}
	private var handledPositionChange = false
	private var playing = false
	private var userPlaying = false
	private var seekable = false
	private var timestampMillis = 0L
	private var trackDuration = 0L
	val isPlaying
		get() = playing
	val isUserPlaying
		get() = userPlaying
	val isSeekable
		get() = seekable
	val currentTimestamp
		get() = timestampMillis
	val duration
		get() = trackDuration
	var playlist: Playlist<T>? = null
		set(value) {
			if (field != value) {
				val old = field
				field = value
				old?.callbacks = null
				value?.callbacks = playlistCallbacks
				playlistCallbacks.onPlaylistReplaced(old, value)
			}
		}


	init {
		mediaPlayer.setNextTrackPredictor(this)
		addMediaStateCallback(this)
		registerPlaylistCallback(this)
		dispatchPlaybackSettings(volume, speed, pitch)
	}

	override fun predictNextTrack(consume: Boolean): Playable? {
		if (!hasConsumedFirst) {
			hasConsumedFirst = consume
			return getCurrentItem()
		}
		if (isLooping()) {
			return getCurrentItem()
		}
		return getNextItem(consume)
	}

	override fun isLooping(): Boolean {
		return loopingMode == LoopingMode.LOOPING_MODE_TRACK
				|| (loopingMode == LoopingMode.LOOPING_MODE_PLAYLIST && getCurrentItem()
					== getNextItem(false))
	}

	fun play() {
		if (!playing) {
			playOrPause()
		}
	}

	fun pause() {
		if (playing) {
			playOrPause()
		}
	}

	fun playOrPause() {
		dispatchPlayOrPause()
	}

	fun prev() {
		getPrevPosition()?.let { playlist?.currentPosition = it }
	}

	fun next() {
		getNextPosition()?.let { playlist?.currentPosition = it }
	}

	private fun getCurrentItem(): Playable? {
		return playlist?.let {
			return@let it.getItem(it.currentPosition)
		}
	}

	private fun getNextItem(consume: Boolean): Playable? {
		return playlist?.getItem(getNextPosition().also {
			if (consume) it?.let {
				// If someone sets currentPosition, we usually update media player state.
				// There however is one case where it is undesirable, and that is when media player
				// tells us that it advanced to the next song normally. We need to catch this and
				// just update the next song prediction in that case.
				handledPositionChange = true
				playlist?.currentPosition = it
			}
		})
	}

	private fun getPrevPosition(cpos: Int? = playlist?.currentPosition): Int? {
		return getPosition((cpos ?: 0) - 1) ?: cpos
	}

	private fun getNextPosition(cpos: Int? = playlist?.currentPosition): Int? {
		return getPosition((cpos ?: -1) + 1) ?: cpos
	}

	private fun getPosition(pos: Int): Int? {
		var npos: Int? = null
		playlist?.let {
			npos = pos
			if (npos!! < 0 || npos!! >= it.size) {
				npos = if (loopingMode == LoopingMode.LOOPING_MODE_PLAYLIST) {
					npos!!.mod(it.size) // % is rem, not mod, don't use it here
				} else null
			}
		}
		return npos
	}

	fun seekTo(positionMills: Long) {
		dispatchSeek(positionMills)
	}

	/**
	 * This method does clean up, but this object can still be re-used afterwards.
	 */
	fun recycle() {
		hasConsumedFirst = false
		playlist = null
		mediaPlayer.destroy()
	}

	override fun onPlaylistReplaced(oldPlaylist: Playlist<T>?, newPlaylist: Playlist<T>?) {
		dispatchPredictionChange(true)
	}

	override fun onPlaylistPositionChanged(oldPosition: Int, newPosition: Int) {
		if (handledPositionChange) {
			handledPositionChange = false
			dispatchPredictionChange(false)
		} else {
			hasConsumedFirst = false
			dispatchPredictionChange(true)
		}
	}

	override fun onPlaylistItemAdded(position: Int) {
		if (position == getNextPosition()) {
			dispatchPredictionChange(false)
		}
	}

	override fun onPlaylistItemRemoved(position: Int) {
		if (position == getNextPosition()) {
			dispatchPredictionChange(false)
		}
	}

	override fun onPlayingStatusChanged(playing: Boolean) {
		this.playing = playing
	}

	override fun onUserPlayingStatusChanged(playing: Boolean) {
		userPlaying = playing
	}

	override fun onLiveInfoAvailable(text: String) {
		// Let UI bother with this.
	}

	override fun onMediaTimestampChanged(timestampMillis: Long) {
		this.timestampMillis = timestampMillis
	}

	override fun onSetSeekable(seekable: Boolean) {
		this.seekable = seekable
	}

	override fun onMediaBufferSlowStatus(slowBuffer: Boolean) {
		// Let UI bother with this.
	}

	override fun onMediaBufferProgress(progress: Float) {
		// Let UI bother with this.
	}

	override fun onMediaHasDecreasedPerformance() {
		// Let UI bother with this.
	}

	override fun onPlaybackError(what: Int) {
		// Let UI bother with this.
	}

	override fun onDurationAvailable(durationMillis: Long) {
		trackDuration = durationMillis
	}

	override fun onPlaybackSettingsChanged(volume: Float, speed: Float, pitch: Float) {
		if (volume != volume || speed != speed || pitch != pitch) {
			throw IllegalStateException()
		}
	}

	fun registerPlaylistCallback(callback: PlaylistCallbacks<T>) {
		playlistCallbacks.registerPlaylistCallback(callback)
	}

	fun unregisterPlaylistCallback(callback: PlaylistCallbacks<T>) {
		playlistCallbacks.unregisterPlaylistCallback(callback)
	}

	fun addMediaStateCallback(callback: MediaStateCallback) {
		mediaPlayer.addMediaStateCallback(callback)
	}

	fun removeMediaStateCallback(callback: MediaStateCallback) {
		mediaPlayer.removeMediaStateCallback(callback)
	}
}