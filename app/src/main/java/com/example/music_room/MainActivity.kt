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
            loadCuratedAlbums()
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
        binding.trendingSeeAll.setOnClickListener {
            startActivity(Intent(this@MainActivity, RoomsActivity::class.java))
        }
        binding.popularSeeAll.setOnClickListener {
            lifecycleScope.launch { loadCuratedAlbums() }
        }
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

    private suspend fun loadCuratedAlbums() {
        binding.popularAlbumsRecyclerView.isVisible = false
        val itunesApi = com.example.music_room.data.AuroraServiceLocator.itunesApi

        try {
            val response = itunesApi.getTopAlbums()
            val results = response.feed.results ?: emptyList()
            
            val albums = results.mapNotNull { result ->
                if (result.name == null || result.artistName == null || result.artworkUrl100 == null) return@mapNotNull null
                
                // Get high-res image
                val highResUrl = result.artworkUrl100.replace("100x100bb", "600x600bb")
                
                Album(
                    title = result.name,
                    artist = result.artistName,
                    trackId = result.id ?: "",
                    provider = "ITUNES",
                    imageUrl = highResUrl,
                    durationSeconds = 0,
                    externalUrl = result.url ?: ""
                )
            }

            if (albums.isNotEmpty()) {
                albumsAdapter.submitList(albums)
                binding.popularAlbumsRecyclerView.isVisible = true
            } else {
                 loadFallbackCuratedAlbums()
            }
        } catch (e: Exception) {
             e.printStackTrace()
             loadFallbackCuratedAlbums()
        }
    }

    private suspend fun loadFallbackCuratedAlbums() {
        val curatedQueries = listOf(
            "The Weeknd After Hours",
            "Taylor Swift Midnights",
            "SZA SOS",
            "Harry Styles Harry's House",
            "Bad Bunny Un Verano Sin Ti",
            "Olivia Rodrigo GUTS",
            "Drake For All The Dogs",
            "Beyonce Renaissance",
            "Kendrick Lamar Mr. Morale",
            "Billie Eilish Hit Me Hard and Soft"
        )

        val albums = mutableListOf<Album>()
        val itunesApi = com.example.music_room.data.AuroraServiceLocator.itunesApi

        try {
            // Parallel fetching for ultra-fast loading
            val deferredResults = curatedQueries.map { query ->
                lifecycleScope.async(Dispatchers.IO) {
                    try {
                        itunesApi.searchAlbums(query).results.firstOrNull()
                    } catch (e: Exception) {
                        null
                    }
                }
            }

            val results = deferredResults.awaitAll()

            results.filterNotNull().forEach { track ->
                // Get high-res image by replacing 100x100 with 600x600
                val highResUrl = track.artworkUrl100.replace("100x100bb", "600x600bb")
                
                albums.add(
                    Album(
                        title = track.collectionName,
                        artist = track.artistName,
                        trackId = track.collectionId.toString(),
                        provider = "ITUNES",
                        imageUrl = highResUrl,
                        durationSeconds = 0,
                        externalUrl = ""
                    )
                )
            }

            if (albums.isNotEmpty()) {
                albumsAdapter.submitList(albums)
                binding.popularAlbumsRecyclerView.isVisible = true
            } else {
                 loadPopularAlbums(DEFAULT_SEARCH_QUERY)
            }
        } catch (e: Exception) {
             loadPopularAlbums(DEFAULT_SEARCH_QUERY)
        }
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
                        imageUrl = getHighResThumbnailUrl(track.thumbnailUrl),
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

    private fun getHighResThumbnailUrl(url: String?): String? {
        if (url == null) return null
        if (url.contains("i.ytimg.com")) {
            return url.replace("default.jpg", "sddefault.jpg")
                .replace("mqdefault.jpg", "sddefault.jpg")
                .replace("hqdefault.jpg", "sddefault.jpg")
        }
        return url
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
