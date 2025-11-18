package com.musicstreaming.adapters

import com.musicstreaming.models.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.pow

/**
 * YouTube Music Provider implementation using YouTube Data API v3
 * Handles authentication, search, and stream URL extraction
 */
class YouTubeMusicProvider(
    private val apiKey: String
) : MusicProvider {
    
    private val logger = LoggerFactory.getLogger(YouTubeMusicProvider::class.java)
    
    override val providerType: ProviderType = ProviderType.YOUTUBE
    
    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 10000 // 10 second timeout
            connectTimeoutMillis = 10000
            socketTimeoutMillis = 10000
        }
    }
    
    private val baseUrl = "https://www.googleapis.com/youtube/v3"
    
    companion object {
        private const val MAX_RETRIES = 3
        private const val INITIAL_BACKOFF_MS = 1000L
    }

    /**
     * Check if the provider is available by validating the API key
     */
    override suspend fun isAvailable(): Boolean {
        return try {
            apiKey.isNotBlank()
        } catch (e: Exception) {
            logger.error("Failed to check YouTube provider availability", e)
            false
        }
    }
    
    /**
     * Search for tracks on YouTube
     * Filters results to music/audio content only
     */
    override suspend fun search(query: String, limit: Int): List<Track> {
        if (query.isBlank()) {
            return emptyList()
        }
        
        return withRetry("search") {
            try {
                val response = httpClient.get("$baseUrl/search") {
                    parameter("part", "snippet")
                    parameter("q", query)
                    parameter("type", "video")
                    parameter("videoCategoryId", "10") // Music category
                    parameter("maxResults", limit.coerceAtMost(20))
                    parameter("key", apiKey)
                }
                
                when (response.status) {
                    HttpStatusCode.OK -> {
                        val searchResponse = response.body<YouTubeSearchResponse>()
                        searchResponse.items.mapNotNull { item ->
                            convertToTrack(item)
                        }
                    }
                    HttpStatusCode.Forbidden -> {
                        val responseBody = response.body<String>()
                        logger.error("YouTube API returned 403 Forbidden: $responseBody")
                        if (responseBody.contains("quotaExceeded")) {
                            logger.error("YouTube API quota exceeded")
                            throw StreamingError.RateLimitError(ProviderType.YOUTUBE, 86400) // 24 hours
                        }
                        throw StreamingError.AuthenticationError(ProviderType.YOUTUBE)
                    }
                    HttpStatusCode.BadRequest -> {
                        val responseBody = response.body<String>()
                        logger.error("YouTube API returned 400 Bad Request: $responseBody")
                        throw StreamingError.ProviderError(
                            ProviderType.YOUTUBE,
                            "Bad request to YouTube API: $responseBody"
                        )
                    }
                    else -> {
                        val responseBody = try { response.body<String>() } catch (e: Exception) { "Unable to read response" }
                        logger.error("YouTube API returned ${response.status}: $responseBody")
                        throw StreamingError.ProviderError(
                            ProviderType.YOUTUBE,
                            "Search failed with status: ${response.status}"
                        )
                    }
                }
            } catch (e: StreamingError) {
                throw e
            } catch (e: HttpRequestTimeoutException) {
                logger.warn("YouTube search timeout for query: $query")
                emptyList()
            } catch (e: Exception) {
                logger.error("YouTube search error for query: $query", e)
                throw StreamingError.NetworkError("Search failed: ${e.message}", e)
            }
        }
    }
    
    /**
     * Get detailed track information
     */
    override suspend fun getTrack(trackId: String): Track? {
        return withRetry("getTrack") {
            try {
                val response = httpClient.get("$baseUrl/videos") {
                    parameter("part", "snippet,contentDetails")
                    parameter("id", trackId)
                    parameter("key", apiKey)
                }
                
                when (response.status) {
                    HttpStatusCode.OK -> {
                        val videoResponse = response.body<YouTubeVideoResponse>()
                        videoResponse.items.firstOrNull()?.let { item ->
                            convertVideoToTrack(item)
                        }
                    }
                    else -> null
                }
            } catch (e: Exception) {
                logger.error("Failed to get track: $trackId", e)
                null
            }
        }
    }

    /**
     * Get stream URL for a track using yt-dlp
     */
    override suspend fun getStreamUrl(trackId: String): StreamInfo {
        return withRetry("getStreamUrl") {
            try {
                val track = getTrack(trackId)
                    ?: throw StreamingError.TrackNotFoundError(trackId, ProviderType.YOUTUBE)
                
                val videoUrl = "https://www.youtube.com/watch?v=$trackId"
                
                // Use yt-dlp to extract audio stream URL
                val processBuilder = ProcessBuilder(
                    "yt-dlp",
                    "-f", "bestaudio",
                    "--get-url",
                    "--no-playlist",
                    videoUrl
                )
                
                val process = processBuilder.start()
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val streamUrl = reader.readLine()
                val exitCode = process.waitFor()
                
                if (exitCode != 0 || streamUrl.isNullOrBlank()) {
                    val errorReader = BufferedReader(InputStreamReader(process.errorStream))
                    val errorMessage = errorReader.readText()
                    logger.error("yt-dlp failed for track $trackId: $errorMessage")
                    throw StreamingError.ProviderError(
                        ProviderType.YOUTUBE,
                        "Failed to extract stream URL: $errorMessage"
                    )
                }
                
                StreamInfo(
                    streamUrl = streamUrl,
                    track = track,
                    expiresAt = System.currentTimeMillis() + (6 * 60 * 60 * 1000), // 6 hours
                    format = AudioFormat.WEBM
                )
            } catch (e: StreamingError) {
                throw e
            } catch (e: Exception) {
                logger.error("Failed to get stream URL for track: $trackId", e)
                throw StreamingError.ProviderError(
                    ProviderType.YOUTUBE,
                    "Stream extraction failed: ${e.message}",
                    e
                )
            }
        }
    }
    
    /**
     * Convert YouTube search item to Track model
     */
    private fun convertToTrack(item: YouTubeSearchItem): Track? {
        return try {
            Track(
                id = item.id.videoId,
                title = item.snippet.title,
                artist = item.snippet.channelTitle,
                durationSeconds = 0, // Duration not available in search results
                provider = ProviderType.YOUTUBE,
                thumbnailUrl = item.snippet.thumbnails.default.url,
                externalUrl = "https://www.youtube.com/watch?v=${item.id.videoId}"
            )
        } catch (e: Exception) {
            logger.warn("Failed to convert YouTube item to track: ${item.id.videoId}", e)
            null
        }
    }
    
    /**
     * Convert YouTube video item to Track model with duration
     */
    private fun convertVideoToTrack(item: YouTubeVideoItem): Track {
        val durationSeconds = parseDuration(item.contentDetails.duration)
        return Track(
            id = item.id,
            title = item.snippet.title,
            artist = item.snippet.channelTitle,
            durationSeconds = durationSeconds,
            provider = ProviderType.YOUTUBE,
            thumbnailUrl = item.snippet.thumbnails.default.url,
            externalUrl = "https://www.youtube.com/watch?v=${item.id}"
        )
    }
    
    /**
     * Parse ISO 8601 duration format (PT#M#S) to seconds
     */
    private fun parseDuration(duration: String): Long {
        val regex = Regex("PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?")
        val match = regex.find(duration) ?: return 0
        
        val hours = match.groupValues[1].toLongOrNull() ?: 0
        val minutes = match.groupValues[2].toLongOrNull() ?: 0
        val seconds = match.groupValues[3].toLongOrNull() ?: 0
        
        return hours * 3600 + minutes * 60 + seconds
    }

    /**
     * Retry logic with exponential backoff for network errors
     */
    private suspend fun <T> withRetry(operation: String, block: suspend () -> T): T {
        var lastException: Exception? = null
        
        repeat(MAX_RETRIES) { attempt ->
            try {
                return block()
            } catch (e: StreamingError.RateLimitError) {
                // Don't retry rate limit errors
                throw e
            } catch (e: StreamingError.AuthenticationError) {
                // Don't retry authentication errors
                throw e
            } catch (e: StreamingError) {
                // Retry other streaming errors
                lastException = e
                if (attempt < MAX_RETRIES - 1) {
                    val backoffMs = INITIAL_BACKOFF_MS * 2.0.pow(attempt).toLong()
                    logger.warn("Retry attempt ${attempt + 1} for $operation after ${backoffMs}ms", e)
                    delay(backoffMs)
                }
            } catch (e: Exception) {
                lastException = e
                if (attempt < MAX_RETRIES - 1) {
                    val backoffMs = INITIAL_BACKOFF_MS * 2.0.pow(attempt).toLong()
                    logger.warn("Retry attempt ${attempt + 1} for $operation after ${backoffMs}ms", e)
                    delay(backoffMs)
                }
            }
        }
        
        logger.error("Operation $operation failed after $MAX_RETRIES attempts")
        throw lastException ?: StreamingError.NetworkError("Operation failed after retries")
    }
    
    // YouTube API Response Models
    
    @Serializable
    data class YouTubeSearchResponse(
        val items: List<YouTubeSearchItem>
    )
    
    @Serializable
    data class YouTubeSearchItem(
        val id: VideoId,
        val snippet: Snippet
    )
    
    @Serializable
    data class VideoId(
        val videoId: String
    )
    
    @Serializable
    data class YouTubeVideoResponse(
        val items: List<YouTubeVideoItem>
    )
    
    @Serializable
    data class YouTubeVideoItem(
        val id: String,
        val snippet: Snippet,
        val contentDetails: ContentDetails
    )
    
    @Serializable
    data class Snippet(
        val title: String,
        val channelTitle: String,
        val thumbnails: Thumbnails
    )
    
    @Serializable
    data class Thumbnails(
        val default: Thumbnail
    )
    
    @Serializable
    data class Thumbnail(
        val url: String
    )
    
    @Serializable
    data class ContentDetails(
        val duration: String
    )
}
