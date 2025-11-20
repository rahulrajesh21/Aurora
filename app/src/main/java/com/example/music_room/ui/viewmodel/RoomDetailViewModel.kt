package com.example.music_room.ui.viewmodel

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.music_room.data.AuroraServiceLocator
import com.example.music_room.data.remote.model.PlaybackStateDto
import com.example.music_room.data.remote.model.TrackDto
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class RoomDetailUiState(
    val isLoading: Boolean = false,
    val playbackState: PlaybackStateDto? = null,
    val queue: List<TrackDto> = emptyList(),
    val error: String? = null,
    val memberId: String? = null,
    val isQueueLoading: Boolean = false,
    val isQueueEmpty: Boolean = false,
    val canDeleteRoom: Boolean = false
)

class RoomDetailViewModel : ViewModel() {

    private val repository = AuroraServiceLocator.repository
    private var playbackSocket: com.example.music_room.data.socket.PlaybackSocketClient? = null
    private var heartbeatJob: Job? = null

    private val _uiState = MutableStateFlow(RoomDetailUiState())
    val uiState: StateFlow<RoomDetailUiState> = _uiState.asStateFlow()

    // Playback ticker state
    var sliderBasePositionSeconds = 0f
    var sliderBaseTimestamp = 0L
    
    private var currentRoomId: String? = null
    private var hostMemberId: String? = null

    fun setRoomId(id: String) {
        currentRoomId = id
        playbackSocket = AuroraServiceLocator.createPlaybackSocket(id)
    }

    fun setRoomHostId(hostId: String?) {
        hostMemberId = hostId
        _uiState.update { it.copy(canDeleteRoom = canDelete(it.memberId)) }
    }

    override fun onCleared() {
        super.onCleared()
        stopHeartbeat()
        playbackSocket?.disconnect()
    }

    fun connectSocket() {
        playbackSocket?.connect(
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
        playbackSocket?.disconnect()
    }

    fun setMemberId(id: String?) {
        _uiState.update { it.copy(memberId = id, canDeleteRoom = canDelete(id)) }
        if (id == null) {
            stopHeartbeat()
        } else {
            startHeartbeat(id)
        }
    }

    private var isJoining = false

    fun joinRoom(roomId: String, displayName: String, passcode: String? = null, inviteCode: String? = null, onSuccess: (String) -> Unit) {
        if (isJoining || _uiState.value.memberId != null) return

        isJoining = true
        currentRoomId = roomId
        viewModelScope.launch {
            repository.joinRoom(roomId, displayName, passcode, inviteCode)
                .onSuccess { response ->
                    setMemberId(response.member.id)
                    onSuccess(response.member.id)
                }
                .onFailure { error ->
                    _uiState.update { it.copy(error = error.message) }
                }
            isJoining = false
        }
    }

    suspend fun leaveRoom(roomId: String, memberId: String) {
        // Perform the leave operation and wait for it to complete
        repository.leaveRoom(roomId, memberId)
        setMemberId(null)
    }

    fun deleteRoom(roomId: String, onResult: (Boolean, String?) -> Unit) {
        val memberId = _uiState.value.memberId
        if (memberId == null) {
            onResult(false, "Join the room before deleting it")
            return
        }
        viewModelScope.launch {
            repository.deleteRoom(roomId, memberId)
                .onSuccess {
                    onResult(true, null)
                }
                .onFailure { error ->
                    _uiState.update { it.copy(error = error.message) }
                    onResult(false, error.message)
                }
        }
    }

    fun refreshPlaybackState() {
        val roomId = currentRoomId ?: return
        viewModelScope.launch {
            repository.getPlaybackState(roomId)
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
        val roomId = currentRoomId ?: return
        _uiState.update { it.copy(isQueueLoading = true) }
        viewModelScope.launch {
            repository.getQueue(roomId)
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
                            isQueueEmpty = true 
                        ) 
                    }
                }
        }
    }

    fun promoteTrack(position: Int) {
        val roomId = currentRoomId ?: return
        viewModelScope.launch {
            repository.reorderQueue(roomId, position, 0)
                .onSuccess { refreshQueue() }
                .onFailure { error -> _uiState.update { it.copy(error = error.message) } }
        }
    }

    fun removeTrack(position: Int) {
        val roomId = currentRoomId ?: return
        viewModelScope.launch {
            repository.removeFromQueue(roomId, position)
                .onSuccess { refreshQueue() }
                .onFailure { error -> _uiState.update { it.copy(error = error.message) } }
        }
    }

    fun reorderQueue(from: Int, to: Int) {
        val roomId = currentRoomId ?: return
        viewModelScope.launch {
            repository.reorderQueue(roomId, from, to)
                .onSuccess { refreshQueue() }
                .onFailure { error -> 
                    _uiState.update { it.copy(error = error.message) }
                    refreshQueue() // Revert
                }
        }
    }

    fun togglePlayPause() {
        val roomId = currentRoomId ?: return
        val isPlaying = _uiState.value.playbackState?.isPlaying == true
        viewModelScope.launch {
            val result = if (isPlaying) repository.pause(roomId) else repository.resume(roomId)
            result.onSuccess { state ->
                _uiState.update { it.copy(playbackState = state) }
                updateTickerBase(state)
            }.onFailure { error ->
                _uiState.update { it.copy(error = error.message) }
            }
        }
    }

    fun skipTrack() {
        val roomId = currentRoomId ?: return
        viewModelScope.launch {
            repository.next(roomId)
                .onSuccess { state ->
                    _uiState.update { it.copy(playbackState = state) }
                    updateTickerBase(state)
                }
                .onFailure { error -> _uiState.update { it.copy(error = error.message) } }
        }
    }

    fun previousTrack() {
        val roomId = currentRoomId ?: return
        viewModelScope.launch {
            repository.previous(roomId)
                .onSuccess { state ->
                    _uiState.update { it.copy(playbackState = state) }
                    updateTickerBase(state)
                }
                .onFailure { error -> _uiState.update { it.copy(error = error.message) } }
        }
    }

    fun shuffleQueue() {
        val roomId = currentRoomId ?: return
        viewModelScope.launch {
            repository.shuffleQueue(roomId)
                .onSuccess { refreshQueue() }
                .onFailure { error -> _uiState.update { it.copy(error = error.message) } }
        }
    }

    fun restartTrack() {
        val roomId = currentRoomId ?: return
        viewModelScope.launch {
            repository.seekTo(roomId, 0)
                .onSuccess { state ->
                    _uiState.update { it.copy(playbackState = state) }
                    updateTickerBase(state)
                }
                .onFailure { error -> _uiState.update { it.copy(error = error.message) } }
        }
    }

    fun seekTo(position: Int) {
        val roomId = currentRoomId ?: return
        viewModelScope.launch {
            repository.seekTo(roomId, position)
                .onSuccess { state ->
                    _uiState.update { it.copy(playbackState = state) }
                    updateTickerBase(state)
                }
                .onFailure { error -> _uiState.update { it.copy(error = error.message) } }
        }
    }

    fun playTrack(trackId: String, provider: String) {
        val roomId = currentRoomId ?: return
        viewModelScope.launch {
            repository.playTrack(roomId, trackId, provider)
                .onSuccess { state ->
                    _uiState.update { it.copy(playbackState = state) }
                    updateTickerBase(state)
                }
                .onFailure { error -> _uiState.update { it.copy(error = error.message) } }
        }
    }

    fun addToQueue(trackId: String, provider: String, addedBy: String?) {
        val roomId = currentRoomId ?: return
        viewModelScope.launch {
            repository.addToQueue(roomId, trackId, provider, addedBy)
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

    private fun startHeartbeat(memberId: String) {
        val roomId = currentRoomId ?: return
        heartbeatJob?.cancel()
        heartbeatJob = viewModelScope.launch {
            while (isActive) {
                repository.sendHeartbeat(roomId, memberId)
                    .onFailure { error -> Log.w(TAG, "Heartbeat failed", error) }
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun canDelete(memberId: String?): Boolean {
        return memberId != null && memberId == hostMemberId
    }

    private fun updateTickerBase(state: PlaybackStateDto) {
        sliderBasePositionSeconds = state.positionSeconds.toFloat()
        sliderBaseTimestamp = SystemClock.elapsedRealtime()
    }

    companion object {
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
        private const val TAG = "RoomDetailViewModel"
    }
}
