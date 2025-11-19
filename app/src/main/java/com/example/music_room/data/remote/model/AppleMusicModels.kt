package com.example.music_room.data.remote.model

import com.squareup.moshi.Json

data class AppleMusicFeedResponse(
    @Json(name = "feed") val feed: Feed
)

data class Feed(
    @Json(name = "results") val results: List<AlbumResult>? = emptyList()
)

data class AlbumResult(
    @Json(name = "id") val id: String?,
    @Json(name = "name") val name: String?,
    @Json(name = "artistName") val artistName: String?,
    @Json(name = "artworkUrl100") val artworkUrl100: String?,
    @Json(name = "url") val url: String?
)
