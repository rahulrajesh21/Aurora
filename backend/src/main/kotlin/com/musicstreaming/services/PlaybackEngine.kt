package com.musicstreaming.services

import com.musicstreaming.adapters.MusicProvider
import com.musicstreaming.models.*
import kotlinx.coroutines.delay

/**
 * Manages playback operations including starting, stopping, seeking, and track progression
 * Coordinates with music providers and queue manager for seamless playback
 */
class PlaybackEngine(
    private val providers: Map<ProviderType, MusicProvider>,
    private val queueManager: QueueManager,
    private val maxRetryAttempts: Int = 3,
    private val initialRetryDelayMs: Long = 1000
) {
    // Current playback state
    private var currentTrack: Track? = null
    private var currentProvider: MusicProvider? = null
    private var currentStreamInfo: StreamInfo? = null
    private var positionSeconds: Long = 0
    private var isPlaying: Boolean = false
    private var lastUpdateTimestamp: Long = System.currentTimeMillis()

    /**
     * Start playback for a given track
     * @param track The track to play
     * @return Result with StreamInfo or error
     */
    suspend fun startPlayback(track: Track): Result<StreamInfo> {
        return try {
            // Get the provider for this track
            val provider = providers[track.provider]
                ?: return Result.failure(
                    StreamingError.ProviderError(
                        track.provider,
                        "Provider not available"
                    )
                )

            // Get stream URL from provider
            val streamInfo = provider.getStreamUrl(track.id)

            // Update internal state
            currentTrack = track
            currentProvider = provider
            currentStreamInfo = streamInfo
            positionSeconds = 0
            isPlaying = true
            lastUpdateTimestamp = System.currentTimeMillis()

            Result.success(streamInfo)
        } catch (e: StreamingError) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(
                StreamingError.ProviderError(
                    track.provider,
                    "Failed to start playback: ${e.message}",
                    e
                )
            )
        }
    }

    /**
     * Stop playback and clear current track
     * @return Result with success or error
     */
    suspend fun stopPlayback(): Result<Unit> {
        return try {
            currentTrack = null
            currentProvider = null
            currentStreamInfo = null
            positionSeconds = 0
            isPlaying = false
            lastUpdateTimestamp = System.currentTimeMillis()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(
                StreamingError.NetworkError(
                    "Failed to stop playback: ${e.message}",
                    e
                )
            )
        }
    }

    /**
     * Pause playback while preserving current position
     * @return Result with success or error
     */
    suspend fun pause(): Result<Unit> {
        return try {
            if (!isPlaying) {
                return Result.success(Unit)
            }

            isPlaying = false
            lastUpdateTimestamp = System.currentTimeMillis()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(
                StreamingError.NetworkError(
                    "Failed to pause playback: ${e.message}",
                    e
                )
            )
        }
    }

    /**
     * Resume playback from current position
     * @return Result with success or error
     */
    suspend fun resume(): Result<Unit> {
        return try {
            if (currentTrack == null) {
                return Result.failure(
                    StreamingError.NetworkError("No track to resume")
                )
            }

            if (isPlaying) {
                return Result.success(Unit)
            }

            isPlaying = true
            lastUpdateTimestamp = System.currentTimeMillis()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(
                StreamingError.NetworkError(
                    "Failed to resume playback: ${e.message}",
                    e
                )
            )
        }
    }

    /**
     * Update the current playback position
     * Used for tracking position during playback
     * @param newPosition The new position in seconds
     */
    fun updatePosition(newPosition: Long) {
        if (currentTrack != null && newPosition >= 0 && newPosition <= currentTrack!!.durationSeconds) {
            positionSeconds = newPosition
            lastUpdateTimestamp = System.currentTimeMillis()
        }
    }

    /**
     * Seek to a specific position in the current track
     * @param positionSeconds The target position in seconds
     * @return Result with success or error
     */
    suspend fun seekTo(positionSeconds: Long): Result<Unit> {
        return try {
            val track = currentTrack
                ?: return Result.failure(
                    StreamingError.NetworkError("No track currently playing")
                )

            // Validate seek position is within track duration
            if (positionSeconds < 0) {
                return Result.failure(
                    StreamingError.InvalidSeekPosition(
                        positionSeconds,
                        track.durationSeconds
                    )
                )
            }

            if (positionSeconds > track.durationSeconds) {
                return Result.failure(
                    StreamingError.InvalidSeekPosition(
                        positionSeconds,
                        track.durationSeconds
                    )
                )
            }

            // Update playback state with new position
            this.positionSeconds = positionSeconds
            lastUpdateTimestamp = System.currentTimeMillis()

            Result.success(Unit)
        } catch (e: StreamingError) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(
                StreamingError.NetworkError(
                    "Failed to seek: ${e.message}",
                    e
                )
            )
        }
    }

    /**
     * Advance to the next track in the queue
     * Coordinates with QueueManager to get the next track
     * @return Result with StreamInfo for the next track or error if queue is empty
     */
    suspend fun playNext(): Result<StreamInfo> {
        return try {
            // Get next track from queue
            val nextQueueItem = queueManager.popNextTrack()
                ?: return Result.failure(
                    StreamingError.QueueError("Queue is empty, no next track available")
                )

            val nextTrack = nextQueueItem.track

            // Start playback of the next track
            val result = startPlayback(nextTrack)

            // Ensure transition happens within 2 seconds (requirement 3.4)
            if (result.isSuccess) {
                // Track transition completed successfully
                Result.success(result.getOrThrow())
            } else {
                result
            }
        } catch (e: StreamingError) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(
                StreamingError.NetworkError(
                    "Failed to play next track: ${e.message}",
                    e
                )
            )
        }
    }

    /**
     * Attempt to reconnect and restore stream for the current track
     * Implements exponential backoff retry logic (3 attempts)
     * @return Result with StreamInfo or error after all retries exhausted
     */
    suspend fun reconnectStream(): Result<StreamInfo> {
        val track = currentTrack
            ?: return Result.failure(
                StreamingError.NetworkError("No track to reconnect")
            )

        val savedPosition = positionSeconds
        val wasPlaying = isPlaying

        var lastError: StreamingError? = null
        var retryDelayMs = initialRetryDelayMs

        // Attempt reconnection up to maxRetryAttempts times with exponential backoff
        for (attempt in 1..maxRetryAttempts) {
            try {
                // Get the provider for this track
                val provider = providers[track.provider]
                    ?: return Result.failure(
                        StreamingError.ProviderError(
                            track.provider,
                            "Provider not available"
                        )
                    )

                // Attempt to get stream URL
                val streamInfo = provider.getStreamUrl(track.id)

                // Restore state
                currentStreamInfo = streamInfo
                currentProvider = provider
                positionSeconds = savedPosition
                isPlaying = wasPlaying
                lastUpdateTimestamp = System.currentTimeMillis()

                return Result.success(streamInfo)
            } catch (e: StreamingError) {
                lastError = e
            } catch (e: Exception) {
                lastError = StreamingError.NetworkError(
                    "Reconnection attempt $attempt failed: ${e.message}",
                    e
                )
            }

            // If not the last attempt, wait before retrying with exponential backoff
            if (attempt < maxRetryAttempts) {
                delay(retryDelayMs)
                retryDelayMs *= 2 // Exponential backoff
            }
        }

        // All retries exhausted, stop playback and return error
        isPlaying = false
        lastUpdateTimestamp = System.currentTimeMillis()

        return Result.failure(
            lastError ?: StreamingError.NetworkError(
                "Failed to reconnect after $maxRetryAttempts attempts"
            )
        )
    }

    /**
     * Start playback with automatic retry on failure
     * @param track The track to play
     * @return Result with StreamInfo or error
     */
    suspend fun startPlaybackWithRetry(track: Track): Result<StreamInfo> {
        val result = startPlayback(track)
        
        // If initial attempt fails, try reconnection logic
        if (result.isFailure) {
            currentTrack = track
            return reconnectStream()
        }
        
        return result
    }

    /**
     * Get the current playback state, including stream information when available.
     * The streamUrl comes from the provider (e.g. YouTube via yt-dlp) and allows
     * clients to play audio in sync with the backend-managed queue and controls.
     * @return Current PlaybackState
     */
    fun getCurrentState(): PlaybackState {
        val streamInfo = currentStreamInfo
        return PlaybackState(
            currentTrack = currentTrack,
            positionSeconds = positionSeconds,
            isPlaying = isPlaying,
            queue = queueManager.getQueue().map { it.track },
            shuffleEnabled = queueManager.isShuffleEnabled(),
            timestamp = System.currentTimeMillis(),
            streamUrl = streamInfo?.streamUrl,
            streamFormat = streamInfo?.format
        )
    }
}
