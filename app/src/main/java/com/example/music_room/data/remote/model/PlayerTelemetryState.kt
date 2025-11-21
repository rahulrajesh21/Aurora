package com.example.music_room.data.remote.model

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class PlayerTelemetryState(
    val currentTime: Long,
    val trackId: String,
    val song: String,
    val artist: String,
    val duration: Long,
    val isPlaying: Boolean,
    val isBuffering: Boolean,
    val browserTime: Long
)

@JsonClass(generateAdapter = true)
data class PlayerTickMessage(
    val type: String = "player_tick",
    val payload: PlayerTelemetryState
)

@JsonClass(generateAdapter = true)
data class SocketEventMessage(
    val type: String,
    val payload: Any?
)
