package com.example.music_room.data.remote.model

import com.squareup.moshi.Json

data class PlayRequestDto(
    @Json(name = "trackId") val trackId: String,
    @Json(name = "provider") val provider: String = "YOUTUBE"
)

data class PauseRequestDto(
    @Json(name = "positionSeconds") val positionSeconds: Double?
)
