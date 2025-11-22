package com.example.music_room.data.remote.model

import com.squareup.moshi.Json

data class PopularAlbumsResponseDto(
    @Json(name = "fetchedAt") val fetchedAt: String?,
    @Json(name = "albums") val albums: List<PopularAlbumDto> = emptyList()
)

data class PopularAlbumDto(
    @Json(name = "id") val id: String,
    @Json(name = "title") val title: String,
    @Json(name = "artist") val artist: String,
    @Json(name = "imageUrl") val imageUrl: String?,
    @Json(name = "externalUrl") val externalUrl: String?
)
