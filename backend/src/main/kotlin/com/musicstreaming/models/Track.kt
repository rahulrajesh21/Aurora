package com.musicstreaming.models

import kotlinx.serialization.Serializable

@Serializable
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val durationSeconds: Long,
    val provider: ProviderType,
    val thumbnailUrl: String? = null,
    val externalUrl: String? = null
) {
    init {
        require(id.isNotBlank()) { "Track id cannot be blank" }
        require(title.isNotBlank()) { "Track title cannot be blank" }
        require(artist.isNotBlank()) { "Track artist cannot be blank" }
        require(durationSeconds >= 0) { "Track duration must be non-negative, got: $durationSeconds" }
    }
}
