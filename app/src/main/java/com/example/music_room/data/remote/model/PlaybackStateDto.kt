package com.example.music_room.data.remote.model

import com.squareup.moshi.Json

 data class PlaybackStateDto(
    @Json(name = "currentTrack") val currentTrack: TrackDto?,
    @Json(name = "positionSeconds") val positionSeconds: Int,
    @Json(name = "isPlaying") val isPlaying: Boolean,
    @Json(name = "queue") val queue: List<TrackDto>,
    @Json(name = "shuffleEnabled") val shuffleEnabled: Boolean,
    @Json(name = "timestamp") val timestamp: Long,
    @Json(name = "streamUrl") val streamUrl: String? = null,
    @Json(name = "streamFormat") val streamFormat: String? = null
)
