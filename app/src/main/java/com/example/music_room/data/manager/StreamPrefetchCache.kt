package com.example.music_room.data.manager

import java.util.concurrent.ConcurrentHashMap

object StreamPrefetchCache {
    private val cache = ConcurrentHashMap<String, String>()

    fun store(trackId: String, streamUrl: String) {
        if (trackId.isBlank() || streamUrl.isBlank()) return
        cache[trackId] = streamUrl
    }

    fun consume(trackId: String?): String? {
        if (trackId.isNullOrBlank()) return null
        return cache.remove(trackId)
    }
}
