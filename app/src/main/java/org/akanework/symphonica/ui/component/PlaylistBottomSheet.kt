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

package org.akanework.symphonica.ui.component

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.akanework.symphonica.MainActivity.Companion.musicPlayer
import org.akanework.symphonica.R
import org.akanework.symphonica.SymphonicaApplication
import org.akanework.symphonica.ui.adapter.PlaylistAdapter

/**
 * [PlaylistBottomSheet] is the [BottomSheetDialogFragment]
 * that will open when you click the playlist button inside
 * full player.
 */
class PlaylistBottomSheet : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "PlaylistBottomSheet"
        lateinit var adapter: PlaylistAdapter

        /**
         * [updatePlaylistSheetLocation] updates playlist's
         * now playing location indicator.
         */
        fun updatePlaylistSheetLocation(prevLocation: Int) {
            if (::adapter.isInitialized) {
                adapter.notifyItemChanged(prevLocation)
                adapter.notifyItemChanged(musicPlayer.playlist!!.currentPosition)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val rootView = inflater.inflate(R.layout.playlist_bottom_sheet, container, false)
        val playlistView: RecyclerView = rootView.findViewById(R.id.playlist_recyclerview)

        val layoutManager = LinearLayoutManager(SymphonicaApplication.context)
        adapter = PlaylistAdapter(musicPlayer.playlist!!.toMutableList())

        playlistView.layoutManager = layoutManager
        playlistView.adapter = adapter

        playlistView.post {
            layoutManager.scrollToPositionWithOffset(musicPlayer.playlist!!.currentPosition, 20)
        }
        return rootView
    }

}