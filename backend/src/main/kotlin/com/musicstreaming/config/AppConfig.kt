package com.musicstreaming.config

import io.ktor.server.application.*

/**
 * Application configuration holder
 * Loads and validates all configuration from application.conf and environment variables
 */
data class AppConfig(
    val youtube: YouTubeConfig,
    val queue: QueueConfig,
    val playback: PlaybackConfig,
    val state: StateConfig,
    val websocket: WebSocketConfig
) {
    companion object {
        /**
         * Load configuration from application environment
         * Validates required settings and provides sensible defaults
         */
        fun load(environment: ApplicationEnvironment): AppConfig {
            val config = environment.config
            
            // Load YouTube configuration
            val youtubeApiKey = config.propertyOrNull("streaming.youtube.apiKey")?.getString()
                ?: System.getenv("YOUTUBE_API_KEY")
                ?: ""
            
            // Validate required YouTube API key
            if (youtubeApiKey.isEmpty()) {
                throw ConfigurationException(
                    "YouTube API key is required. " +
                    "Set it in application.conf (streaming.youtube.apiKey) " +
                    "or via environment variable YOUTUBE_API_KEY"
                )
            }
            
            val youtube = YouTubeConfig(
                apiKey = youtubeApiKey,
                searchTimeout = config.propertyOrNull("streaming.youtube.searchTimeout")?.getString()?.toLongOrNull() ?: 2000L,
                searchLimit = config.propertyOrNull("streaming.youtube.searchLimit")?.getString()?.toIntOrNull() ?: 20
            )
            
            // Load queue configuration with defaults
            val queue = QueueConfig(
                maxSize = config.propertyOrNull("streaming.queue.maxSize")?.getString()?.toIntOrNull() ?: 100,
                persistenceEnabled = config.propertyOrNull("streaming.queue.persistenceEnabled")?.getString()?.toBooleanStrictOrNull() ?: true,
                storagePath = config.propertyOrNull("streaming.queue.storagePath")?.getString() ?: "./data/queue.json"
            )
            
            // Load playback configuration with defaults
            val retryBackoffMs = config.propertyOrNull("streaming.playback.retryBackoffMs")?.getList()
                ?.mapNotNull { it.toLongOrNull() }
                ?: listOf(1000L, 2000L, 4000L)
            
            val playback = PlaybackConfig(
                seekTimeoutMs = config.propertyOrNull("streaming.playback.seekTimeoutMs")?.getString()?.toLongOrNull() ?: 1000L,
                retryAttempts = config.propertyOrNull("streaming.playback.retryAttempts")?.getString()?.toIntOrNull() ?: 3,
                retryBackoffMs = retryBackoffMs,
                startPlaybackTimeoutMs = config.propertyOrNull("streaming.playback.startPlaybackTimeoutMs")?.getString()?.toLongOrNull() ?: 2000L,
                transitionTimeoutMs = config.propertyOrNull("streaming.playback.transitionTimeoutMs")?.getString()?.toLongOrNull() ?: 2000L
            )
            
            // Load state configuration with defaults
            val state = StateConfig(
                persistenceEnabled = config.propertyOrNull("streaming.state.persistenceEnabled")?.getString()?.toBooleanStrictOrNull() ?: true,
                storagePath = config.propertyOrNull("streaming.state.storagePath")?.getString() ?: "./data/state.json"
            )
            
            // Load WebSocket configuration with defaults
            val websocket = WebSocketConfig(
                broadcastDelayMs = config.propertyOrNull("streaming.websocket.broadcastDelayMs")?.getString()?.toLongOrNull() ?: 500L
            )
            
            return AppConfig(
                youtube = youtube,
                queue = queue,
                playback = playback,
                state = state,
                websocket = websocket
            )
        }
    }
}

/**
 * YouTube API configuration
 */
data class YouTubeConfig(
    val apiKey: String,
    val searchTimeout: Long,
    val searchLimit: Int
)

/**
 * Queue management configuration
 */
data class QueueConfig(
    val maxSize: Int,
    val persistenceEnabled: Boolean,
    val storagePath: String
)

/**
 * Playback engine configuration
 */
data class PlaybackConfig(
    val seekTimeoutMs: Long,
    val retryAttempts: Int,
    val retryBackoffMs: List<Long>,
    val startPlaybackTimeoutMs: Long,
    val transitionTimeoutMs: Long
)

/**
 * State management configuration
 */
data class StateConfig(
    val persistenceEnabled: Boolean,
    val storagePath: String
)

/**
 * WebSocket configuration
 */
data class WebSocketConfig(
    val broadcastDelayMs: Long
)

/**
 * Exception thrown when configuration is invalid or missing required values
 */
class ConfigurationException(message: String) : Exception(message)
