package com.example.music_room.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.music_room.Album
import com.example.music_room.data.AuroraServiceLocator
import com.example.music_room.data.remote.model.RoomSnapshotDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MainUiState(
    val isLoading: Boolean = false,
    val trendingRoom: RoomSnapshotDto? = null,
    val popularAlbums: List<Album> = emptyList(),
    val error: String? = null,
    val isAlbumsLoading: Boolean = false
)

class MainViewModel : ViewModel() {

    private val repository = AuroraServiceLocator.repository
    private val itunesApi = AuroraServiceLocator.itunesApi

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        refreshData()
    }

    fun refreshData() {
        viewModelScope.launch {
            loadTrendingRoom()
            loadCuratedAlbums()
        }
    }

    fun refreshTrendingRoom() {
        viewModelScope.launch {
            loadTrendingRoom()
        }
    }

    private suspend fun loadTrendingRoom() {
        // We don't set global loading here to avoid flickering the whole screen if just refreshing room
        repository.getRooms()
            .onSuccess { rooms ->
                _uiState.update { it.copy(trendingRoom = rooms.firstOrNull()) }
            }
            .onFailure { error ->
                _uiState.update { it.copy(error = error.message) }
            }
    }

    private suspend fun loadCuratedAlbums() {
        _uiState.update { it.copy(isAlbumsLoading = true) }
        
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
                _uiState.update { it.copy(popularAlbums = albums, isAlbumsLoading = false) }
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

        try {
            // Parallel fetching for ultra-fast loading
            val deferredResults = curatedQueries.map { query ->
                viewModelScope.async(Dispatchers.IO) {
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
                _uiState.update { it.copy(popularAlbums = albums, isAlbumsLoading = false) }
            } else {
                 loadPopularAlbums(DEFAULT_SEARCH_QUERY)
            }
        } catch (e: Exception) {
             loadPopularAlbums(DEFAULT_SEARCH_QUERY)
        }
    }

    private suspend fun loadPopularAlbums(query: String) {
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
                _uiState.update { it.copy(popularAlbums = mapped, isAlbumsLoading = false) }
            }
            .onFailure { error ->
                _uiState.update { it.copy(error = error.message, isAlbumsLoading = false) }
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

    companion object {
        private const val DEFAULT_SEARCH_QUERY = "This week top hits"
    }
}
