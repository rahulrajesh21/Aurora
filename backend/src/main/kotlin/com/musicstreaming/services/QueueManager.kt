package com.musicstreaming.services

import com.musicstreaming.models.QueueItem
import com.musicstreaming.models.StreamingError
import com.musicstreaming.models.Track
import com.musicstreaming.storage.QueueStorage
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages the playback queue with in-memory storage
 * Supports add, remove, reorder, shuffle, and clear operations
 */
class QueueManager(
    private val storage: QueueStorage? = null,
    private val maxQueueSize: Int = 100
) {
    private val queue = mutableListOf<QueueItem>()
    private val positionCounter = AtomicInteger(0)
    private val secureRandom = SecureRandom()
    
    // For shuffle functionality
    private var originalOrder: List<QueueItem>? = null
    private var shuffleEnabled = false
    private var currentlyPlayingId: String? = null

    /**
     * Add a track to the end of the queue
     * @param track The track to add
     * @param addedBy Identifier of the user who added the track (default: "system")
     * @return Result with the created QueueItem or error
     */
    suspend fun addTrack(track: Track, addedBy: String = "system"): Result<QueueItem> {
        return try {
            // Validate queue capacity
            if (queue.size >= maxQueueSize) {
                return Result.failure(
                    StreamingError.QueueError("Queue is full. Maximum capacity is $maxQueueSize tracks")
                )
            }

            val queueItem = QueueItem(
                id = UUID.randomUUID().toString(),
                track = track,
                addedBy = addedBy,
                addedAt = System.currentTimeMillis(),
                position = positionCounter.getAndIncrement()
            )

            queue.add(queueItem)
            
            // Persist if storage is available
            storage?.addToQueue(track, addedBy)

            Result.success(queueItem)
        } catch (e: Exception) {
            Result.failure(StreamingError.QueueError("Failed to add track: ${e.message}"))
        }
    }

    /**
     * Remove a track from the queue by position
     * @param position The 0-based position of the track to remove
     * @return Result with success or error
     */
    suspend fun removeTrack(position: Int): Result<Unit> {
        return try {
            // Validate position
            if (position < 0 || position >= queue.size) {
                return Result.failure(
                    StreamingError.QueueError("Invalid position: $position. Queue size is ${queue.size}")
                )
            }

            val removedItem = queue.removeAt(position)
            
            // Update positions for subsequent tracks
            updatePositionsAfterRemoval(position)
            
            // Persist if storage is available
            storage?.removeFromQueue(removedItem.id)

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(StreamingError.QueueError("Failed to remove track: ${e.message}"))
        }
    }

    /**
     * Reorder a track within the queue
     * @param fromPosition The current position of the track
     * @param toPosition The target position for the track
     * @return Result with success or error
     */
    suspend fun reorderTrack(fromPosition: Int, toPosition: Int): Result<Unit> {
        return try {
            // Validate positions
            if (fromPosition < 0 || fromPosition >= queue.size) {
                return Result.failure(
                    StreamingError.QueueError("Invalid from position: $fromPosition. Queue size is ${queue.size}")
                )
            }
            if (toPosition < 0 || toPosition >= queue.size) {
                return Result.failure(
                    StreamingError.QueueError("Invalid to position: $toPosition. Queue size is ${queue.size}")
                )
            }

            if (fromPosition == toPosition) {
                return Result.success(Unit)
            }

            val item = queue.removeAt(fromPosition)
            queue.add(toPosition, item)
            
            // Update positions for affected tracks
            updatePositionsAfterReorder()
            
            // Persist if storage is available
            storage?.reorderQueue(item.id, toPosition)

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(StreamingError.QueueError("Failed to reorder track: ${e.message}"))
        }
    }

    /**
     * Clear all tracks from the queue
     * @return Result with success or error
     */
    suspend fun clearQueue(): Result<Unit> {
        return try {
            queue.clear()
            positionCounter.set(0)
            originalOrder = null
            shuffleEnabled = false
            
            // Persist if storage is available
            storage?.clearQueue()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(StreamingError.QueueError("Failed to clear queue: ${e.message}"))
        }
    }

    /**
     * Get all tracks in the queue
     * @return List of queue items in order
     */
    fun getQueue(): List<QueueItem> {
        return queue.toList()
    }

    /**
     * Get the next track in the queue
     * @return The next queue item or null if queue is empty
     */
    fun getNextTrack(): QueueItem? {
        return queue.firstOrNull()
    }

    /**
     * Remove and return the next track in the queue
     * @return The next queue item or null if queue is empty
     */
    suspend fun popNextTrack(): QueueItem? {
        if (queue.isEmpty()) {
            return null
        }
        
        val nextItem = queue.removeAt(0)
        updatePositionsAfterRemoval(0)
        
        // Persist if storage is available
        storage?.removeFromQueue(nextItem.id)
        
        return nextItem
    }

    /**
     * Shuffle the queue using SecureRandom
     * Preserves the currently playing track if set
     * @return Result with success or error
     */
    fun shuffle(): Result<Unit> {
        return try {
            if (queue.isEmpty()) {
                return Result.success(Unit)
            }

            // Save original order if not already shuffled
            if (!shuffleEnabled) {
                originalOrder = queue.toList()
            }

            // Separate currently playing track from the rest
            val currentTrack = currentlyPlayingId?.let { id ->
                queue.find { it.id == id }
            }
            
            val tracksToShuffle = if (currentTrack != null) {
                queue.filter { it.id != currentlyPlayingId }
            } else {
                queue.toList()
            }

            // Shuffle using SecureRandom (Fisher-Yates shuffle)
            val shuffled = tracksToShuffle.toMutableList()
            for (i in shuffled.size - 1 downTo 1) {
                val j = secureRandom.nextInt(i + 1)
                val temp = shuffled[i]
                shuffled[i] = shuffled[j]
                shuffled[j] = temp
            }

            // Rebuild queue with current track first (if exists)
            queue.clear()
            if (currentTrack != null) {
                queue.add(currentTrack)
            }
            queue.addAll(shuffled)

            // Update positions
            updatePositionsAfterReorder()
            
            shuffleEnabled = true

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(StreamingError.QueueError("Failed to shuffle queue: ${e.message}"))
        }
    }

    /**
     * Toggle shuffle on/off
     * When turning off, restores the original queue order
     * @return Result with success or error
     */
    fun toggleShuffle(): Result<Unit> {
        return try {
            if (shuffleEnabled) {
                // Turn off shuffle - restore original order
                originalOrder?.let { original ->
                    queue.clear()
                    queue.addAll(original)
                    updatePositionsAfterReorder()
                }
                shuffleEnabled = false
                originalOrder = null
            } else {
                // Turn on shuffle
                shuffle()
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(StreamingError.QueueError("Failed to toggle shuffle: ${e.message}"))
        }
    }

    /**
     * Set the currently playing track ID
     * Used to preserve the track during shuffle operations
     */
    fun setCurrentlyPlaying(trackId: String?) {
        currentlyPlayingId = trackId
    }

    /**
     * Check if shuffle is enabled
     */
    fun isShuffleEnabled(): Boolean {
        return shuffleEnabled
    }

    /**
     * Get the current queue size
     */
    fun getQueueSize(): Int {
        return queue.size
    }

    /**
     * Update positions for all tracks after removal
     */
    private fun updatePositionsAfterRemoval(removedPosition: Int) {
        for (i in removedPosition until queue.size) {
            queue[i] = queue[i].copy(position = i)
        }
    }

    /**
     * Update positions for all tracks after reorder
     */
    private fun updatePositionsAfterReorder() {
        for (i in queue.indices) {
            queue[i] = queue[i].copy(position = i)
        }
    }

    /**
     * Restore queue from storage
     */
    suspend fun restoreFromStorage(): Result<Unit> {
        return try {
            if (storage == null) {
                return Result.success(Unit)
            }

            val storedQueue = storage.getQueue()
            queue.clear()
            queue.addAll(storedQueue)
            
            // Update position counter
            if (queue.isNotEmpty()) {
                positionCounter.set(queue.maxOf { it.position } + 1)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(StreamingError.QueueError("Failed to restore queue: ${e.message}"))
        }
    }
}
