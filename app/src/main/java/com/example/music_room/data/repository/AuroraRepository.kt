package com.example.music_room.data.repository

import com.example.music_room.data.remote.AuroraApi
import com.example.music_room.data.remote.model.AddToQueueRequestDto
import com.example.music_room.data.remote.model.ApiErrorResponse
import com.example.music_room.data.remote.model.CreateInviteRequestDto
import com.example.music_room.data.remote.model.CreateInviteResponseDto
import com.example.music_room.data.remote.model.CreateRoomRequestDto
import com.example.music_room.data.remote.model.CreateRoomResponseDto
import com.example.music_room.data.remote.model.JoinRoomRequestDto
import com.example.music_room.data.remote.model.JoinRoomResponseDto
import com.example.music_room.data.remote.model.LeaveRoomRequestDto
import com.example.music_room.data.remote.model.PlayRequestDto
import com.example.music_room.data.remote.model.PlaybackStateDto
import com.example.music_room.data.remote.model.QueueResponseDto
import com.example.music_room.data.remote.model.ReorderQueueRequestDto
import com.example.music_room.data.remote.model.RoomInvitesResponseDto
import com.example.music_room.data.remote.model.RoomMembersResponseDto
import com.example.music_room.data.remote.model.RoomSnapshotDto
import com.example.music_room.data.remote.model.SearchRequestDto
import com.example.music_room.data.remote.model.SearchResponseDto
import com.example.music_room.data.remote.model.SeekRequestDto
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
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

    suspend fun getPlaybackState(): Result<PlaybackStateDto> = safeCall {
        api.getPlaybackState()
    }

    suspend fun playTrack(trackId: String, provider: String = "YOUTUBE"): Result<PlaybackStateDto> = safeCall {
        api.play(PlayRequestDto(trackId = trackId, provider = provider))
    }

    suspend fun pause(): Result<PlaybackStateDto> = safeCall { api.pause() }

    suspend fun resume(): Result<PlaybackStateDto> = safeCall { api.resume() }

    suspend fun skip(): Result<PlaybackStateDto> = safeCall { api.skip() }

    suspend fun next(): Result<PlaybackStateDto> = safeCall { api.next() }

    suspend fun previous(): Result<PlaybackStateDto> = safeCall { api.previous() }

    suspend fun seekTo(positionSeconds: Int): Result<PlaybackStateDto> = safeCall {
        api.seek(SeekRequestDto(positionSeconds = positionSeconds))
    }

    suspend fun seekPercentage(percentage: Double): Result<PlaybackStateDto> = safeCall {
        api.seek(SeekRequestDto(percentage = percentage))
    }

    suspend fun addToQueue(trackId: String, provider: String = "YOUTUBE", addedBy: String? = null): Result<QueueResponseDto> = safeCall {
        api.addToQueue(AddToQueueRequestDto(trackId = trackId, provider = provider, addedBy = addedBy))
    }

    suspend fun removeFromQueue(position: Int): Result<QueueResponseDto> = safeCall {
        api.removeFromQueue(position)
    }

    suspend fun reorderQueue(fromPosition: Int, toPosition: Int): Result<QueueResponseDto> = safeCall {
        api.reorderQueue(ReorderQueueRequestDto(fromPosition, toPosition))
    }

    suspend fun shuffleQueue(): Result<QueueResponseDto> = safeCall { api.shuffleQueue() }

    suspend fun clearQueue(): Result<QueueResponseDto> = safeCall { api.clearQueue() }

    suspend fun getQueue(): Result<QueueResponseDto> = safeCall { api.getQueue() }

    suspend fun getRooms(): Result<List<RoomSnapshotDto>> = safeCall { api.getRooms() }

    suspend fun createRoom(
        name: String,
        hostName: String,
        visibility: String = "PUBLIC",
        passcode: String? = null
    ): Result<CreateRoomResponseDto> = safeCall {
        api.createRoom(
            CreateRoomRequestDto(
                name = name,
                hostName = hostName,
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

    suspend fun getRoomMembers(roomId: String): Result<RoomMembersResponseDto> = safeCall {
        api.getRoomMembers(roomId)
    }

    suspend fun getRoomInvites(roomId: String): Result<RoomInvitesResponseDto> = safeCall {
        api.getRoomInvites(roomId)
    }

    suspend fun createInvite(
        roomId: String,
        requestedBy: String,
        maxUses: Int? = null,
        ttlSeconds: Int? = null
    ): Result<CreateInviteResponseDto> = safeCall {
        api.createInvite(roomId, CreateInviteRequestDto(requestedBy = requestedBy, maxUses = maxUses, ttlSeconds = ttlSeconds))
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
