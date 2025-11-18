package com.musicstreaming.api

import com.musicstreaming.di.ServiceContainer
import com.musicstreaming.models.StreamingError
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("SearchRoutes")

@Serializable
data class SearchRequest(
    val query: String
)

@Serializable
data class ErrorResponse(
    val error: String,
    val message: String
)

/**
 * Configure search-related routes
 * Requirement 1.1: Query YouTube API
 * Requirement 1.2: Return unified list with track details
 */
fun Route.searchRoutes() {
    val streamingService = ServiceContainer.getInstance().streamingService
    
    post("/api/search") {
        try {
            val request = call.receive<SearchRequest>()
            
            if (request.query.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("INVALID_REQUEST", "Search query cannot be blank")
                )
                return@post
            }
            
            logger.info("Search request received: ${request.query}")
            
            val result = streamingService.search(request.query)
            
            if (result.isSuccess) {
                val searchResult = result.getOrThrow()
                logger.info("Search successful: found ${searchResult.tracks.size} tracks")
                call.respond(HttpStatusCode.OK, searchResult)
            } else {
                val error = result.exceptionOrNull()
                logger.error("Search failed: ${error?.message}", error)
                
                when (error) {
                    is StreamingError.NetworkError -> {
                        call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            ErrorResponse("NETWORK_ERROR", error.message ?: "Network error occurred")
                        )
                    }
                    is StreamingError.ProviderError -> {
                        call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            ErrorResponse("PROVIDER_ERROR", error.message)
                        )
                    }
                    else -> {
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            ErrorResponse("SEARCH_FAILED", error?.message ?: "Search failed")
                        )
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Unexpected error in search endpoint", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("INTERNAL_ERROR", "An unexpected error occurred")
            )
        }
    }
}
