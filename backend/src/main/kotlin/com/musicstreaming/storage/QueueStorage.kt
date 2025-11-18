package com.musicstreaming.storage

import com.musicstreaming.models.QueueItem
import com.musicstreaming.models.Track

/**
 * Interface for queue storage operations
 * Manages the persistent storage of the music queue
 */
interface QueueStorage {
    /**
     * Add a track to the end of the queue
     * @param track The track to add
     * @param addedBy Identifier of the user who added the track
     * @return The created QueueItem
     */
    suspend fun addToQueue(track: Track, addedBy: String): QueueItem
    
    /**
     * Remove a specific item from the queue
     * @param queueItemId The ID of the queue item to remove
     * @return true if the item was removed, false if not found
     */
    suspend fun removeFromQueue(queueItemId: String): Boolean
    
    /**
     * Get all items currently in the queue, ordered by position
     * @return List of queue items in order
     */
    suspend fun getQueue(): List<QueueItem>
    
    /**
     * Get the next item in the queue (first item)
     * @return The next queue item or null if queue is empty
     */
    suspend fun getNext(): QueueItem?
    
    /**
     * Remove and return the next item in the queue
     * @return The next queue item or null if queue is empty
     */
    suspend fun popNext(): QueueItem?
    
    /**
     * Clear all items from the queue
     */
    suspend fun clearQueue()
    
    /**
     * Reorder queue items
     * @param queueItemId The ID of the item to move
     * @param newPosition The new position (0-based index)
     * @return true if reordering was successful
     */
    suspend fun reorderQueue(queueItemId: String, newPosition: Int): Boolean
}
