package com.example.music_room.data.manager

import com.example.music_room.data.AuroraServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object StreamPrefetcher {
    private val repository get() = AuroraServiceLocator.repository

    suspend fun prefetch(trackId: String) {
        if (trackId.isBlank()) return
        withContext(Dispatchers.IO) {
            runCatching {
                repository.prefetchStream(trackId).getOrNull()
            }.onSuccess { url ->
                if (!url.isNullOrBlank()) {
                    StreamPrefetchCache.store(trackId, url)
                }
            }
        }
    }
}
