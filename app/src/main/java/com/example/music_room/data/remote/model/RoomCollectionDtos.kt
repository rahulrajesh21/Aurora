package com.example.music_room.data.remote.model

import com.squareup.moshi.Json

data class RoomMembersResponseDto(
    @Json(name = "members") val members: List<RoomMemberDto>
)

data class RoomInvitesResponseDto(
    @Json(name = "invites") val invites: List<RoomInviteDto>
)
