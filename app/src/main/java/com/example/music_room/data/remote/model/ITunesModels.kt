package com.example.music_room.data.remote.model

import com.squareup.moshi.Json

data class ITunesSearchResponse(
    @Json(name = "resultCount") val resultCount: Int,
    @Json(name = "results") val results: List<ITunesAlbumDto>
)

data class ITunesAlbumDto(
    @Json(name = "collectionName") val collectionName: String,
    @Json(name = "artistName") val artistName: String,
    @Json(name = "artworkUrl100") val artworkUrl100: String,
    @Json(name = "collectionId") val collectionId: Long,
    @Json(name = "primaryGenreName") val primaryGenreName: String?
)
