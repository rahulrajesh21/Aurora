package com.musicstreaming.models

import kotlinx.serialization.Serializable

@Serializable
data class PlaybackState(
    val currentTrack: Track?,
    val positionSeconds: Long,
    val isPlaying: Boolean,
    val queue: List<Track>,
    val shuffleEnabled: Boolean,
    val timestamp: Long,
    /**
     * Direct audio stream URL for the currently playing track, if available.
     * This is typically extracted via yt-dlp by the provider (e.g. YouTube).
     * Clients should treat this as opaque and feed it directly to their audio player.
     */
    val streamUrl: String? = null,
    /**
     * Audio container/format for the current stream (e.g. WEBM, AAC).
     * Useful for clients that need to adjust playback configuration based on format.
     */
    val streamFormat: AudioFormat? = null
) {
    init {
        require(positionSeconds >= 0) { "Position must be non-negative, got: $positionSeconds" }
        if (currentTrack != null) {
            require(positionSeconds <= currentTrack.durationSeconds) {
                "Position ($positionSeconds) cannot exceed track duration (${currentTrack.durationSeconds})"
            }
        }
    }
}
