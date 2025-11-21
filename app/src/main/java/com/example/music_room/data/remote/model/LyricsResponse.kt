package com.example.music_room.data.remote.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class LyricsResponse(
    @Json(name = "id") val id: Int,
    @Json(name = "plainLyrics") val plainLyrics: String?,
    @Json(name = "syncedLyrics") val syncedLyrics: String?,
    @Json(name = "instrumental") val instrumental: Boolean,
    @Json(name = "duration") val duration: Double?
)
