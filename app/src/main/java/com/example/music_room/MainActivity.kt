package com.example.music_room

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.music_room.data.AuroraServiceLocator
import com.example.music_room.data.remote.model.RoomSnapshotDto
import com.example.music_room.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: com.example.music_room.ui.viewmodel.MainViewModel by androidx.activity.viewModels()
    private val albumsAdapter = AlbumAdapter { album, imageView ->
        openPlayer(
            songTitle = album.title,
            artistName = album.artist,
            trackId = album.trackId,
            provider = album.provider,
            sharedElement = imageView
        )
    }
    private var trendingRoom: RoomSnapshotDto? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        binding = ActivityMainBinding.inflate(layoutInflater)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(binding.root)

        setupMenuButton()
        setupAlbumsList()
        setupTrendingActions()
        observeViewModel()
    }

    private fun setupMenuButton() {
        binding.menuButton.setOnClickListener { anchor ->
            PopupMenu(this, anchor).apply {
                menuInflater.inflate(R.menu.menu_main, menu)
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        R.id.action_create_room -> {
                            startActivity(Intent(this@MainActivity, CreateRoomActivity::class.java))
                            true
                        }
                        R.id.action_browse_rooms -> {
                            startActivity(Intent(this@MainActivity, RoomsActivity::class.java))
                            true
                        }
                        else -> false
                    }
                }
                show()
            }
        }
    }

    private fun setupAlbumsList() {
        binding.popularAlbumsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = albumsAdapter
        }
    }

    private fun setupTrendingActions() {
        binding.joinRoomButton.setOnClickListener { openTrendingRoom() }
        binding.trendingCard.setOnClickListener { openTrendingRoom() }
        binding.searchIcon.setOnClickListener { promptSearch() }
        binding.trendingSeeAll.setOnClickListener {
            startActivity(Intent(this@MainActivity, RoomsActivity::class.java))
        }
        binding.popularSeeAll.setOnClickListener {
            viewModel.refreshData()
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            androidx.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    // Trending Room
                    trendingRoom = state.trendingRoom
                    binding.joinRoomButton.isEnabled = trendingRoom != null
                    if (trendingRoom == null) {
                        if (state.isLoading && state.trendingRoom == null) {
                            binding.roomName.text = getString(R.string.loading)
                        } else {
                            binding.roomName.text = getString(R.string.rooms_empty)
                            binding.hostName.text = ""
                            binding.currentSong.text = getString(R.string.no_track_playing)
                            binding.currentArtist.text = getString(R.string.no_track_artist)
                            binding.listenerCount.text = "0"
                        }
                    } else {
                        applyTrendingSnapshot(trendingRoom!!)
                    }

                    // Albums
                    if (state.isAlbumsLoading) {
                        binding.popularAlbumsRecyclerView.isVisible = false
                        binding.popularAlbumsSkeleton.isVisible = true
                    } else {
                        binding.popularAlbumsSkeleton.isVisible = false
                        binding.popularAlbumsRecyclerView.isVisible = true
                        albumsAdapter.submitList(state.popularAlbums)
                    }

                    // Error
                    if (state.error != null) {
                        Toast.makeText(this@MainActivity, state.error, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun applyTrendingSnapshot(snapshot: RoomSnapshotDto) {
        binding.roomName.text = snapshot.room.name
        binding.hostName.text = getString(R.string.hosted_by_template, snapshot.room.hostName)
        binding.listenerCount.text = snapshot.memberCount.toString()
        val track = snapshot.nowPlaying?.currentTrack
        binding.currentSong.text = track?.title ?: getString(R.string.no_track_playing)
        binding.currentArtist.text = track?.artist ?: getString(R.string.no_track_artist)
    }

    private fun promptSearch() {
        startActivity(Intent(this, SearchActivity::class.java))
    }

    private fun openTrendingRoom() {
        val room = trendingRoom ?: return
        val intent = Intent(this, RoomDetailActivity::class.java).apply {
            putExtra(RoomDetailActivity.EXTRA_ROOM_ID, room.room.id)
            putExtra(RoomDetailActivity.EXTRA_ROOM_NAME, room.room.name)
            putExtra(RoomDetailActivity.EXTRA_ROOM_LOCKED, room.isLocked)
        }
        startActivity(intent)
    }

    private fun openPlayer(
        songTitle: String,
        artistName: String,
        trackId: String?,
        provider: String?,
        sharedElement: android.widget.ImageView
    ) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_SONG_TITLE, songTitle)
            putExtra(PlayerActivity.EXTRA_ARTIST_NAME, artistName)
            putExtra(PlayerActivity.EXTRA_TRACK_ID, trackId)
            putExtra(PlayerActivity.EXTRA_PROVIDER, provider)
            putExtra(PlayerActivity.EXTRA_TRANSITION_NAME, sharedElement.transitionName)
        }
        val options = androidx.core.app.ActivityOptionsCompat.makeSceneTransitionAnimation(
            this,
            sharedElement,
            sharedElement.transitionName
        )
        startActivity(intent, options.toBundle())
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshTrendingRoom()
    }
}
