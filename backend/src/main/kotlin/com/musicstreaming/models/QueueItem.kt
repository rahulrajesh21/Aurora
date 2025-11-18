package com.musicstreaming.models

import kotlinx.serialization.Serializable

@Serializable
data class QueueItem(
    val id: String,
    val track: Track,
    val addedBy: String,
    val addedAt: Long,
    val position: Int
)
