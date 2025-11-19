package com.example.music_room.ui.viewmodel

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.music_room.data.AuroraServiceLocator
import com.example.music_room.data.remote.model.PlaybackStateDto
import com.example.music_room.data.remote.model.TrackDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RoomDetailUiState(
    val isLoading: Boolean = false,
    val playbackState: PlaybackStateDto? = null,
    val queue: List<TrackDto> = emptyList(),
    val error: String? = null,
    val memberId: String? = null,
    val isQueueLoading: Boolean = false,
    val isQueueEmpty: Boolean = false
)

class RoomDetailViewModel : ViewModel() {

    private val repository = AuroraServiceLocator.repository
    private val playbackSocket = AuroraServiceLocator.createPlaybackSocket()

    private val _uiState = MutableStateFlow(RoomDetailUiState())
    val uiState: StateFlow<RoomDetailUiState> = _uiState.asStateFlow()

    // Playback ticker state
    var sliderBasePositionSeconds = 0f
    var sliderBaseTimestamp = 0L

    override fun onCleared() {
        super.onCleared()
        playbackSocket.disconnect()
    }

    fun connectSocket() {
        playbackSocket.connect(
            onState = { state -> 
                _uiState.update { it.copy(playbackState = state) }
                updateTickerBase(state)
            },
            onError = { error ->
                _uiState.update { it.copy(error = error.message) }
            }
        )
    }

    fun disconnectSocket() {
        playbackSocket.disconnect()
    }

    fun setMemberId(id: String) {
        _uiState.update { it.copy(memberId = id) }
    }

    fun joinRoom(roomId: String, displayName: String, passcode: String? = null, inviteCode: String? = null, onSuccess: (String) -> Unit) {
        viewModelScope.launch {
            repository.joinRoom(roomId, displayName, passcode, inviteCode)
                .onSuccess { response ->
                    setMemberId(response.member.id)
                    onSuccess(response.member.id)
                }
                .onFailure { error ->
                    _uiState.update { it.copy(error = error.message) }
                }
        }
    }

    fun leaveRoom(roomId: String, memberId: String) {
        viewModelScope.launch {
            repository.leaveRoom(roomId, memberId)
            _uiState.update { it.copy(memberId = null) }
        }
    }

    fun refreshPlaybackState() {
        viewModelScope.launch {
            repository.getPlaybackState()
                .onSuccess { state ->
                    _uiState.update { it.copy(playbackState = state) }
                    updateTickerBase(state)
                }
                .onFailure { error ->
                    _uiState.update { it.copy(error = error.message) }
                }
        }
    }

    fun refreshQueue() {
        _uiState.update { it.copy(isQueueLoading = true) }
        viewModelScope.launch {
            repository.getQueue()
                .onSuccess { response ->
                    _uiState.update { 
                        it.copy(
                            queue = response.queue, 
                            isQueueLoading = false,
                            isQueueEmpty = response.queue.isEmpty()
                        ) 
                    }
                }
                .onFailure { error ->
                    _uiState.update { 
                        it.copy(
                            error = error.message, 
                            isQueueLoading = false,
                            isQueueEmpty = true // Assume empty on error for UI simplicity or handle differently
                        ) 
                    }
                }
        }
    }

    fun promoteTrack(position: Int) {
        viewModelScope.launch {
            repository.reorderQueue(position, 0)
                .onSuccess { refreshQueue() }
                .onFailure { error -> _uiState.update { it.copy(error = error.message) } }
        }
    }

    fun removeTrack(position: Int) {
        viewModelScope.launch {
            repository.removeFromQueue(position)
                .onSuccess { refreshQueue() }
                .onFailure { error -> _uiState.update { it.copy(error = error.message) } }
        }
    }

    fun reorderQueue(from: Int, to: Int) {
        viewModelScope.launch {
            repository.reorderQueue(from, to)
                .onSuccess { refreshQueue() }
                .onFailure { error -> 
                    _uiState.update { it.copy(error = error.message) }
                    refreshQueue() // Revert
                }
        }
    }

    fun togglePlayPause() {
        val isPlaying = _uiState.value.playbackState?.isPlaying == true
        viewModelScope.launch {
            val result = if (isPlaying) repository.pause() else repository.resume()
            result.onSuccess { state ->
                _uiState.update { it.copy(playbackState = state) }
                updateTickerBase(state)
            }.onFailure { error ->
                _uiState.update { it.copy(error = error.message) }
            }
        }
    }

    fun skipTrack() {
        viewModelScope.launch {
            repository.next()
                .onSuccess { state ->
                    _uiState.update { it.copy(playbackState = state) }
                    updateTickerBase(state)
                }
                .onFailure { error -> _uiState.update { it.copy(error = error.message) } }
        }
    }

    fun previousTrack() {
        viewModelScope.launch {
            repository.previous()
                .onSuccess { state ->
                    _uiState.update { it.copy(playbackState = state) }
                    updateTickerBase(state)
                }
                .onFailure { error -> _uiState.update { it.copy(error = error.message) } }
        }
    }

    fun shuffleQueue() {
        viewModelScope.launch {
            repository.shuffleQueue()
                .onSuccess { refreshQueue() }
                .onFailure { error -> _uiState.update { it.copy(error = error.message) } }
        }
    }

    fun restartTrack() {
        viewModelScope.launch {
            repository.seekTo(0)
                .onSuccess { state ->
                    _uiState.update { it.copy(playbackState = state) }
                    updateTickerBase(state)
                }
                .onFailure { error -> _uiState.update { it.copy(error = error.message) } }
        }
    }

    fun seekTo(position: Int) {
        viewModelScope.launch {
            repository.seekTo(position)
                .onSuccess { state ->
                    _uiState.update { it.copy(playbackState = state) }
                    updateTickerBase(state)
                }
                .onFailure { error -> _uiState.update { it.copy(error = error.message) } }
        }
    }

    fun playTrack(trackId: String, provider: String) {
        viewModelScope.launch {
            repository.playTrack(trackId, provider)
                .onSuccess { state ->
                    _uiState.update { it.copy(playbackState = state) }
                    updateTickerBase(state)
                }
                .onFailure { error -> _uiState.update { it.copy(error = error.message) } }
        }
    }

    fun addToQueue(trackId: String, provider: String, addedBy: String?) {
        viewModelScope.launch {
            repository.addToQueue(trackId, provider, addedBy)
                .onSuccess { refreshQueue() }
                .onFailure { error -> _uiState.update { it.copy(error = error.message) } }
        }
    }

    fun search(query: String, onSuccess: (List<TrackDto>) -> Unit, onError: (Throwable) -> Unit) {
        viewModelScope.launch {
            repository.search(query)
                .onSuccess { response -> onSuccess(response.tracks) }
                .onFailure { onError(it) }
        }
    }
    
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    private fun updateTickerBase(state: PlaybackStateDto) {
        sliderBasePositionSeconds = state.positionSeconds.toFloat()
        sliderBaseTimestamp = SystemClock.elapsedRealtime()
    }
}
