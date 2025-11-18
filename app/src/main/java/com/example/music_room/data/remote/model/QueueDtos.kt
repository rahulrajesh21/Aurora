package com.example.music_room.data.remote.model

import com.squareup.moshi.Json

data class QueueResponseDto(
    @Json(name = "queue") val queue: List<TrackDto>
)

data class AddToQueueRequestDto(
    @Json(name = "trackId") val trackId: String,
    @Json(name = "provider") val provider: String = "YOUTUBE",
    @Json(name = "addedBy") val addedBy: String? = null
)

data class ReorderQueueRequestDto(
    @Json(name = "fromPosition") val fromPosition: Int,
    @Json(name = "toPosition") val toPosition: Int
)

data class SeekRequestDto(
    @Json(name = "positionSeconds") val positionSeconds: Int? = null,
    @Json(name = "percentage") val percentage: Double? = null
)
