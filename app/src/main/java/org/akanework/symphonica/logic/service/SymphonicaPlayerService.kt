/*
 *     Copyright (C) 2023 Akane Foundation
 *
 *     This file is part of Symphonica.
 *
 *     Symphonica is free software: you can redistribute it and/or modify it under the terms
 *     of the GNU General Public License as published by the Free Software Foundation,
 *     either version 3 of the License, or (at your option) any later version.
 *
 *     Symphonica is distributed in the hope that it will be useful, but WITHOUT ANY
 *     WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 *     FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License along with
 *     Symphonica. If not, see <https://www.gnu.org/licenses/>.
 */

package org.akanework.symphonica.logic.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.toBitmap
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import org.akanework.symphonica.MainActivity
import org.akanework.symphonica.MainActivity.Companion.fullSheetShuffleButton
import org.akanework.symphonica.MainActivity.Companion.isListShuffleEnabled
import org.akanework.symphonica.MainActivity.Companion.musicPlayer
import org.akanework.symphonica.MainActivity.Companion.playlistViewModel
import org.akanework.symphonica.R
import org.akanework.symphonica.SymphonicaApplication.Companion.context
import org.akanework.symphonica.logic.data.Song
import org.akanework.symphonica.logic.service.SymphonicaPlayerService.Companion.notification
import org.akanework.symphonica.logic.service.SymphonicaPlayerService.Companion.updateMetadata
import org.akanework.symphonica.logic.util.LoopingMode
import org.akanework.symphonica.logic.util.MediaStateCallback
import org.akanework.symphonica.logic.util.Playlist
import org.akanework.symphonica.logic.util.PlaylistCallbacks
import org.akanework.symphonica.logic.util.Timestamp
import org.akanework.symphonica.logic.util.broadcastMetaDataUpdate
import org.akanework.symphonica.logic.util.changePlayerStatus
import org.akanework.symphonica.logic.util.nextSong
import org.akanework.symphonica.logic.util.prevSong
import org.akanework.symphonica.logic.util.thisSong
import org.akanework.symphonica.ui.component.PlaylistBottomSheet.Companion.updatePlaylistSheetLocation
import kotlin.random.Random

/**
 * [SymphonicaPlayerService] is the core of Symphonica.
 * It used [musicPlayer]'s async method to play songs.
 * It also manages [notification]'s playback metadata
 * updates.
 *
 * SubFunctions:
 * [updateMetadata]
 *
 * Arguments:
 * It receives [Intent] when being called.
 * [playlistViewModel] is used to store playlist and
 * current position across this instance.
 * ----------------------------------------------------
 * 1. "ACTION_REPLACE_AND_PLAY" will replace current playlist
 * and start playing.
 * 2. "ACTION_PAUSE" will pause current player instance.
 * 3. "ACTION_RESUME" will resume current player instance.
 * 4. "ACTION_NEXT" will make the player plays the next song
 * inside the playlist. If not, then stop the instance.
 * 5. "ACTION_PREV" similar to "ACTION_NEXT".
 * 6. "ACTION_JUMP" will jump to target song inside the playlist.
 */
class SymphonicaPlayerService : Service(), MediaStateCallback, PlaylistCallbacks<Song> {

    companion object {
        val mediaSession = MediaSession(context, "PlayerService")
        private val mediaStyle: Notification.MediaStyle =
            Notification.MediaStyle().setMediaSession(mediaSession.sessionToken)
        private val notification = Notification.Builder(context, "channel_symphonica")
            .setStyle(mediaStyle)
            .setSmallIcon(R.drawable.ic_note)
            .setActions()
            .build()


        /**
         * [updateMetadata] is used for [notification] to update its
         * metadata information. You can find this functions all across
         * Symphonica.
         *
         * It does not need any arguments, instead it uses the [playlistViewModel]
         * and [Glide] to update it's info. You can call it up anywhere.
         */
        @SuppressLint("NotificationPermission") // not needed for media notifications
        fun updateMetadata() {
            var initialized = false
            lateinit var bitmapResource: Bitmap
            try {
                Glide.with(context)
                    .asBitmap()
                    .load(playlistViewModel.playList[playlistViewModel.currentLocation].imgUri)
                    .placeholder(R.drawable.ic_song_default_cover)
                    .into(object : CustomTarget<Bitmap>() {
                        override fun onResourceReady(
                            resource: Bitmap,
                            transition: Transition<in Bitmap>?
                        ) {
                            bitmapResource = resource
                            initialized = true
                            mediaSession.setMetadata(
                                MediaMetadata.Builder()
                                    .putString(
                                        MediaMetadata.METADATA_KEY_TITLE,
                                        playlistViewModel.playList[playlistViewModel.currentLocation].title
                                    )
                                    .putString(
                                        MediaMetadata.METADATA_KEY_ARTIST,
                                        playlistViewModel.playList[playlistViewModel.currentLocation].artist
                                    )
                                    .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bitmapResource)
                                    .putLong(
                                        MediaMetadata.METADATA_KEY_DURATION,
                                        playlistViewModel.playList[playlistViewModel.currentLocation].duration
                                    )
                                    .build()
                            )
                        }

                        override fun onLoadCleared(placeholder: Drawable?) {
                            // this is called when imageView is cleared on lifecycle call or for
                            // some other reason.
                            // if you are referencing the bitmap somewhere else too other than this imageView
                            // clear it here as you can no longer have the bitmap
                        }
                    })
            } catch (_: Exception) {
                // Placeholder here.
            }
            if (!initialized) {
                mediaSession.setMetadata(
                    MediaMetadata.Builder()
                        .putString(
                            MediaMetadata.METADATA_KEY_TITLE,
                            playlistViewModel.playList[playlistViewModel.currentLocation].title
                        )
                        .putString(
                            MediaMetadata.METADATA_KEY_ARTIST,
                            playlistViewModel.playList[playlistViewModel.currentLocation].artist
                        )
                        .putBitmap(
                            MediaMetadata.METADATA_KEY_ALBUM_ART,
                            AppCompatResources.getDrawable(
                                context,
                                R.drawable.ic_album_notification_cover
                            )!!.toBitmap()
                        )
                        .putLong(
                            MediaMetadata.METADATA_KEY_DURATION,
                            playlistViewModel.playList[playlistViewModel.currentLocation].duration
                        )
                        .build()
                )
            }
            MainActivity.managerSymphonica.notify(1, notification)
        }
    }

    private val mediaSessionCallback = object : MediaSession.Callback() {
        override fun onSeekTo(pos: Long) {
            musicPlayer?.seekTo(pos)
        }

        override fun onSkipToNext() {
            nextSong()
        }

        override fun onSkipToPrevious() {
            prevSong()
        }

        override fun onPause() {
            if (musicPlayer != null) {
                changePlayerStatus()
            } else if (playlistViewModel.playList.size != 0
                && playlistViewModel.currentLocation != playlistViewModel.playList.size
            ) {
                thisSong()
            }
        }

        override fun onPlay() {
            if (musicPlayer != null) {
                changePlayerStatus()
            } else if (playlistViewModel.playList.size != 0
                && playlistViewModel.currentLocation != playlistViewModel.playList.size
            ) {
                thisSong()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        musicPlayer.addMediaStateCallback(this)
        musicPlayer.registerPlaylistCallback(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        musicPlayer.removeMediaStateCallback(this)
        musicPlayer.unregisterPlaylistCallback(this)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "ACTION_REPLACE_AND_PLAY" -> {
                stopAndPlay()
            }

            "ACTION_PAUSE" -> {
                if (musicPlayer != null && musicPlayer!!.isPlaying) {
                    musicPlayer!!.pause()
                }
            }

            "ACTION_PLAY_PAUSE" -> {
                musicPlayer.playOrPause()
            }

            "ACTION_RESUME" -> {
                if (musicPlayer != null && !musicPlayer!!.isPlaying) {
                    musicPlayer!!.play()
                }
            }

            "ACTION_NEXT" -> {
                if (musicPlayer != null) {
                    nextSongDecisionMaker()
                    startPlaying()
                }
            }

            "ACTION_PREV" -> {
                if (musicPlayer != null) {
                    prevSongDecisionMaker()
                    startPlaying()
                }
            }

            "ACTION_JUMP" -> {
                stopAndPlay()
            }
        }
        return START_STICKY
    }

    private fun startPlaying() {
        musicPlayer!!.playlist = Playlist(playlistViewModel.playList)
        musicPlayer.play()
    }

    private fun stopAndPlay() {
        musicPlayer!!.pause()
        startPlaying()
    }

    private fun killMiniPlayer() {
        if (MainActivity.isMiniPlayerRunning) {
            // Send a broadcast to finish MiniPlayerActivity.
            val intentKillBroadcast = Intent("internal.play_mini_player_stop")
            sendBroadcast(intentKillBroadcast)
        }
    }

    private fun stopPlaying() {
        musicPlayer!!.recycle()
        broadcastMetaDataUpdate()
    }

    private fun prevSongDecisionMaker() {
        val previousLocation = playlistViewModel.currentLocation
        if (!isListShuffleEnabled && musicPlayer.loopingMode != LoopingMode.LOOPING_MODE_TRACK) {
            playlistViewModel.currentLocation =
                if (playlistViewModel.currentLocation == 0 && musicPlayer.loopingMode == LoopingMode.LOOPING_MODE_PLAYLIST && !fullSheetShuffleButton!!.isChecked) {
                    playlistViewModel.playList.size - 1
                } else if (playlistViewModel.currentLocation == 0 && musicPlayer.loopingMode == LoopingMode.LOOPING_MODE_NONE && !fullSheetShuffleButton!!.isChecked) {
                    stopPlaying()
                    0
                } else if (playlistViewModel.currentLocation != 0 && !fullSheetShuffleButton!!.isChecked) {
                    playlistViewModel.currentLocation - 1
                } else if (fullSheetShuffleButton!!.isChecked && playlistViewModel.playList.size != 1) {
                    Random.nextInt(0, playlistViewModel.playList.size)
                } else {
                    0
                }
        } else if (musicPlayer.loopingMode != LoopingMode.LOOPING_MODE_TRACK) {
            playlistViewModel.currentLocation =
                if (playlistViewModel.currentLocation == 0 && musicPlayer.loopingMode == LoopingMode.LOOPING_MODE_NONE) {
                    playlistViewModel.playList.size - 1
                } else if (playlistViewModel.currentLocation == 0 && musicPlayer.loopingMode == LoopingMode.LOOPING_MODE_PLAYLIST) {
                    stopPlaying()
                    0
                } else {
                    playlistViewModel.currentLocation - 1
                }
        }

        // Who the fuck opens the playlist and use media control to select the
        // previous song? Not me.
        updatePlaylistSheetLocation(previousLocation)
    }

    private fun nextSongDecisionMaker() {
        val previousLocation = playlistViewModel.currentLocation
        if (!isListShuffleEnabled && musicPlayer.loopingMode != LoopingMode.LOOPING_MODE_TRACK) {
            playlistViewModel.currentLocation =
                if (playlistViewModel.currentLocation == playlistViewModel.playList.size - 1 && musicPlayer.loopingMode == LoopingMode.LOOPING_MODE_PLAYLIST && !fullSheetShuffleButton!!.isChecked) {
                    0
                } else if (playlistViewModel.currentLocation == playlistViewModel.playList.size - 1 && musicPlayer.loopingMode == LoopingMode.LOOPING_MODE_NONE && !fullSheetShuffleButton!!.isChecked) {
                    stopPlaying()
                    0
                } else if (playlistViewModel.currentLocation != playlistViewModel.playList.size - 1 && !fullSheetShuffleButton!!.isChecked) {
                    playlistViewModel.currentLocation + 1
                } else if (fullSheetShuffleButton!!.isChecked && playlistViewModel.playList.size != 1) {
                    Random.nextInt(0, playlistViewModel.playList.size)
                } else {
                    0
                }
        } else if (musicPlayer.loopingMode != LoopingMode.LOOPING_MODE_TRACK) {
            playlistViewModel.currentLocation =
                if (playlistViewModel.currentLocation == playlistViewModel.playList.size - 1 &&
                    musicPlayer.loopingMode == LoopingMode.LOOPING_MODE_PLAYLIST
                ) {
                    0
                } else if (playlistViewModel.currentLocation == playlistViewModel.playList.size - 1 &&
                    musicPlayer.loopingMode == LoopingMode.LOOPING_MODE_NONE
                ) {
                    stopPlaying()
                    0
                } else {
                    playlistViewModel.currentLocation + 1
                }
        }
        updatePlaylistSheetLocation(previousLocation)
        musicPlayer.playlist?.currentPosition = playlistViewModel.currentLocation
    }

    private fun updatePlaybackState(fakePlaying: Boolean = !musicPlayer.isPlaying) {
        // This method has a funny hack, because Android does NOT care about ANY position
        // update while we are paused, so we have to fake it to playing. Don't ask me why.
        mediaSession.setPlaybackState(PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT
                    or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        (if (musicPlayer.isSeekable) PlaybackState.ACTION_SEEK_TO else 0))
            .setState(
                if (musicPlayer.slowBuffer) PlaybackState.STATE_BUFFERING
                else if (musicPlayer.isPlaying || fakePlaying) PlaybackState.STATE_PLAYING
                else PlaybackState.STATE_PAUSED,
                if (musicPlayer.isSeekable) musicPlayer.currentTimestamp
                else PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                if (musicPlayer.isPlaying) musicPlayer.speed else 0f,
                musicPlayer.timestampUpdateTime
            )
            .setBufferedPosition((musicPlayer.bufferProgress * musicPlayer.duration).toLong())
            .build())
        if (fakePlaying) {
            updatePlaybackState(false)
        }
    }

    override fun onPlayingStatusChanged(playing: Boolean) {
        if (playing) {
            mediaSession.isActive = true
            mediaSession.setCallback(mediaSessionCallback)
            updateMetadata()
            if (MainActivity.managerSymphonica.activeNotifications.isEmpty()) {
                mediaSession.setCallback(mediaSessionCallback)
            }
        }
    }

    override fun onUserPlayingStatusChanged(playing: Boolean) {
        if (playing) {
            killMiniPlayer()
        }
    }

    override fun onLiveInfoAvailable(text: String) {
        // TODO
    }

    override fun onMediaTimestampChanged(timestampMillis: Long) {
        // We care about onMediaTimestampBaseChanged() instead, we are in charge of notification
    }

    override fun onMediaTimestampBaseChanged(timestampBase: Timestamp) {
        updatePlaybackState()
    }

    override fun onSetSeekable(seekable: Boolean) {
        updatePlaybackState()
    }

    override fun onMediaBufferSlowStatus(slowBuffer: Boolean) {
        updatePlaybackState()
    }

    override fun onMediaBufferProgress(progress: Float) {
        updatePlaybackState()
    }

    override fun onMediaHasDecreasedPerformance() {
        // TODO
    }

    override fun onPlaybackError(what: Int) {
        // TODO
    }

    override fun onDurationAvailable(durationMillis: Long) {
        updatePlaybackState()
    }

    override fun onPlaybackSettingsChanged(volume: Float, speed: Float, pitch: Float) {
        updatePlaybackState()
    }

    override fun onPlaylistReplaced(oldPlaylist: Playlist<Song>?, newPlaylist: Playlist<Song>?) {
        // TODO
    }

    override fun onPlaylistPositionChanged(oldPosition: Int, newPosition: Int) {
        // TODO
    }

    override fun onPlaylistItemAdded(position: Int) {
        // TODO
    }

    override fun onPlaylistItemRemoved(position: Int) {
        // TODO
    }

}