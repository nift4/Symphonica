package org.akanework.symphonica.logic.util

import android.content.Context

enum class LoopingMode {
	LOOPING_MODE_NONE, LOOPING_MODE_PLAYLIST, LOOPING_MODE_TRACK
}

interface Track : Playable {
	// TODO
}

class Playlist(private val callbacks: PlaylistCallbacks) {
	private inner class Entry(private val track: Track) {
		fun toTrack(): Track = track
	}

	private var currentEntry: Entry? = null

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
					callbacks.onPlaylistPositionChanged(currentPosition, value)
					trackList[value]
				}
			}
		}
	private val trackList = ArrayList<Entry>()
	val size: Int
		get() = trackList.size

	fun add(track: Track, to: Int) {
		trackList.add(to, Entry(track))
		callbacks.onPlaylistItemAdded(to)
	}

	fun remove(at: Int) {
		val oldPos = currentPosition
		trackList.removeAt(at)
		callbacks.onPlaylistItemRemoved(at)
		if (at == oldPos) {
			// Currently playing item got removed.
			currentPosition = if (oldPos >= size) 0 else oldPos
		}
	}

	fun getItem(pos: Int?): Track? {
		if (pos == null || pos < 0 || pos >= size)
			return null
		return trackList[pos].toTrack()
	}
}

interface PlaylistCallbacks {
	fun onPlaylistReplaced(oldPlaylist: Playlist?, newPlaylist: Playlist?)
	fun onPlaylistPositionChanged(oldPosition: Int, newPosition: Int)
	fun onPlaylistItemAdded(position: Int)
	fun onPlaylistItemRemoved(position: Int)

	class Dispatcher : PlaylistCallbacks {
		private val callbacks = ArrayList<PlaylistCallbacks>()

		fun registerPlaylistCallback(callback: PlaylistCallbacks) {
			callbacks.add(callback)
		}

		fun unregisterPlaylistCallback(callback: PlaylistCallbacks) {
			callbacks.remove(callback)
		}

		override fun onPlaylistReplaced(oldPlaylist: Playlist?, newPlaylist: Playlist?) {
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

class MusicPlayer(applicationContext: Context) : NextTrackPredictor(), MediaStateCallback,
	PlaylistCallbacks {
	private val mediaPlayer = TransitionMediaPlayer(applicationContext)
	private val playlistCallbacks = PlaylistCallbacks.Dispatcher()
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
	val currentPosition
		get() = timestampMillis
	val duration
		get() = trackDuration
	var playlist: Playlist? = null
		set(value) {
			if (field != value) {
				val old = field
				field = value
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
		jump(getPrevPosition())
	}

	fun next() {
		jump(getNextPosition())
	}

	fun jump(to: Int?) {
		if (to == null) return
		if (playlist == null) return
		if (to < 0 || to >= (playlist?.size ?: 0)) {
			throw IllegalArgumentException("new position out of bounds to=$to cpos=" +
					"${playlist?.currentPosition ?: -1} size=${playlist?.size ?: -1}")
		}
		playlist?.currentPosition = to
	}

	private fun getCurrentItem(): Playable? {
		return playlist?.let {
			return@let it.getItem(it.currentPosition)
		}
	}

	private fun getNextItem(consume: Boolean): Playable? {
		return playlist?.getItem(getNextPosition().also {
			if (consume) it?.let { playlist?.currentPosition = it }
		})
	}

	private fun getPrevPosition(): Int? {
		return getPosition((playlist?.currentPosition ?: 0) - 1)
			?: playlist?.currentPosition
	}

	private fun getNextPosition(): Int? {
		return getPosition((playlist?.currentPosition ?: -1) + 1)
			?: playlist?.currentPosition
	}

	private fun getPosition(pos: Int): Int? {
		var npos: Int? = null
		playlist?.let {
			npos = pos
			if (npos!! < 0 || npos!! >= it.size) {
				npos = if (loopingMode == LoopingMode.LOOPING_MODE_PLAYLIST) {
					// TODO mod or % (they're different in java, how about kotlin?)
					npos!!.mod(it.size)
				} else null
			}
		}
		return npos
	}

	// TODO change this to long
	fun seekTo(positionMills: Int) {
		dispatchSeek(positionMills.toLong())
	}

	fun destroy() {
		hasConsumedFirst = false
		mediaPlayer.destroy()
	}

	override fun onPlaylistReplaced(oldPlaylist: Playlist?, newPlaylist: Playlist?) {
		dispatchPredictionChange(true)
	}

	override fun onPlaylistPositionChanged(oldPosition: Int, newPosition: Int) {
		dispatchPredictionChange(true)
	}

	override fun onPlaylistItemAdded(position: Int) {
		if (position == getNextPosition()) {
			dispatchPredictionChange(false)
		}
	}

	override fun onPlaylistItemRemoved(position: Int) {
		TODO("Not yet implemented")
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

	fun registerPlaylistCallback(callback: PlaylistCallbacks) {
		playlistCallbacks.registerPlaylistCallback(callback)
	}

	fun unregisterPlaylistCallback(callback: PlaylistCallbacks) {
		playlistCallbacks.unregisterPlaylistCallback(callback)
	}

	fun addMediaStateCallback(callback: MediaStateCallback) {
		mediaPlayer.addMediaStateCallback(callback)
	}

	fun removeMediaStateCallback(callback: MediaStateCallback) {
		mediaPlayer.removeMediaStateCallback(callback)
	}
}