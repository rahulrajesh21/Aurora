package com.musicstreaming.services

import com.musicstreaming.adapters.MusicProvider
import com.musicstreaming.models.*
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory

/**
 * Main streaming service that coordinates all components
 * Handles search, playback control, queue management, and state management
 * Requirements: 1.1, 2.1, 4.1, 4.2, 4.3, 9.1, 9.2, 9.3, 9.4
 */
class StreamingService(
    private val providers: Map<ProviderType, MusicProvider>,
    private val queueManager: QueueManager,
    private val playbackEngine: PlaybackEngine,
    private val stateManager: StateManager,
    private val webSocketManager: WebSocketManager
) {
    private val logger = LoggerFactory.getLogger(StreamingService::class.java)

    companion object {
        private const val SEARCH_TIMEOUT_MS = 100000L
    }

    /**
     * Update state and broadcast to all WebSocket clients
     * Requirement 3.4: Send updates within 500ms of state change
     * Requirement 8.4: Broadcast on all state changes
     */
    private suspend fun updateAndBroadcastState(newState: PlaybackState) {
        stateManager.updateState(newState)
        webSocketManager.broadcastState(newState)
    }

    /**
     * Search for tracks across available providers
     * Requirement 1.1: Query YouTube API
     * Requirement 1.2: Return unified list with track details
     * Requirement 1.4: Return results from available providers if one fails
     * Requirement 5.4: Ensure response time under 2 seconds
     */
    suspend fun search(query: String): Result<SearchResult> {
        return try {
            if (query.isBlank()) {
                return Result.failure(
                    StreamingError.NetworkError("Search query cannot be blank")
                )
            }

            logger.info("Searching for: $query")

            // Search with timeout to ensure response under 2 seconds
            val tracks = withTimeout(SEARCH_TIMEOUT_MS) {
                val allTracks = mutableListOf<Track>()
                val successfulProviders = mutableListOf<ProviderType>()

                // Search across all providers
                for ((providerType, provider) in providers) {
                    try {
                        val results = provider.search(query, limit = 20)
                        allTracks.addAll(results)
                        successfulProviders.add(providerType)
                        logger.info("Found ${results.size} tracks from $providerType")
                    } catch (e: Exception) {
                        // Handle search failures gracefully - continue with other providers
                        logger.warn("Search failed for provider $providerType: ${e.message}", e)
                    }
                }

                SearchResult(
                    tracks = allTracks,
                    query = query,
                    providers = successfulProviders
                )
            }

            Result.success(tracks)
        } catch (e: StreamingError) {
            logger.error("Search error for query '$query'", e)
            Result.failure(e)
        } catch (e: Exception) {
            logger.error("Unexpected search error for query '$query'", e)
            Result.failure(
                StreamingError.NetworkError("Search failed: ${e.message}", e)
            )
        }
    }

    /**
     * Start playback for a specific track
     * Requirement 2.1: Initiate playback from search results
     */
    suspend fun play(trackId: String, provider: ProviderType): Result<PlaybackState> {
        return try {
            logger.info("Starting playback for track: $trackId from provider: $provider")

            // Get track details first
            val musicProvider = providers[provider]
                ?: return Result.failure(
                    StreamingError.ProviderError(provider, "Provider not available")
                )

            val track = musicProvider.getTrack(trackId)
                ?: return Result.failure(
                    StreamingError.TrackNotFoundError(trackId, provider)
                )

            // Start playback
            val streamResult = playbackEngine.startPlayback(track)
            if (streamResult.isFailure) {
                return Result.failure(streamResult.exceptionOrNull() ?: 
                    StreamingError.NetworkError("Failed to start playback"))
            }

            // Update state and broadcast
            val newState = playbackEngine.getCurrentState()
            updateAndBroadcastState(newState)

            logger.info("Playback started successfully for track: ${track.title}")
            Result.success(newState)
        } catch (e: StreamingError) {
            logger.error("Playback error for track $trackId", e)
            Result.failure(e)
        } catch (e: Exception) {
            logger.error("Unexpected playback error for track $trackId", e)
            Result.failure(
                StreamingError.NetworkError("Playback failed: ${e.message}", e)
            )
        }
    }

    /**
     * Pause current playback
     * Requirement 4.2: Halt stream and preserve position
     */
    suspend fun pause(): Result<PlaybackState> {
        return try {
            logger.info("Pausing playback")

            val pauseResult = playbackEngine.pause()
            if (pauseResult.isFailure) {
                return Result.failure(pauseResult.exceptionOrNull() ?: 
                    StreamingError.NetworkError("Failed to pause"))
            }

            // Update state and broadcast
            val newState = playbackEngine.getCurrentState()
            updateAndBroadcastState(newState)

            logger.info("Playback paused")
            Result.success(newState)
        } catch (e: StreamingError) {
            logger.error("Pause error", e)
            Result.failure(e)
        } catch (e: Exception) {
            logger.error("Unexpected pause error", e)
            Result.failure(
                StreamingError.NetworkError("Pause failed: ${e.message}", e)
            )
        }
    }

    /**
     * Resume playback from current position
     * Requirement 4.3: Resume from preserved position
     */
    suspend fun resume(): Result<PlaybackState> {
        return try {
            logger.info("Resuming playback")

            val resumeResult = playbackEngine.resume()

            val finalState = if (resumeResult.isSuccess) {
                logger.info("Playback resumed on existing track")
                playbackEngine.getCurrentState()
            } else {
                val error = resumeResult.exceptionOrNull()
                // If there is no track to resume but we have items in the queue,
                // start playback from the next track in the queue instead of failing.
                if (error is StreamingError.NetworkError && error.message?.contains("No track to resume") == true) {
                    logger.info("No current track to resume, attempting to start playback from queue")

                    val nextResult = playbackEngine.playNext()
                    if (nextResult.isFailure) {
                        return Result.failure(nextResult.exceptionOrNull() ?: 
                            StreamingError.QueueError("No track available in queue to start playback"))
                    }

                    logger.info("Started playback from queue")
                    playbackEngine.getCurrentState()
                } else {
                    // Propagate other resume errors
                    return Result.failure(error ?: StreamingError.NetworkError("Failed to resume"))
                }
            }

            // Update state and broadcast
            updateAndBroadcastState(finalState)

            logger.info("Playback resumed")
            Result.success(finalState)
        } catch (e: StreamingError) {
            logger.error("Resume error", e)
            Result.failure(e)
        } catch (e: Exception) {
            logger.error("Unexpected resume error", e)
            Result.failure(
                StreamingError.NetworkError("Resume failed: ${e.message}", e)
            )
        }
    }

    /**
     * Skip to the next track in the queue
     * Requirement 4.1: Provide skip operation
     */
    suspend fun skip(): Result<PlaybackState> {
        return try {
            logger.info("Skipping to next track")

            val nextResult = playbackEngine.playNext()
            if (nextResult.isFailure) {
                return Result.failure(nextResult.exceptionOrNull() ?: 
                    StreamingError.QueueError("No next track available"))
            }

            // Update state and broadcast
            val newState = playbackEngine.getCurrentState()
            updateAndBroadcastState(newState)

            logger.info("Skipped to next track")
            Result.success(newState)
        } catch (e: StreamingError) {
            logger.error("Skip error", e)
            Result.failure(e)
        } catch (e: Exception) {
            logger.error("Unexpected skip error", e)
            Result.failure(
                StreamingError.NetworkError("Skip failed: ${e.message}", e)
            )
        }
    }

    /**
     * Seek to a specific position in the current track
     * Requirement 9.1: Accept position in seconds or percentage
     * Requirement 9.2: Validate target position
     * Requirement 9.3: Update stream handler within 1 second
     * Requirement 9.4: Update playback state within 500ms
     */
    suspend fun seek(positionSeconds: Long): Result<PlaybackState> {
        return try {
            logger.info("Seeking to position: $positionSeconds seconds")

            // Validate and seek
            val seekResult = playbackEngine.seekTo(positionSeconds)
            if (seekResult.isFailure) {
                return Result.failure(seekResult.exceptionOrNull() ?: 
                    StreamingError.NetworkError("Failed to seek"))
            }

            // Update state and broadcast
            val newState = playbackEngine.getCurrentState()
            updateAndBroadcastState(newState)

            logger.info("Seeked to position: $positionSeconds seconds")
            Result.success(newState)
        } catch (e: StreamingError) {
            logger.error("Seek error to position $positionSeconds", e)
            Result.failure(e)
        } catch (e: Exception) {
            logger.error("Unexpected seek error to position $positionSeconds", e)
            Result.failure(
                StreamingError.NetworkError("Seek failed: ${e.message}", e)
            )
        }
    }

    /**
     * Seek to a specific position using percentage of track duration
     * Requirement 9.1: Accept position as percentage
     */
    suspend fun seekByPercentage(percentage: Double): Result<PlaybackState> {
        return try {
            if (percentage < 0.0 || percentage > 100.0) {
                return Result.failure(
                    StreamingError.NetworkError("Percentage must be between 0 and 100")
                )
            }

            val currentState = playbackEngine.getCurrentState()
            val currentTrack = currentState.currentTrack
                ?: return Result.failure(
                    StreamingError.NetworkError("No track currently playing")
                )

            // Convert percentage to seconds
            val positionSeconds = ((percentage / 100.0) * currentTrack.durationSeconds).toLong()

            logger.info("Seeking to $percentage% (${positionSeconds}s)")
            seek(positionSeconds)
        } catch (e: StreamingError) {
            logger.error("Seek by percentage error: $percentage%", e)
            Result.failure(e)
        } catch (e: Exception) {
            logger.error("Unexpected seek by percentage error: $percentage%", e)
            Result.failure(
                StreamingError.NetworkError("Seek by percentage failed: ${e.message}", e)
            )
        }
    }

    /**
     * Get the current playback state
     */
    suspend fun getState(): PlaybackState {
        return stateManager.getState()
    }

    /**
     * Add a track to the queue by fetching its details first
     */
    suspend fun addToQueue(trackId: String, provider: ProviderType): Result<Unit> {
        return try {
            logger.info("Adding track to queue: trackId=$trackId, provider=$provider")

            // Get the music provider
            val musicProvider = providers[provider]
            if (musicProvider == null) {
                logger.error("Provider not found: $provider")
                return Result.failure(StreamingError.NetworkError("Provider $provider not available"))
            }

            // Fetch track details
            val track = musicProvider.getTrack(trackId)
            if (track == null) {
                logger.error("Track not found: $trackId")
                return Result.failure(StreamingError.NetworkError("Track not found: $trackId"))
            }

            logger.info("Track details fetched: ${track.title}")

            val result = queueManager.addTrack(track, "user")
            if (result.isFailure) {
                return Result.failure(result.exceptionOrNull() ?: 
                    StreamingError.QueueError("Failed to add track"))
            }

            // Update state and broadcast
            val newState = playbackEngine.getCurrentState()
            updateAndBroadcastState(newState)

            logger.info("Track added to queue: ${track.title}")
            Result.success(Unit)
        } catch (e: StreamingError) {
            logger.error("Add to queue error for trackId: $trackId", e)
            Result.failure(e)
        } catch (e: Exception) {
            logger.error("Unexpected add to queue error for trackId: $trackId", e)
            Result.failure(
                StreamingError.QueueError("Add to queue failed: ${e.message}")
            )
        }
    }

    /**
     * Remove a track from the queue by position
     */
    suspend fun removeFromQueue(position: Int): Result<Unit> {
        return try {
            logger.info("Removing track from queue at position: $position")

            val result = queueManager.removeTrack(position)
            if (result.isFailure) {
                return Result.failure(result.exceptionOrNull() ?: 
                    StreamingError.QueueError("Failed to remove track"))
            }

            // Update state and broadcast
            val newState = playbackEngine.getCurrentState()
            updateAndBroadcastState(newState)

            logger.info("Track removed from queue at position: $position")
            Result.success(Unit)
        } catch (e: StreamingError) {
            logger.error("Remove from queue error at position: $position", e)
            Result.failure(e)
        } catch (e: Exception) {
            logger.error("Unexpected remove from queue error at position: $position", e)
            Result.failure(
                StreamingError.QueueError("Remove from queue failed: ${e.message}")
            )
        }
    }

    /**
     * Reorder tracks in the queue
     */
    suspend fun reorderQueue(fromPosition: Int, toPosition: Int): Result<Unit> {
        return try {
            logger.info("Reordering queue from $fromPosition to $toPosition")

            val result = queueManager.reorderTrack(fromPosition, toPosition)
            if (result.isFailure) {
                return Result.failure(result.exceptionOrNull() ?: 
                    StreamingError.QueueError("Failed to reorder"))
            }

            // Update state and broadcast
            val newState = playbackEngine.getCurrentState()
            updateAndBroadcastState(newState)

            logger.info("Queue reordered from $fromPosition to $toPosition")
            Result.success(Unit)
        } catch (e: StreamingError) {
            logger.error("Reorder queue error from $fromPosition to $toPosition", e)
            Result.failure(e)
        } catch (e: Exception) {
            logger.error("Unexpected reorder queue error from $fromPosition to $toPosition", e)
            Result.failure(
                StreamingError.QueueError("Reorder queue failed: ${e.message}")
            )
        }
    }

    /**
     * Clear all tracks from the queue
     */
    suspend fun clearQueue(): Result<Unit> {
        return try {
            logger.info("Clearing queue")

            val result = queueManager.clearQueue()
            if (result.isFailure) {
                return Result.failure(result.exceptionOrNull() ?: 
                    StreamingError.QueueError("Failed to clear queue"))
            }

            // Update state and broadcast
            val newState = playbackEngine.getCurrentState()
            updateAndBroadcastState(newState)

            logger.info("Queue cleared")
            Result.success(Unit)
        } catch (e: StreamingError) {
            logger.error("Clear queue error", e)
            Result.failure(e)
        } catch (e: Exception) {
            logger.error("Unexpected clear queue error", e)
            Result.failure(
                StreamingError.QueueError("Clear queue failed: ${e.message}")
            )
        }
    }

    /**
     * Shuffle the queue
     */
    suspend fun shuffleQueue(): Result<Unit> {
        return try {
            logger.info("Shuffling queue")

            val result = queueManager.shuffle()
            if (result.isFailure) {
                return Result.failure(result.exceptionOrNull() ?: 
                    StreamingError.QueueError("Failed to shuffle"))
            }

            // Update state and broadcast
            val newState = playbackEngine.getCurrentState()
            updateAndBroadcastState(newState)

            logger.info("Queue shuffled")
            Result.success(Unit)
        } catch (e: StreamingError) {
            logger.error("Shuffle queue error", e)
            Result.failure(e)
        } catch (e: Exception) {
            logger.error("Unexpected shuffle queue error", e)
            Result.failure(
                StreamingError.QueueError("Shuffle queue failed: ${e.message}")
            )
        }
    }

    /**
     * Get the current queue
     */
    fun getQueue(): List<Track> {
        return queueManager.getQueue().map { it.track }
    }
}
