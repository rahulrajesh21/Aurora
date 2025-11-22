package com.example.music_room.data.remote.model

import com.squareup.moshi.Json

data class RoomDto(
    @Json(name = "id") val id: String,
    @Json(name = "name") val name: String,
    @Json(name = "hostId") val hostId: String,
    @Json(name = "hostName") val hostName: String,
    @Json(name = "visibility") val visibility: String,
    @Json(name = "maxMembers") val maxMembers: Int,
    @Json(name = "description") val description: String? = null,
    @Json(name = "passcode") val passcode: String? = null,
    @Json(name = "createdAt") val createdAt: Long,
    @Json(name = "updatedAt") val updatedAt: Long
)

data class RoomSnapshotDto(
    @Json(name = "room") val room: RoomDto,
    @Json(name = "memberCount") val memberCount: Int,
    @Json(name = "isLocked") val isLocked: Boolean,
    @Json(name = "nowPlaying") val nowPlaying: PlaybackStateDto? = null
)

data class RoomMemberDto(
    @Json(name = "id") val id: String,
    @Json(name = "displayName") val displayName: String,
    @Json(name = "joinedAt") val joinedAt: Long,
    @Json(name = "lastActiveAt") val lastActiveAt: Long,
    @Json(name = "isHost") val isHost: Boolean
)

data class RoomInviteDto(
    @Json(name = "code") val code: String,
    @Json(name = "roomId") val roomId: String,
    @Json(name = "createdByMemberId") val createdByMemberId: String,
    @Json(name = "createdAt") val createdAt: Long,
    @Json(name = "expiresAt") val expiresAt: Long,
    @Json(name = "maxUses") val maxUses: Int,
    @Json(name = "uses") val uses: Int
)

data class CreateRoomRequestDto(
    @Json(name = "name") val name: String,
    @Json(name = "hostName") val hostName: String,
    @Json(name = "description") val description: String? = null,
    @Json(name = "visibility") val visibility: String = "PUBLIC",
    @Json(name = "passcode") val passcode: String? = null
)

data class CreateRoomResponseDto(
    @Json(name = "room") val room: RoomDto,
    @Json(name = "host") val host: RoomMemberDto
)

data class JoinRoomRequestDto(
    @Json(name = "displayName") val displayName: String,
    @Json(name = "passcode") val passcode: String? = null,
    @Json(name = "inviteCode") val inviteCode: String? = null
)

data class JoinRoomResponseDto(
    @Json(name = "member") val member: RoomMemberDto
)

data class LeaveRoomRequestDto(
    @Json(name = "memberId") val memberId: String
)

data class HeartbeatRequestDto(
    @Json(name = "memberId") val memberId: String
)

data class DeleteRoomRequestDto(
    @Json(name = "memberId") val memberId: String
)

data class CreateInviteRequestDto(
    @Json(name = "requestedBy") val requestedBy: String,
    @Json(name = "maxUses") val maxUses: Int? = null,
    @Json(name = "ttlSeconds") val ttlSeconds: Int? = null
)

data class CreateInviteResponseDto(
    @Json(name = "invite") val invite: RoomInviteDto
)
