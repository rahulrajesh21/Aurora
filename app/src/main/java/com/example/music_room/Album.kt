package com.example.music_room

data class Album(
    val title: String,
    val artist: String,
    val imageResId: Int? = null,
    val trackId: String? = null,
    val provider: String? = null,
    val imageUrl: String? = null,
    val durationSeconds: Int? = null,
    val externalUrl: String? = null
)
