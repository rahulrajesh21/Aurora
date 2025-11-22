package com.example.music_room

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.music_room.data.AuroraServiceLocator
import com.example.music_room.databinding.ActivitySearchBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchBinding
    private val repository = AuroraServiceLocator.repository
    private val searchAdapter = AlbumAdapter { album, imageView ->
        openPlayer(
            songTitle = album.title,
            artistName = album.artist,
            trackId = album.trackId,
            provider = album.provider,
            sharedElement = imageView
        )
    }
    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        binding = ActivitySearchBinding.inflate(layoutInflater)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }

        setupSearchList()
        setupSearchView()
    }

    private fun setupSearchList() {
        binding.searchResultsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@SearchActivity)
            adapter = searchAdapter
        }
    }

    private fun setupSearchView() {
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                query?.let { performSearch(it) }
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                searchJob?.cancel()
                if (!newText.isNullOrBlank()) {
                    searchJob = lifecycleScope.launch {
                        delay(500) // Debounce
                        performSearch(newText)
                    }
                } else {
                    binding.searchResultsRecyclerView.isVisible = false
                    binding.recentSearchesTitle.isVisible = true
                }
                return true
            }
        })
        binding.searchView.requestFocus()
    }

    private fun performSearch(query: String) {
        binding.searchLoading.isVisible = true
        binding.recentSearchesTitle.isVisible = false
        
        lifecycleScope.launch {
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
                    searchAdapter.submitList(mapped)
                    binding.searchResultsRecyclerView.isVisible = true
                }
                .onFailure { error ->
                    Toast.makeText(this@SearchActivity, error.message ?: getString(R.string.loading), Toast.LENGTH_SHORT).show()
                }
            binding.searchLoading.isVisible = false
        }
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
}
