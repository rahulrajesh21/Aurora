package com.musicstreaming.models

sealed class StreamingError(
    override val message: String,
    override val cause: Throwable? = null
) : Exception(message, cause) {
    
    data class ProviderError(
        val provider: ProviderType,
        val errorMessage: String,
        val errorCause: Throwable? = null
    ) : StreamingError("Provider error from ${provider.name}: $errorMessage", errorCause)
    
    data class AuthenticationError(
        val provider: ProviderType,
        val errorCause: Throwable? = null
    ) : StreamingError("Authentication failed for provider: ${provider.name}", errorCause)
    
    data class NetworkError(
        val errorMessage: String,
        val errorCause: Throwable? = null
    ) : StreamingError("Network error: $errorMessage", errorCause)
    
    data class TrackNotFoundError(
        val trackId: String,
        val provider: ProviderType? = null
    ) : StreamingError("Track not found: $trackId${provider?.let { " on $it" } ?: ""}")
    
    data class InvalidSeekPosition(
        val position: Long,
        val trackDuration: Long
    ) : StreamingError("Invalid seek position: $position (track duration: $trackDuration)")
    
    data class QueueError(
        val errorMessage: String
    ) : StreamingError("Queue error: $errorMessage")
    
    data class RateLimitError(
        val provider: ProviderType,
        val retryAfterSeconds: Long
    ) : StreamingError("Rate limit exceeded for ${provider.name}, retry after $retryAfterSeconds seconds")
}
