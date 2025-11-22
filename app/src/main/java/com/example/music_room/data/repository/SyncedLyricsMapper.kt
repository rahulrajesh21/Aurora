package com.example.music_room.data.repository

import com.example.music_room.data.remote.model.LyricLineDto
import com.example.music_room.data.remote.model.LyricPartDto
import com.example.music_room.data.remote.model.LyricsResponseDto

private const val NO_LYRICS_MESSAGE = "No lyrics found for this song"

/**
 * Shared mapping helpers that mirror the Better Lyrics browser extension logic.
 */
internal fun LyricsResponseDto.toSyncedLyrics(): SyncedLyrics? {
    if (lyrics.isEmpty()) return null
    if (lyrics.size == 1 && lyrics.first().words == NO_LYRICS_MESSAGE) return null

    val mappedLines = lyrics.map { it.toDomain() }
    return SyncedLyrics(
        lines = mappedLines,
        sourceLabel = source ?: "Better Lyrics",
        sourceUrl = sourceHref,
        syncType = determineSyncType(mappedLines),
        language = language,
        musicVideoSynced = musicVideoSynced ?: false,
        normalizedSong = song.takeIf { !it.isNullOrBlank() },
        normalizedArtist = artist.takeIf { !it.isNullOrBlank() }
    )
}

internal fun determineSyncType(lines: List<SyncedLyricLine>): SyncType {
    if (lines.isEmpty()) return SyncType.NONE

    val allZero = lines.all { it.startTimeMs == 0L }
    if (allZero) return SyncType.NONE

    val hasRichSync = lines.any { line ->
        line.parts.isNotEmpty() && line.parts.any { it.durationMs > 0 }
    }

    return if (hasRichSync) SyncType.RICH_SYNC else SyncType.LINE_SYNC
}

private fun LyricLineDto.toDomain(): SyncedLyricLine {
    return SyncedLyricLine(
        startTimeMs = startTimeMs,
        durationMs = durationMs.takeIf { it > 0 } ?: 1000L,
        words = words,
        translation = translation?.text,
        romanization = romanization,
        parts = parts?.map { it.toDomain() } ?: emptyList()
    )
}

private fun LyricPartDto.toDomain(): SyncedLyricPart {
    return SyncedLyricPart(
        startTimeMs = startTimeMs,
        durationMs = durationMs,
        words = words,
        isBackground = isBackground == true
    )
}
