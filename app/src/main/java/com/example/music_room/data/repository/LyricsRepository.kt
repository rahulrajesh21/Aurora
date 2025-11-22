package com.example.music_room.data.repository

import com.example.music_room.data.remote.LyricsApi
import com.example.music_room.data.remote.model.LyricsRequestDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * LyricsRepository - Better Lyrics Parity Implementation
 * 
 * This implementation mirrors the Better Lyrics browser extension exactly:
 * - Minimal song/artist name cleaning (only trim and ", & " replacement)
 * - Direct pass-through to backend (which handles provider fallback)
 * - Support for rich-sync (word-level) and line-sync lyrics
 * - Segment map support for music videos
 */
class LyricsRepository(
    private val api: LyricsApi
) {
    /**
     * Fetch lyrics using Better Lyrics algorithm
     * 
     * Better Lyrics approach (from lyrics.ts:113-115):
     *   song = song.trim();
     *   artist = artist.trim();
     *   artist = artist.replace(", & ", ", ");
     * 
     * That's it! No aggressive cleaning, no bracket removal, no artist prefix stripping.
     */
    suspend fun getLyrics(
        trackName: String,
        artistName: String,
        durationSeconds: Int,
        videoId: String?
    ): Result<SyncedLyrics> = withContext(Dispatchers.IO) {
        try {
            // Better Lyrics minimal cleaning - EXACTLY as in lyrics.ts
            val cleanTrack = trackName.trim()
            val cleanArtist = artistName.trim().replace(", & ", ", ")

            val request = LyricsRequestDto(
                song = cleanTrack,
                artist = cleanArtist,
                videoId = videoId?.takeIf { it.isNotBlank() } ?: "",
                durationMs = durationSeconds.coerceAtLeast(0).toLong() * 1000L
            )

            val response = api.fetchLyrics(request)

            val synced = response.toSyncedLyrics()
                ?: return@withContext Result.failure(Exception("No lyrics found"))

            Result.success(synced)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

/**
 * Sync types matching Better Lyrics (injectLyrics.ts:181)
 * - RICH_SYNC: Word-by-word karaoke timing
 * - LINE_SYNC: Line-by-line timing
 * - NONE: Unsynced plain text
 */
enum class SyncType {
    RICH_SYNC,  // richsync - word-level parts with durations
    LINE_SYNC,  // synced - line-level timing only
    NONE        // none - no timing information
}

data class SyncedLyrics(
    val lines: List<SyncedLyricLine>,
    val sourceLabel: String,
    val sourceUrl: String?,
    val syncType: SyncType,
    val language: String?,
    val musicVideoSynced: Boolean
)

data class SyncedLyricLine(
    val startTimeMs: Long,
    val durationMs: Long,
    val words: String,
    val translation: String?,
    val romanization: String?,
    val parts: List<SyncedLyricPart>
)

data class SyncedLyricPart(
    val startTimeMs: Long,
    val durationMs: Long,
    val words: String,
    val isBackground: Boolean
)
