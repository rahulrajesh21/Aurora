package com.musicstreaming.api

import com.musicstreaming.di.ServiceContainer
import com.musicstreaming.models.StreamingError
import com.musicstreaming.models.Track
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("QueueRoutes")

@Serializable
data class AddToQueueRequest(
    val trackId: String,
    val provider: String = "YOUTUBE",
    val addedBy: String = "user"
)

@Serializable
data class ReorderQueueRequest(
    val fromPosition: Int,
    val toPosition: Int
)

@Serializable
data class QueueResponse(
    val queue: List<Track>
)

/**
 * Configure queue management routes
 * Requirement 3.2: Add tracks to queue
 * Requirement 7.1: Remove tracks from queue
 * Requirement 7.2: Reorder tracks in queue
 * Requirement 7.3: Clear queue
 * Requirement 8.1: Shuffle queue
 */
fun Route.queueRoutes() {
    val streamingService = ServiceContainer.getInstance().streamingService
    
    // POST /api/queue/add - Add track to queue
    post("/api/queue/add") {
        try {
            val request = call.receive<AddToQueueRequest>()
            
            logger.info("Add to queue request received: trackId=${request.trackId}, provider=${request.provider}")
            
            // Convert provider string to ProviderType enum
            val providerType = try {
                com.musicstreaming.models.ProviderType.valueOf(request.provider)
            } catch (e: IllegalArgumentException) {
                logger.error("Invalid provider: ${request.provider}")
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("INVALID_PROVIDER", "Provider '${request.provider}' is not supported")
                )
                return@post
            }
            
            val result = streamingService.addToQueue(request.trackId, providerType)
            
            if (result.isSuccess) {
                logger.info("Track added to queue successfully")
                val queue = streamingService.getQueue()
                call.respond(HttpStatusCode.OK, QueueResponse(queue))
            } else {
                val error = result.exceptionOrNull()
                logger.error("Add to queue failed: ${error?.message}", error)
                
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
                            ErrorResponse("ADD_FAILED", error?.message ?: "Failed to add track to queue")
                        )
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Unexpected error in add to queue endpoint", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("INTERNAL_ERROR", "An unexpected error occurred")
            )
        }
    }
    
    // DELETE /api/queue/{position} - Remove track from queue
    delete("/api/queue/{position}") {
        try {
            val position = call.parameters["position"]?.toIntOrNull()
            
            if (position == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("INVALID_REQUEST", "Invalid position parameter")
                )
                return@delete
            }
            
            logger.info("Remove from queue request received: position=$position")
            
            val result = streamingService.removeFromQueue(position)
            
            if (result.isSuccess) {
                logger.info("Track removed from queue successfully")
                val queue = streamingService.getQueue()
                call.respond(HttpStatusCode.OK, QueueResponse(queue))
            } else {
                val error = result.exceptionOrNull()
                logger.error("Remove from queue failed: ${error?.message}", error)
                
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
                            ErrorResponse("REMOVE_FAILED", error?.message ?: "Failed to remove track from queue")
                        )
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Unexpected error in remove from queue endpoint", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("INTERNAL_ERROR", "An unexpected error occurred")
            )
        }
    }
    
    // PUT /api/queue/reorder - Reorder tracks in queue
    put("/api/queue/reorder") {
        try {
            val request = call.receive<ReorderQueueRequest>()
            
            logger.info("Reorder queue request received: from=${request.fromPosition}, to=${request.toPosition}")
            
            val result = streamingService.reorderQueue(request.fromPosition, request.toPosition)
            
            if (result.isSuccess) {
                logger.info("Queue reordered successfully")
                val queue = streamingService.getQueue()
                call.respond(HttpStatusCode.OK, QueueResponse(queue))
            } else {
                val error = result.exceptionOrNull()
                logger.error("Reorder queue failed: ${error?.message}", error)
                
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
                            ErrorResponse("REORDER_FAILED", error?.message ?: "Failed to reorder queue")
                        )
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Unexpected error in reorder queue endpoint", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("INTERNAL_ERROR", "An unexpected error occurred")
            )
        }
    }
    
    // DELETE /api/queue - Clear queue
    delete("/api/queue") {
        try {
            logger.info("Clear queue request received")
            
            val result = streamingService.clearQueue()
            
            if (result.isSuccess) {
                logger.info("Queue cleared successfully")
                call.respond(HttpStatusCode.OK, QueueResponse(emptyList()))
            } else {
                val error = result.exceptionOrNull()
                logger.error("Clear queue failed: ${error?.message}", error)
                
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse("CLEAR_FAILED", error?.message ?: "Failed to clear queue")
                )
            }
        } catch (e: Exception) {
            logger.error("Unexpected error in clear queue endpoint", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("INTERNAL_ERROR", "An unexpected error occurred")
            )
        }
    }
    
    // POST /api/queue/shuffle - Shuffle queue
    post("/api/queue/shuffle") {
        try {
            logger.info("Shuffle queue request received")
            
            val result = streamingService.shuffleQueue()
            
            if (result.isSuccess) {
                logger.info("Queue shuffled successfully")
                val queue = streamingService.getQueue()
                call.respond(HttpStatusCode.OK, QueueResponse(queue))
            } else {
                val error = result.exceptionOrNull()
                logger.error("Shuffle queue failed: ${error?.message}", error)
                
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
                            ErrorResponse("SHUFFLE_FAILED", error?.message ?: "Failed to shuffle queue")
                        )
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Unexpected error in shuffle queue endpoint", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("INTERNAL_ERROR", "An unexpected error occurred")
            )
        }
    }
    
    // GET /api/queue - Get current queue
    get("/api/queue") {
        try {
            logger.info("Get queue request received")
            
            val queue = streamingService.getQueue()
            logger.info("Queue retrieved successfully: ${queue.size} tracks")
            call.respond(HttpStatusCode.OK, QueueResponse(queue))
        } catch (e: Exception) {
            logger.error("Unexpected error in get queue endpoint", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("INTERNAL_ERROR", "An unexpected error occurred")
            )
        }
    }
}
