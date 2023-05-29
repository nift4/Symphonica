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

import android.app.Service
import android.content.Intent
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.os.IBinder
import org.akanework.symphonica.MainActivity
import org.akanework.symphonica.MainActivity.Companion.playlistViewModel
import org.akanework.symphonica.SymphonicaApplication.Companion.context
import org.akanework.symphonica.logic.util.MusicPlayer
import org.akanework.symphonica.logic.util.broadcastMetaDataUpdate
import org.akanework.symphonica.logic.util.broadcastPlayPaused
import org.akanework.symphonica.logic.util.broadcastPlayStart
import org.akanework.symphonica.logic.util.broadcastPlayStopped
import org.akanework.symphonica.logic.util.broadcastSliderSeek
import org.akanework.symphonica.logic.util.nextSong
import org.akanework.symphonica.logic.util.prevSong

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
class SymphonicaPlayerService : Service(), MediaPlayer.OnPreparedListener {

    private val musicPlayer = MusicPlayer(context)

    private val mediaSessionCallback = object : MediaSession.Callback() {
        override fun onSeekTo(pos: Long) {
            musicPlayer?.seekTo(pos.toInt())

            broadcastSliderSeek()
        }

        override fun onSkipToNext() {
            nextSong()
        }

        override fun onSkipToPrevious() {
            prevSong()
        }

        override fun onPause() {
            musicPlayer.pause()
        }

        override fun onPlay() {
            musicPlayer.play()
        }
    }

    override fun onPrepared(mp: MediaPlayer) {
        mp.start()
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        when (intent.action) {
            "ACTION_REPLACE_AND_PLAY" -> {
                startPlaying()
            }

            "ACTION_PAUSE" -> {
                if (musicPlayer.isPlaying) {
                    musicPlayer!!.pause()
                    broadcastPlayPaused()
                }
            }

            "ACTION_RESUME" -> {
                if (musicPlayer != null && !musicPlayer!!.isPlaying) {
                    musicPlayer!!.play()
                    broadcastPlayStart()
                    killMiniPlayer()
                }
            }

            "ACTION_NEXT" -> {
                musicPlayer.next()
            }

            "ACTION_PREV" -> {
                musicPlayer.prev()
            }

            "ACTION_JUMP" -> {
                musicPlayer.jump(playlistViewModel.currentLocation)
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun startPlaying() {
        if (musicPlayer != null) {
            killMiniPlayer()
            musicPlayer!!.play()
            broadcastPlayStart()
            /*mediaSession.isActive = true
            mediaSession.setCallback(mediaSessionCallback)
            updateMetadata()
            if (MainActivity.managerSymphonica.activeNotifications.isEmpty()) {
                mediaSession.setCallback(mediaSessionCallback)
            }*/
        }
    }

    private fun stopAndPlay() {
        musicPlayer!!.destroy()
        startPlaying()
    }

    private fun killMiniPlayer() {
        if (MainActivity.isMiniPlayerRunning) {
            // Send a broadcast to finish MiniPlayerActivity.
            val intentKillBroadcast = Intent("internal.play_mini_player_stop")
            sendBroadcast(intentKillBroadcast)
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        TODO("Not yet implemented")
    }

    private fun stopPlaying() {
        musicPlayer!!.destroy()
        //musicPlayer = null
        broadcastPlayStopped()
        broadcastMetaDataUpdate()
    }

}