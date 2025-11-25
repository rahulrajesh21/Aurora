package com.example.music_room.data.repository

import com.example.music_room.data.remote.AuroraApi
import com.example.music_room.data.remote.model.AddToQueueRequestDto
import com.example.music_room.data.remote.model.ApiErrorResponse

import com.example.music_room.data.remote.model.CreateRoomRequestDto
import com.example.music_room.data.remote.model.CreateRoomResponseDto
import com.example.music_room.data.remote.model.JoinRoomRequestDto
import com.example.music_room.data.remote.model.JoinRoomResponseDto
import com.example.music_room.data.remote.model.LeaveRoomRequestDto
import com.example.music_room.data.remote.model.PlayRequestDto
import com.example.music_room.data.remote.model.PlaybackStateDto
import com.example.music_room.data.remote.model.PauseRequestDto
import com.example.music_room.data.remote.model.PopularAlbumsResponseDto
import com.example.music_room.data.remote.model.QueueResponseDto
import com.example.music_room.data.remote.model.ReorderQueueRequestDto

import com.example.music_room.data.remote.model.RoomSnapshotDto
import com.example.music_room.data.remote.model.SearchRequestDto
import com.example.music_room.data.remote.model.SearchResponseDto
import com.example.music_room.data.remote.model.SeekRequestDto
import com.example.music_room.data.remote.model.HeartbeatRequestDto
import com.example.music_room.data.remote.model.DeleteRoomRequestDto
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import retrofit2.HttpException

class AuroraRepository(
    private val api: AuroraApi,
    moshi: Moshi
) {
    private val errorAdapter = moshi.adapter(ApiErrorResponse::class.java)

    suspend fun search(query: String): Result<SearchResponseDto> = safeCall {
        api.search(SearchRequestDto(query))
    }

    suspend fun getPlaybackState(roomId: String): Result<PlaybackStateDto> = safeCall {
        api.getPlaybackState(roomId)
    }

    suspend fun playTrack(roomId: String, trackId: String, provider: String = "YOUTUBE"): Result<PlaybackStateDto> = safeCall {
        api.play(roomId, PlayRequestDto(trackId = trackId, provider = provider))
    }

    suspend fun pause(roomId: String, positionSeconds: Double?): Result<PlaybackStateDto> = safeCall {
        api.pause(roomId, PauseRequestDto(positionSeconds))
    }

    suspend fun resume(roomId: String): Result<PlaybackStateDto> = safeCall { api.resume(roomId) }

    suspend fun skip(roomId: String): Result<PlaybackStateDto> = safeCall { api.skip(roomId) }

    suspend fun next(roomId: String): Result<PlaybackStateDto> = safeCall { api.next(roomId) }

    suspend fun previous(roomId: String): Result<PlaybackStateDto> = safeCall { api.previous(roomId) }

    suspend fun seekTo(roomId: String, positionSeconds: Int): Result<PlaybackStateDto> = safeCall {
        api.seek(roomId, SeekRequestDto(positionSeconds = positionSeconds))
    }



    suspend fun addToQueue(roomId: String, trackId: String, provider: String = "YOUTUBE", addedBy: String? = null): Result<QueueResponseDto> = safeCall {
        api.addToQueue(roomId, AddToQueueRequestDto(trackId = trackId, provider = provider, addedBy = addedBy))
    }

    suspend fun removeFromQueue(roomId: String, position: Int): Result<QueueResponseDto> = safeCall {
        api.removeFromQueue(roomId, position)
    }

    suspend fun reorderQueue(roomId: String, fromPosition: Int, toPosition: Int): Result<QueueResponseDto> = safeCall {
        api.reorderQueue(roomId, ReorderQueueRequestDto(fromPosition, toPosition))
    }

    suspend fun shuffleQueue(roomId: String): Result<QueueResponseDto> = safeCall { api.shuffleQueue(roomId) }



    suspend fun getQueue(roomId: String): Result<QueueResponseDto> = safeCall { api.getQueue(roomId) }

    suspend fun getPopularAlbums(): Result<PopularAlbumsResponseDto> = safeCall { api.getPopularAlbums() }

    suspend fun prefetchStream(trackId: String): Result<String> = safeCall {
        api.getStreamInfo(trackId).streamUrl
    }

    suspend fun getRooms(): Result<List<RoomSnapshotDto>> = safeCall {
        val rooms = api.getRooms()
        // Fetch queues for all rooms in parallel to populate "Up Next"
        // This is necessary because the rooms list endpoint doesn't include the full queue
        withContext(Dispatchers.IO) {
            rooms.map { room ->
                async {
                    try {
                        val queueResponse = api.getQueue(room.room.id)
                        val queue = queueResponse.queue
                        val nowPlaying = room.nowPlaying?.copy(queue = queue) ?: PlaybackStateDto(
                            currentTrack = null,
                            positionSeconds = 0.0,
                            isPlaying = false,
                            queue = queue,
                            shuffleEnabled = false,
                            timestamp = System.currentTimeMillis(),
                            streamUrl = null,
                            streamFormat = null
                        )
                        room.copy(nowPlaying = nowPlaying)
                    } catch (e: Exception) {
                        // If queue fetch fails, just return the original room
                        room
                    }
                }
            }.map { it.await() }
        }
    }

    suspend fun createRoom(
        name: String,
        hostName: String,
        description: String? = null,
        visibility: String = "PUBLIC",
        passcode: String? = null
    ): Result<CreateRoomResponseDto> = safeCall {
        api.createRoom(
            CreateRoomRequestDto(
                name = name,
                hostName = hostName,
                description = description,
                visibility = visibility,
                passcode = passcode
            )
        )
    }

    suspend fun joinRoom(
        roomId: String,
        displayName: String,
        passcode: String? = null,
        inviteCode: String? = null
    ): Result<JoinRoomResponseDto> = safeCall {
        api.joinRoom(roomId, JoinRoomRequestDto(displayName = displayName, passcode = passcode, inviteCode = inviteCode))
    }

    suspend fun leaveRoom(roomId: String, memberId: String): Result<Unit> = safeCall {
        api.leaveRoom(roomId, LeaveRoomRequestDto(memberId))
        Unit
    }

    suspend fun sendHeartbeat(roomId: String, memberId: String): Result<Unit> = safeCall {
        api.heartbeat(roomId, HeartbeatRequestDto(memberId))
        Unit
    }

    suspend fun deleteRoom(roomId: String, memberId: String): Result<Unit> = safeCall {
        api.deleteRoom(roomId, DeleteRoomRequestDto(memberId))
        Unit
    }





    private suspend fun <T> safeCall(block: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
        try {
            Result.success(block())
        } catch (throwable: Throwable) {
            Result.failure(parseError(throwable))
        }
    }

    private fun parseError(throwable: Throwable): Throwable {
        if (throwable is HttpException) {
            val errorBody = throwable.response()?.errorBody()?.string()
            val apiError = errorBody?.let {
                runCatching { errorAdapter.fromJson(it) }.getOrNull()
            }
            val message = apiError?.message?.takeUnless { it.isBlank() }
                ?: apiError?.error
                ?: throwable.message()
                ?: "Unknown error"
            return RuntimeException(message, throwable)
        }
        return throwable
    }
}
