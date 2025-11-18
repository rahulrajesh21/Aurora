package com.musicstreaming.storage

import com.musicstreaming.models.QueueItem
import com.musicstreaming.models.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * File-based implementation of QueueStorage
 * Persists queue to a JSON file with async operations
 */
class FileQueueStorage(
    private val storageFile: File = File("queue_storage.json")
) : QueueStorage {
    
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }
    
    private val mutex = Mutex()
    private val queue = mutableListOf<QueueItem>()
    
    init {
        // Load existing queue on initialization
        if (storageFile.exists()) {
            try {
                val content = storageFile.readText()
                if (content.isNotBlank()) {
                    val items = json.decodeFromString<List<QueueItem>>(content)
                    queue.addAll(items)
                }
            } catch (e: Exception) {
                // Handle persistence errors gracefully - log but don't fail
                System.err.println("Failed to load queue from storage: ${e.message}")
            }
        }
    }

    override suspend fun addToQueue(track: Track, addedBy: String): QueueItem = mutex.withLock {
        val queueItem = QueueItem(
            id = UUID.randomUUID().toString(),
            track = track,
            addedBy = addedBy,
            addedAt = System.currentTimeMillis(),
            position = queue.size
        )
        
        queue.add(queueItem)
        persistQueue()
        
        return queueItem
    }

    override suspend fun removeFromQueue(queueItemId: String): Boolean = mutex.withLock {
        val removed = queue.removeIf { it.id == queueItemId }
        
        if (removed) {
            // Update positions
            queue.forEachIndexed { index, item ->
                queue[index] = item.copy(position = index)
            }
            persistQueue()
        }
        
        return removed
    }

    override suspend fun getQueue(): List<QueueItem> = mutex.withLock {
        return queue.toList()
    }

    override suspend fun getNext(): QueueItem? = mutex.withLock {
        return queue.firstOrNull()
    }

    override suspend fun popNext(): QueueItem? = mutex.withLock {
        if (queue.isEmpty()) {
            return null
        }
        
        val next = queue.removeAt(0)
        
        // Update positions
        queue.forEachIndexed { index, item ->
            queue[index] = item.copy(position = index)
        }
        
        persistQueue()
        
        return next
    }

    override suspend fun clearQueue() = mutex.withLock {
        queue.clear()
        persistQueue()
    }

    override suspend fun reorderQueue(queueItemId: String, newPosition: Int): Boolean = mutex.withLock {
        val currentIndex = queue.indexOfFirst { it.id == queueItemId }
        
        if (currentIndex == -1 || newPosition < 0 || newPosition >= queue.size) {
            return false
        }
        
        val item = queue.removeAt(currentIndex)
        queue.add(newPosition, item)
        
        // Update positions
        queue.forEachIndexed { index, queueItem ->
            queue[index] = queueItem.copy(position = index)
        }
        
        persistQueue()
        
        return true
    }

    /**
     * Persist the queue to file asynchronously
     * Handles errors gracefully without throwing exceptions
     */
    private suspend fun persistQueue() {
        try {
            withContext(Dispatchers.IO) {
                val jsonContent = json.encodeToString(queue)
                storageFile.writeText(jsonContent)
            }
        } catch (e: Exception) {
            // Handle persistence errors gracefully - log but don't fail
            System.err.println("Failed to persist queue to storage: ${e.message}")
        }
    }

    /**
     * Restore queue from file
     * @return List of queue items or empty list if restore fails
     */
    suspend fun restore(): List<QueueItem> = mutex.withLock {
        return try {
            if (storageFile.exists()) {
                withContext(Dispatchers.IO) {
                    val content = storageFile.readText()
                    if (content.isNotBlank()) {
                        json.decodeFromString<List<QueueItem>>(content)
                    } else {
                        emptyList()
                    }
                }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            // Handle restore errors gracefully
            System.err.println("Failed to restore queue from storage: ${e.message}")
            emptyList()
        }
    }
}
