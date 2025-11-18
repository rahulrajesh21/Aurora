package com.musicstreaming.services

import com.musicstreaming.models.PlaybackState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Manages playback state in memory with optional persistence
 * Provides thread-safe access to current playback state
 * Requirements: 2.5, 4.2, 4.3, 3.5
 */
class StateManager(
    private val storageFile: File = File("playback_state.json")
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }
    
    private val mutex = Mutex()
    private var currentState: PlaybackState = PlaybackState(
        currentTrack = null,
        positionSeconds = 0,
        isPlaying = false,
        queue = emptyList(),
        shuffleEnabled = false,
        timestamp = System.currentTimeMillis()
    )

    /**
     * Update the current playback state
     * Thread-safe operation using mutex
     * @param state The new playback state
     */
    suspend fun updateState(state: PlaybackState) = mutex.withLock {
        currentState = state
    }

    /**
     * Get the current playback state
     * Thread-safe operation using mutex
     * @return Current PlaybackState snapshot
     */
    suspend fun getState(): PlaybackState = mutex.withLock {
        return currentState
    }

    /**
     * Persist the current playback state to file asynchronously
     * Handles errors gracefully without blocking operations
     * Requirement: 3.5
     */
    suspend fun persistState() {
        try {
            val stateToPersist = mutex.withLock { currentState }
            
            withContext(Dispatchers.IO) {
                val jsonContent = json.encodeToString(stateToPersist)
                storageFile.writeText(jsonContent)
            }
        } catch (e: Exception) {
            // Handle persistence errors gracefully - log but don't fail
            System.err.println("Failed to persist playback state: ${e.message}")
        }
    }

    /**
     * Restore playback state from file on application restart
     * Handles errors gracefully without blocking operations
     * @return Restored PlaybackState or null if restore fails
     * Requirement: 3.5
     */
    suspend fun restoreState(): PlaybackState? {
        return try {
            if (!storageFile.exists()) {
                return null
            }

            val restoredState = withContext(Dispatchers.IO) {
                val content = storageFile.readText()
                if (content.isNotBlank()) {
                    json.decodeFromString<PlaybackState>(content)
                } else {
                    null
                }
            }

            if (restoredState != null) {
                mutex.withLock {
                    currentState = restoredState
                }
            }

            restoredState
        } catch (e: Exception) {
            // Handle restore errors gracefully - log but don't fail
            System.err.println("Failed to restore playback state: ${e.message}")
            null
        }
    }
}
