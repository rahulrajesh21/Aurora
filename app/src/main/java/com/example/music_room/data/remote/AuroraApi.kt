package com.example.music_room.data.remote

import com.example.music_room.data.remote.model.AddToQueueRequestDto
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
import com.example.music_room.data.remote.model.HeartbeatRequestDto
import com.example.music_room.data.remote.model.DeleteRoomRequestDto
import com.example.music_room.data.remote.model.PauseRequestDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

interface AuroraApi {

    @GET("health")
    suspend fun getHealth(): Map<String, String>

    @POST("api/search")
    suspend fun search(@Body body: SearchRequestDto): SearchResponseDto

    @GET("api/rooms/{roomId}/playback/state")
    suspend fun getPlaybackState(@Path("roomId") roomId: String): PlaybackStateDto

    @POST("api/rooms/{roomId}/playback/play")
    suspend fun play(@Path("roomId") roomId: String, @Body body: PlayRequestDto): PlaybackStateDto

    @POST("api/rooms/{roomId}/playback/pause")
    suspend fun pause(@Path("roomId") roomId: String, @Body body: PauseRequestDto): PlaybackStateDto

    @POST("api/rooms/{roomId}/playback/resume")
    suspend fun resume(@Path("roomId") roomId: String): PlaybackStateDto

    @POST("api/rooms/{roomId}/playback/skip")
    suspend fun skip(@Path("roomId") roomId: String): PlaybackStateDto

    @POST("api/rooms/{roomId}/playback/next")
    suspend fun next(@Path("roomId") roomId: String): PlaybackStateDto

    @POST("api/rooms/{roomId}/playback/previous")
    suspend fun previous(@Path("roomId") roomId: String): PlaybackStateDto

    @POST("api/rooms/{roomId}/playback/seek")
    suspend fun seek(@Path("roomId") roomId: String, @Body body: SeekRequestDto): PlaybackStateDto

    @POST("api/rooms/{roomId}/queue/add")
    suspend fun addToQueue(@Path("roomId") roomId: String, @Body body: AddToQueueRequestDto): QueueResponseDto

    @DELETE("api/rooms/{roomId}/queue/{position}")
    suspend fun removeFromQueue(@Path("roomId") roomId: String, @Path("position") position: Int): QueueResponseDto

    @PUT("api/rooms/{roomId}/queue/reorder")
    suspend fun reorderQueue(@Path("roomId") roomId: String, @Body body: ReorderQueueRequestDto): QueueResponseDto

    @DELETE("api/rooms/{roomId}/queue")
    suspend fun clearQueue(@Path("roomId") roomId: String): QueueResponseDto

    @POST("api/rooms/{roomId}/queue/shuffle")
    suspend fun shuffleQueue(@Path("roomId") roomId: String): QueueResponseDto

    @GET("api/rooms/{roomId}/queue")
    suspend fun getQueue(@Path("roomId") roomId: String): QueueResponseDto

    @GET("api/rooms")
    suspend fun getRooms(): List<RoomSnapshotDto>

    @POST("api/rooms")
    suspend fun createRoom(@Body body: CreateRoomRequestDto): CreateRoomResponseDto

    @POST("api/rooms/{roomId}/join")
    suspend fun joinRoom(
        @Path("roomId") roomId: String,
        @Body body: JoinRoomRequestDto
    ): JoinRoomResponseDto

    @POST("api/rooms/{roomId}/leave")
    suspend fun leaveRoom(
        @Path("roomId") roomId: String,
        @Body body: LeaveRoomRequestDto
    ): Response<Void>

    @POST("api/rooms/{roomId}/heartbeat")
    suspend fun heartbeat(
        @Path("roomId") roomId: String,
        @Body body: HeartbeatRequestDto
    ): Response<Void>

    @HTTP(method = "DELETE", path = "api/rooms/{roomId}", hasBody = true)
    suspend fun deleteRoom(
        @Path("roomId") roomId: String,
        @Body body: DeleteRoomRequestDto
    ): Response<Void>

    @GET("api/rooms/{roomId}/members")
    suspend fun getRoomMembers(@Path("roomId") roomId: String): RoomMembersResponseDto

    @GET("api/rooms/{roomId}/invites")
    suspend fun getRoomInvites(@Path("roomId") roomId: String): RoomInvitesResponseDto

    @POST("api/rooms/{roomId}/invites")
    suspend fun createInvite(
        @Path("roomId") roomId: String,
        @Body body: CreateInviteRequestDto
    ): CreateInviteResponseDto
}
