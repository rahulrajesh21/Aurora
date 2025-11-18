package com.musicstreaming.models

import kotlinx.serialization.Serializable

@Serializable
data class StreamInfo(
    val streamUrl: String,
    val track: Track,
    val expiresAt: Long? = null,
    val format: AudioFormat
) {
    init {
        require(streamUrl.isNotBlank()) { "Stream URL cannot be blank" }
    }
}

@Serializable
enum class AudioFormat {
    MP3,
    AAC,
    WEBM,
    OGG
}
