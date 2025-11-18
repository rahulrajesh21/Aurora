package com.example.music_room.data.remote.model

import com.squareup.moshi.Json

data class SearchRequestDto(
    @Json(name = "query") val query: String
)

data class SearchResponseDto(
    @Json(name = "tracks") val tracks: List<TrackDto>,
    @Json(name = "query") val query: String,
    @Json(name = "providers") val providers: List<String>
)
