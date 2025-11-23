package com.example.music_room

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.music_room.databinding.ActivityMainBinding
import com.example.music_room.ui.RoomsAdapter
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: com.example.music_room.ui.viewmodel.MainViewModel by viewModels()
    
    private val albumsAdapter = AlbumAdapter { album, imageView ->
        openPlayer(
            songTitle = album.title,
            artistName = album.artist,
            trackId = album.trackId,
            provider = album.provider,
            sharedElement = imageView
        )
    }
    
    private val roomsAdapter = RoomsAdapter { snapshot ->
        val intent = Intent(this, RoomDetailActivity::class.java).apply {
            putExtra(RoomDetailActivity.EXTRA_ROOM_ID, snapshot.room.id)
            putExtra(RoomDetailActivity.EXTRA_ROOM_NAME, snapshot.room.name)
            putExtra(RoomDetailActivity.EXTRA_ROOM_LOCKED, snapshot.isLocked)
            putExtra(RoomDetailActivity.EXTRA_ROOM_HOST_ID, snapshot.room.hostId)
        }
        startActivity(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        binding = ActivityMainBinding.inflate(layoutInflater)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(binding.root)

        setupLists()
        setupActions()
        observeViewModel()
    }

    private fun setupLists() {
        // Rooms Carousel
        binding.roomsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = roomsAdapter
        }

        // Popular Albums
        binding.popularAlbumsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.VERTICAL, false)
            adapter = albumsAdapter
        }
    }

    private fun setupActions() {
        binding.roomsSeeAll.setOnClickListener { 
            startActivity(Intent(this, RoomsActivity::class.java))
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    // Rooms
                    roomsAdapter.submitList(state.rooms)
                    binding.roomsEmptyState.isVisible = state.rooms.isEmpty() && !state.isLoading
                    binding.roomsRecyclerView.isVisible = state.rooms.isNotEmpty()

                    // Albums
                    if (state.isAlbumsLoading) {
                        binding.popularAlbumsRecyclerView.isVisible = false
                        binding.popularAlbumsEmptyState.isVisible = false
                        binding.popularAlbumsSkeleton.isVisible = true
                    } else {
                        binding.popularAlbumsSkeleton.isVisible = false
                        val hasAlbums = state.popularAlbums.isNotEmpty()
                        binding.popularAlbumsRecyclerView.isVisible = hasAlbums
                        binding.popularAlbumsEmptyState.isVisible = !hasAlbums
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
        viewModel.refreshRooms()
    }
}
