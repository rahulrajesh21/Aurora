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

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val repository = AuroraServiceLocator.repository
    private val albumsAdapter = AlbumAdapter { album ->
        openPlayer(
            songTitle = album.title,
            artistName = album.artist,
            trackId = album.trackId,
            provider = album.provider
        )
    }
    private var trendingRoom: RoomSnapshotDto? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupMenuButton()
        setupAlbumsList()
        setupTrendingActions()

        lifecycleScope.launch {
            loadTrendingRoom()
            loadPopularAlbums(DEFAULT_SEARCH_QUERY)
        }
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
    }

    private suspend fun loadTrendingRoom() {
        binding.joinRoomButton.isEnabled = false
        binding.roomName.text = getString(R.string.loading)
        repository.getRooms()
            .onSuccess { rooms ->
                trendingRoom = rooms.firstOrNull()
                binding.joinRoomButton.isEnabled = trendingRoom != null
                if (trendingRoom == null) {
                    binding.roomName.text = getString(R.string.rooms_empty)
                    binding.hostName.text = ""
                    binding.currentSong.text = getString(R.string.no_track_playing)
                    binding.currentArtist.text = getString(R.string.no_track_artist)
                    binding.listenerCount.text = "0"
                } else {
                    applyTrendingSnapshot(trendingRoom!!)
                }
            }
            .onFailure { error ->
                binding.joinRoomButton.isEnabled = false
                Toast.makeText(this, error.message ?: getString(R.string.loading), Toast.LENGTH_LONG).show()
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

    private suspend fun loadPopularAlbums(query: String) {
        binding.popularAlbumsRecyclerView.isVisible = false
        repository.search(query)
            .onSuccess { response ->
                val mapped = response.tracks.map { track ->
                    Album(
                        title = track.title,
                        artist = track.artist,
                        trackId = track.id,
                        provider = track.provider,
                        imageUrl = track.thumbnailUrl,
                        durationSeconds = track.durationSeconds,
                        externalUrl = track.externalUrl
                    )
                }
                albumsAdapter.submitList(mapped)
            }
            .onFailure { error ->
                Toast.makeText(this, error.message ?: getString(R.string.loading), Toast.LENGTH_LONG).show()
            }
        binding.popularAlbumsRecyclerView.isVisible = true
    }

    private fun promptSearch() {
        val editText = com.google.android.material.textfield.TextInputEditText(this).apply {
            hint = getString(R.string.search_hint)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.search_hint)
            .setView(editText)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                val query = editText.text?.toString()?.trim().orEmpty()
                if (query.isNotEmpty()) {
                    lifecycleScope.launch { loadPopularAlbums(query) }
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.dismiss, null)
            .show()
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

    private fun openPlayer(songTitle: String, artistName: String, trackId: String?, provider: String?) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_SONG_TITLE, songTitle)
            putExtra(PlayerActivity.EXTRA_ARTIST_NAME, artistName)
            putExtra(PlayerActivity.EXTRA_TRACK_ID, trackId)
            putExtra(PlayerActivity.EXTRA_PROVIDER, provider)
        }
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            loadTrendingRoom()
        }
    }

    companion object {
        private const val DEFAULT_SEARCH_QUERY = "This week top hits"
    }
}
