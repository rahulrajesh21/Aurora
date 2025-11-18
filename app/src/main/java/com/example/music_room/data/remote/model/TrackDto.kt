package com.example.music_room.data.remote.model

import com.squareup.moshi.Json

data class TrackDto(
    @Json(name = "id") val id: String = "",
    @Json(name = "title") val title: String = "",
    @Json(name = "artist") val artist: String = "",
    @Json(name = "durationSeconds") val durationSeconds: Int = 0,
    @Json(name = "provider") val provider: String = "YOUTUBE",
    @Json(name = "thumbnailUrl") val thumbnailUrl: String? = null,
    @Json(name = "externalUrl") val externalUrl: String? = null
)
