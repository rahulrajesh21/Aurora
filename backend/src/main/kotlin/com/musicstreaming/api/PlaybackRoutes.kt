package com.musicstreaming.api

import com.musicstreaming.di.ServiceContainer
import com.musicstreaming.models.ProviderType
import com.musicstreaming.models.StreamingError
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("PlaybackRoutes")

@Serializable
data class PlayRequest(
    val trackId: String,
    val provider: ProviderType
)

@Serializable
data class SeekRequest(
    val positionSeconds: Long? = null,
    val percentage: Double? = null
)

/**
 * Configure playback control routes
 * Requirement 2.1: Start playback from search results
 * Requirement 4.1: Provide play, pause, skip operations
 * Requirement 4.2: Halt stream and preserve position
 * Requirement 4.3: Resume from preserved position
 */
fun Route.playbackRoutes() {
    val streamingService = ServiceContainer.getInstance().streamingService
    
    // POST /api/playback/play - Start playback (with optional track) or resume from queue
    post("/api/playback/play") {
        try {
            // Try to receive PlayRequest, but if body is empty, resume from queue
            val request = try {
                call.receiveNullable<PlayRequest>()
            } catch (e: Exception) {
                null
            }
            
            val result = if (request != null && request.trackId.isNotBlank()) {
                // Play specific track
                logger.info("Play request received: trackId=${request.trackId}, provider=${request.provider}")
                streamingService.play(request.trackId, request.provider)
            } else {
                // Resume from queue
                logger.info("Resume playback from queue")
                streamingService.resume()
            }
            
            if (result.isSuccess) {
                val playbackState = result.getOrThrow()
                logger.info("Playback started successfully")
                call.respond(HttpStatusCode.OK, playbackState)
            } else {
                val error = result.exceptionOrNull()
                // For expected queue-empty cases, return a clean 503 instead of 500
                when (error) {
                    is StreamingError.QueueError -> {
                        logger.warn("Play failed due to empty queue: ${error.message}")
                        call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            ErrorResponse("QUEUE_EMPTY", error.message)
                        )
                    }
                    is StreamingError.TrackNotFoundError -> {
                        logger.error("Play failed: ${error.message}", error)
                        call.respond(
                            HttpStatusCode.NotFound,
                            ErrorResponse("TRACK_NOT_FOUND", error.message)
                        )
                    }
                    is StreamingError.ProviderError -> {
                        logger.error("Play failed: ${error.message}", error)
                        call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            ErrorResponse("PROVIDER_ERROR", error.message)
                        )
                    }
                    is StreamingError.NetworkError -> {
                        logger.error("Play failed: ${error.message}", error)
                        call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            ErrorResponse("NETWORK_ERROR", error.message ?: "Network error occurred")
                        )
                    }
                    else -> {
                        logger.error("Play failed: ${error?.message}", error)
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            ErrorResponse("PLAYBACK_FAILED", error?.message ?: "Playback failed")
                        )
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Unexpected error in play endpoint", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("INTERNAL_ERROR", "An unexpected error occurred")
            )
        }
    }
    
    // POST /api/playback/pause - Pause playback
    post("/api/playback/pause") {
        try {
            logger.info("Pause request received")
            
            val result = streamingService.pause()
            
            if (result.isSuccess) {
                val playbackState = result.getOrThrow()
                logger.info("Playback paused successfully")
                call.respond(HttpStatusCode.OK, playbackState)
            } else {
                val error = result.exceptionOrNull()
                logger.error("Pause failed: ${error?.message}", error)
                
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse("PAUSE_FAILED", error?.message ?: "Pause failed")
                )
            }
        } catch (e: Exception) {
            logger.error("Unexpected error in pause endpoint", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("INTERNAL_ERROR", "An unexpected error occurred")
            )
        }
    }
    
    // POST /api/playback/resume - Resume playback
    post("/api/playback/resume") {
        try {
            logger.info("Resume request received")
            
            val result = streamingService.resume()
            
            if (result.isSuccess) {
                val playbackState = result.getOrThrow()
                logger.info("Playback resumed successfully")
                call.respond(HttpStatusCode.OK, playbackState)
            } else {
                val error = result.exceptionOrNull()
                logger.error("Resume failed: ${error?.message}", error)
                
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse("RESUME_FAILED", error?.message ?: "Resume failed")
                )
            }
        } catch (e: Exception) {
            logger.error("Unexpected error in resume endpoint", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("INTERNAL_ERROR", "An unexpected error occurred")
            )
        }
    }
    
    // POST /api/playback/skip - Skip to next track (legacy)
    post("/api/playback/skip") {
        try {
            logger.info("Skip request received")
            
            val result = streamingService.skip()
            
            if (result.isSuccess) {
                val playbackState = result.getOrThrow()
                logger.info("Skipped to next track successfully")
                call.respond(HttpStatusCode.OK, playbackState)
            } else {
                val error = result.exceptionOrNull()
                logger.error("Skip failed: ${error?.message}", error)
                
                when (error) {
                    is StreamingError.QueueError -> {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("QUEUE_ERROR", error.message)
                        )
                    }
                    else -> {
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            ErrorResponse("SKIP_FAILED", error?.message ?: "Skip failed")
                        )
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Unexpected error in skip endpoint", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("INTERNAL_ERROR", "An unexpected error occurred")
            )
        }
    }
    
    // GET /api/playback/state - Get current playback state
    get("/api/playback/state") {
        try {
            logger.info("Get state request received")
            
            val playbackState = streamingService.getState()
            logger.info("State retrieved successfully")
            call.respond(HttpStatusCode.OK, playbackState)
        } catch (e: Exception) {
            logger.error("Unexpected error in get state endpoint", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("INTERNAL_ERROR", "An unexpected error occurred")
            )
        }
    }
    
    // GET /api/playback/stream/{trackId} - Stream audio for testing
    get("/api/playback/stream/{trackId}") {
        try {
            val trackId = call.parameters["trackId"]
            val provider = call.request.queryParameters["provider"]?.let { 
                try { ProviderType.valueOf(it) } catch (e: Exception) { ProviderType.YOUTUBE }
            } ?: ProviderType.YOUTUBE
            
            if (trackId.isNullOrBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("INVALID_REQUEST", "Track ID is required")
                )
                return@get
            }
            
            logger.info("Stream request received: trackId=$trackId, provider=$provider")
            
            // Get stream URL using yt-dlp
            val processBuilder = ProcessBuilder(
                "yt-dlp",
                "-f", "bestaudio",
                "--get-url",
                "--no-playlist",
                "https://www.youtube.com/watch?v=$trackId"
            )
            
            val process = processBuilder.start()
            val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
            val streamUrl = reader.readLine()
            val exitCode = process.waitFor()
            
            if (exitCode != 0 || streamUrl.isNullOrBlank()) {
                logger.error("Failed to get stream URL for track: $trackId")
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ErrorResponse("STREAM_ERROR", "Failed to get stream URL")
                )
                return@get
            }
            
            logger.info("Redirecting to stream URL")
            // Redirect to the actual stream URL
            call.respondRedirect(streamUrl)
            
        } catch (e: Exception) {
            logger.error("Unexpected error in stream endpoint", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("INTERNAL_ERROR", "An unexpected error occurred")
            )
        }
    }
    
    // POST /api/playback/seek - Seek to position
    // Requirement 9.1: Accept position in seconds or percentage
    // Requirement 9.2: Validate target position
    // Requirement 9.3: Update stream handler within 1 second
    post("/api/playback/seek") {
        try {
            val request = call.receive<SeekRequest>()
            
            // Validate that either positionSeconds or percentage is provided
            if (request.positionSeconds == null && request.percentage == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("INVALID_REQUEST", "Either positionSeconds or percentage must be provided")
                )
                return@post
            }
            
            if (request.positionSeconds != null && request.percentage != null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("INVALID_REQUEST", "Only one of positionSeconds or percentage should be provided")
                )
                return@post
            }
            
            logger.info("Seek request received: positionSeconds=${request.positionSeconds}, percentage=${request.percentage}")
            
            val result = if (request.positionSeconds != null) {
                if (request.positionSeconds < 0) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("INVALID_REQUEST", "Position must be non-negative")
                    )
                    return@post
                }
                streamingService.seek(request.positionSeconds)
            } else {
                val percentage = request.percentage!!
                if (percentage < 0.0 || percentage > 100.0) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("INVALID_REQUEST", "Percentage must be between 0 and 100")
                    )
                    return@post
                }
                streamingService.seekByPercentage(percentage)
            }
            
            if (result.isSuccess) {
                val playbackState = result.getOrThrow()
                logger.info("Seek successful")
                call.respond(HttpStatusCode.OK, playbackState)
            } else {
                val error = result.exceptionOrNull()
                logger.error("Seek failed: ${error?.message}", error)
                
                when (error) {
                    is StreamingError.InvalidSeekPosition -> {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("INVALID_SEEK_POSITION", error.message)
                        )
                    }
                    is StreamingError.NetworkError -> {
                        call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            ErrorResponse("NETWORK_ERROR", error.message ?: "Network error occurred")
                        )
                    }
                    else -> {
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            ErrorResponse("SEEK_FAILED", error?.message ?: "Seek failed")
                        )
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Unexpected error in seek endpoint", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("INTERNAL_ERROR", "An unexpected error occurred")
            )
        }
    }

    // POST /api/playback/next - Skip to next track (primary endpoint for clients)
    post("/api/playback/next") {
        try {
            logger.info("Next track request received")

            val result = streamingService.skip()

            if (result.isSuccess) {
                val playbackState = result.getOrThrow()
                logger.info("Moved to next track successfully")
                call.respond(HttpStatusCode.OK, playbackState)
            } else {
                val error = result.exceptionOrNull()
                logger.error("Next failed: ${error?.message}", error)

                when (error) {
                    is StreamingError.QueueError -> {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("QUEUE_ERROR", error.message)
                        )
                    }
                    else -> {
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            ErrorResponse("NEXT_FAILED", error?.message ?: "Next failed")
                        )
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Unexpected error in next endpoint", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("INTERNAL_ERROR", "An unexpected error occurred")
            )
        }
    }

    // POST /api/playback/previous - Previous track (not yet implemented)
    post("/api/playback/previous") {
        try {
            logger.info("Previous track request received")

            // Previous track history is not yet implemented on the backend.
            // Return a clear error so the client can show an appropriate message.
            call.respond(
                HttpStatusCode.NotImplemented,
                ErrorResponse("PREVIOUS_NOT_IMPLEMENTED", "Previous track is not yet supported")
            )
        } catch (e: Exception) {
            logger.error("Unexpected error in previous endpoint", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("INTERNAL_ERROR", "An unexpected error occurred")
            )
        }
    }
}
