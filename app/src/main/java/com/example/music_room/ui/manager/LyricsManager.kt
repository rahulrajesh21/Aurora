package com.example.music_room.ui.manager

import com.example.music_room.data.remote.model.LyricsResponseDto
import com.example.music_room.data.repository.LyricsRepository
import com.example.music_room.data.repository.SyncedLyrics
import com.example.music_room.data.repository.toSyncedLyrics
import com.example.music_room.ui.LyricsAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

sealed class LyricsState {
    object Loading : LyricsState()
    data class Success(val lyrics: SyncedLyrics) : LyricsState()
    data class Error(val message: String) : LyricsState()
    object Hidden : LyricsState()
}

class LyricsManager(
    private val repository: LyricsRepository,
    private val adapter: LyricsAdapter,
    private val scope: CoroutineScope,
    private val onLyricsStateChanged: (LyricsState) -> Unit
) {
    var syncedLyrics: SyncedLyrics? = null
        private set
    
    private var currentJob: Job? = null
    private val lyricsCache = LinkedHashMap<String, SyncedLyrics>()
    private val MAX_CACHE_SIZE = 10

    fun fetchLyrics(title: String, artist: String, duration: Int, videoId: String?) {
        if (title.isBlank() || artist.isBlank()) {
            onLyricsStateChanged(LyricsState.Hidden)
            return
        }

        val key = buildKey(videoId, title, artist)
        
        // Check cache
        lyricsCache[key]?.let { cached ->
            displayLyrics(cached)
            return
        }

        currentJob?.cancel()
        adapter.clear()
        syncedLyrics = null
        onLyricsStateChanged(LyricsState.Loading)

        currentJob = scope.launch {
            val result = repository.getLyrics(title, artist, duration, videoId)
            
            result.onSuccess { response ->
                if (response.lines.isNotEmpty()) {
                    cacheLyrics(key, response)
                    displayLyrics(response)
                } else {
                    onLyricsStateChanged(LyricsState.Error("Lyrics not found"))
                }
            }.onFailure { error ->
                onLyricsStateChanged(LyricsState.Error(error.message ?: "Unknown error"))
            }
        }
    }

    fun handleSocketUpdate(response: LyricsResponseDto) {
        val synced = response.toSyncedLyrics() ?: return
        displayLyrics(synced)
    }

    fun updatePosition(positionSeconds: Float) {
        val lyrics = syncedLyrics ?: return
        if (lyrics.lines.isEmpty()) return
        
        val positionMs = (positionSeconds * 1000L).toLong()
        adapter.updatePlaybackPosition(positionMs)
    }
    
    fun getScrollIndex(positionSeconds: Float): Int {
        val lyrics = syncedLyrics ?: return -1
        if (lyrics.lines.isEmpty()) return -1
        return adapter.updatePlaybackPosition((positionSeconds * 1000L).toLong())
    }

    fun clear() {
        currentJob?.cancel()
        adapter.clear()
        syncedLyrics = null
        onLyricsStateChanged(LyricsState.Hidden)
    }

    private fun displayLyrics(lyrics: SyncedLyrics) {
        syncedLyrics = lyrics
        adapter.submitLines(lyrics.lines, lyrics.syncType)
        onLyricsStateChanged(LyricsState.Success(lyrics))
    }

    private fun buildKey(videoId: String?, title: String, artist: String): String {
        return listOf(videoId.orEmpty(), title, artist).joinToString("|").lowercase()
    }

    private fun cacheLyrics(key: String, lyrics: SyncedLyrics) {
        lyricsCache[key] = lyrics
        if (lyricsCache.size > MAX_CACHE_SIZE) {
            val firstKey = lyricsCache.keys.firstOrNull()
            if (firstKey != null) {
                lyricsCache.remove(firstKey)
            }
        }
    }
}
