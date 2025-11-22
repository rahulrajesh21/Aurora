package com.example.music_room.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.music_room.Album
import com.example.music_room.data.AuroraServiceLocator
import com.example.music_room.data.remote.model.PopularAlbumDto
import com.example.music_room.data.remote.model.RoomSnapshotDto
import com.example.music_room.utils.sanitizeArtistLabel
import com.example.music_room.utils.sanitizeTrackTitle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MainUiState(
    val isLoading: Boolean = false,
    val rooms: List<RoomSnapshotDto> = emptyList(),
    val popularAlbums: List<Album> = emptyList(),
    val error: String? = null,
    val isAlbumsLoading: Boolean = false
)

class MainViewModel : ViewModel() {

    private val repository = AuroraServiceLocator.repository

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        refreshData()
    }

    fun refreshData() {
        viewModelScope.launch {
            loadRooms()
            loadCuratedAlbums()
        }
    }

    fun refreshRooms() {
        viewModelScope.launch {
            loadRooms()
        }
    }

    private suspend fun loadRooms() {
        repository.getRooms()
            .onSuccess { rooms ->
                _uiState.update { it.copy(rooms = rooms) }
            }
            .onFailure { error ->
                _uiState.update { it.copy(error = error.message) }
            }
    }

    private suspend fun loadCuratedAlbums() {
        _uiState.update { it.copy(isAlbumsLoading = true) }

        repository.getPopularAlbums()
            .onSuccess { response ->
                val albums = response.albums.mapNotNull { it.toAlbum() }
                _uiState.update { it.copy(popularAlbums = albums, isAlbumsLoading = false) }
            }
            .onFailure { error ->
                error.printStackTrace()
                _uiState.update { it.copy(isAlbumsLoading = false, error = error.message) }
            }
    }

    private fun PopularAlbumDto.toAlbum(): Album? {
    val sanitizedTitle = title.takeIf { it.isNotBlank() }?.sanitizeTrackTitle() ?: return null
    val sanitizedArtist = artist.takeIf { it.isNotBlank() } ?: return null
    val displayArtist = sanitizedArtist.sanitizeArtistLabel().ifBlank { sanitizedArtist }
        val sanitizedImage = imageUrl?.takeIf { it.isNotBlank() } ?: return null

        return Album(
            title = sanitizedTitle,
            artist = displayArtist,
            trackId = id,
            provider = "LASTFM",
            imageUrl = sanitizedImage,
            durationSeconds = 0,
            externalUrl = externalUrl
        )
    }

    companion object {
    }
}
