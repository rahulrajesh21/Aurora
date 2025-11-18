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
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

interface AuroraApi {

    @GET("health")
    suspend fun getHealth(): Map<String, String>

    @POST("api/search")
    suspend fun search(@Body body: SearchRequestDto): SearchResponseDto

    @GET("api/playback/state")
    suspend fun getPlaybackState(): PlaybackStateDto

    @POST("api/playback/play")
    suspend fun play(@Body body: PlayRequestDto): PlaybackStateDto

    @POST("api/playback/pause")
    suspend fun pause(): PlaybackStateDto

    @POST("api/playback/resume")
    suspend fun resume(): PlaybackStateDto

    @POST("api/playback/skip")
    suspend fun skip(): PlaybackStateDto

    @POST("api/playback/next")
    suspend fun next(): PlaybackStateDto

    @POST("api/playback/previous")
    suspend fun previous(): PlaybackStateDto

    @POST("api/playback/seek")
    suspend fun seek(@Body body: SeekRequestDto): PlaybackStateDto

    @POST("api/queue/add")
    suspend fun addToQueue(@Body body: AddToQueueRequestDto): QueueResponseDto

    @DELETE("api/queue/{position}")
    suspend fun removeFromQueue(@Path("position") position: Int): QueueResponseDto

    @PUT("api/queue/reorder")
    suspend fun reorderQueue(@Body body: ReorderQueueRequestDto): QueueResponseDto

    @DELETE("api/queue")
    suspend fun clearQueue(): QueueResponseDto

    @POST("api/queue/shuffle")
    suspend fun shuffleQueue(): QueueResponseDto

    @GET("api/queue")
    suspend fun getQueue(): QueueResponseDto

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
