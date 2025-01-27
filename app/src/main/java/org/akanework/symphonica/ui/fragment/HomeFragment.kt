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

package org.akanework.symphonica.ui.fragment

import android.animation.ObjectAnimator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.carousel.CarouselLayoutManager
import com.google.android.material.transition.MaterialSharedAxis
import org.akanework.symphonica.MainActivity
import org.akanework.symphonica.MainActivity.Companion.libraryViewModel
import org.akanework.symphonica.R
import org.akanework.symphonica.logic.data.Song
import org.akanework.symphonica.ui.adapter.SongCarouselAdapter

/**
 * [HomeFragment] is homepage fragment.
 */
class HomeFragment : Fragment() {

    companion object {

        private lateinit var loadingPrompt: MaterialCardView
        private lateinit var shuffleAdapter: SongCarouselAdapter
        private lateinit var recentAdapter: SongCarouselAdapter
        private val shuffleList: MutableList<Song> = mutableListOf()
        private val recentList: MutableList<Song> = mutableListOf()

        private var isInitialized: Boolean = true

        /**
         * This is used for outer class to switch [loadingPrompt].
         * e.g. When loading from disk completed.
         */
        fun switchPrompt(operation: Int) {
            if (::loadingPrompt.isInitialized) {
                when (operation) {
                    0 -> {
                        loadingPrompt.visibility = VISIBLE
                        ObjectAnimator.ofFloat(loadingPrompt, "alpha", 0f, 1f)
                            .setDuration(200)
                            .start()
                        isInitialized = false
                    }

                    1 -> {
                        ObjectAnimator.ofFloat(loadingPrompt, "alpha", 1f, 0f)
                            .setDuration(200)
                            .start()
                        val handler = Handler(Looper.getMainLooper())
                        val runnable = Runnable {
                            loadingPrompt.visibility = GONE
                        }
                        handler.postDelayed(runnable, 200)
                        isInitialized = true
                        initializeList()
                    }

                    else -> {
                        throw IllegalArgumentException()
                    }
                }
            }
        }

        private fun initializeList() {
            if (shuffleList.isEmpty() && libraryViewModel.librarySongList.isNotEmpty()) {
                shuffleList.add(libraryViewModel.librarySongList.random())
                shuffleList.add(libraryViewModel.librarySongList.random())
                shuffleList.add(libraryViewModel.librarySongList.random())
                shuffleList.add(libraryViewModel.librarySongList.random())
                shuffleList.add(libraryViewModel.librarySongList.random())
                shuffleAdapter.notifyItemRangeChanged(0, 5)
            }
            if (libraryViewModel.libraryNewestAddedList.isNotEmpty() && recentList.isEmpty()) {
                recentList.addAll(0, libraryViewModel.libraryNewestAddedList)
                recentAdapter.notifyItemRangeChanged(0, 10)
            }
        }

        /**
         * [refreshList] refreshes shuffleList.
         * It is used for the shuffle button.
         */
        fun refreshList() {
            if (shuffleList.isNotEmpty()) {
                shuffleList.clear()
            }
            shuffleList.add(libraryViewModel.librarySongList.random())
            shuffleList.add(libraryViewModel.librarySongList.random())
            shuffleList.add(libraryViewModel.librarySongList.random())
            shuffleList.add(libraryViewModel.librarySongList.random())
            shuffleList.add(libraryViewModel.librarySongList.random())
            shuffleAdapter.notifyItemRangeChanged(0, 5)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        reenterTransition =
            MaterialSharedAxis(MaterialSharedAxis.X, /* forward= */ false).setDuration(500)

    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        val rootView = inflater.inflate(R.layout.fragment_home, container, false)

        val topAppBar = rootView.findViewById<MaterialToolbar>(R.id.topAppBar)
        val shuffleRefreshButton = rootView.findViewById<MaterialButton>(R.id.refresh_shuffle_list)
        val collapsingToolbar =
            rootView.findViewById<CollapsingToolbarLayout>(R.id.collapsingToolbar)
        val appBarLayout = rootView.findViewById<AppBarLayout>(R.id.appBarLayout)
        val shuffleCarouselRecyclerView =
            rootView.findViewById<RecyclerView>(R.id.shuffle_recycler_view)
        val recentCarouselRecyclerView =
            rootView.findViewById<RecyclerView>(R.id.recent_recycler_view)

        val shuffleLayoutManager = CarouselLayoutManager()
        shuffleCarouselRecyclerView.layoutManager = shuffleLayoutManager
        shuffleAdapter = SongCarouselAdapter(shuffleList)
        shuffleCarouselRecyclerView.adapter = shuffleAdapter

        val recentLayoutManager = CarouselLayoutManager()
        recentCarouselRecyclerView.layoutManager = recentLayoutManager
        recentAdapter = SongCarouselAdapter(recentList)
        recentCarouselRecyclerView.adapter = recentAdapter

        loadingPrompt = rootView.findViewById(R.id.loading_prompt_list)

        topAppBar.setNavigationOnClickListener {
            // Allow open drawer if only initialization have been completed.
            if (isInitialized) {
                MainActivity.switchDrawer()
            }
        }

        shuffleRefreshButton.setOnClickListener {
            refreshList()
        }

        var isShow = true
        var scrollRange = -1

        appBarLayout.addOnOffsetChangedListener { barLayout, verticalOffset ->
            if (scrollRange == -1) {
                scrollRange = barLayout?.totalScrollRange!!
            }
            if (scrollRange + verticalOffset == 0) {
                collapsingToolbar.title = getString(R.string.app_name)
                isShow = true
            } else if (isShow) {
                collapsingToolbar.title =
                    getString(R.string.home_greetings)
                isShow = false
            }
        }

        return rootView
    }

    override fun onResume() {
        super.onResume()
        // Set the current fragment to library
        MainActivity.switchNavigationViewIndex(2)
    }
}