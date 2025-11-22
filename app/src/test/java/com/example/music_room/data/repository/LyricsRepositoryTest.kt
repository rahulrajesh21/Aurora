package com.example.music_room.data.repository

import com.example.music_room.data.remote.model.LyricLineDto
import com.example.music_room.data.remote.model.LyricPartDto
import com.example.music_room.data.remote.model.LyricsResponseDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LyricsRepositoryTest {

    @Test
    fun toSyncedLyrics_returnsNull_whenBackendSignalsNoLyrics() {
        val dto = LyricsResponseDto(
            song = "Test",
            artist = "Artist",
            album = null,
            durationMs = 120_000,
            videoId = "vid",
            source = "Better Lyrics",
            sourceHref = null,
            lyrics = listOf(
                LyricLineDto(
                    startTimeMs = 0,
                    durationMs = 0,
                    words = "No lyrics found for this song",
                    parts = emptyList(),
                    translation = null
                )
            )
        )

        val mapped = dto.toSyncedLyrics()

        assertNull(mapped)
    }

    @Test
    fun toSyncedLyrics_detectsRichSync_whenWordPartsHaveDurations() {
        val dto = LyricsResponseDto(
            song = "Test",
            artist = "Artist",
            album = null,
            durationMs = 120_000,
            videoId = "vid",
            source = "Better Lyrics",
            sourceHref = null,
            lyrics = listOf(
                LyricLineDto(
                    startTimeMs = 1000,
                    durationMs = 2000,
                    words = "Hello world",
                    parts = listOf(
                        LyricPartDto(startTimeMs = 1000, durationMs = 500, words = "Hello", isBackground = false),
                        LyricPartDto(startTimeMs = 1500, durationMs = 500, words = "world", isBackground = false)
                    ),
                    translation = null
                )
            )
        )

        val mapped = dto.toSyncedLyrics()

        assertNotNull(mapped)
        assertEquals(SyncType.RICH_SYNC, mapped?.syncType)
        assertEquals(1, mapped?.lines?.size)
        assertEquals("Hello world", mapped?.lines?.first()?.words)
    }

    @Test
    fun determineSyncType_returnsLineSync_whenOnlyLineTimingExists() {
        val lines = listOf(
            SyncedLyricLine(
                startTimeMs = 1000,
                durationMs = 2000,
                words = "Only line timing",
                translation = null,
                romanization = null,
                parts = emptyList()
            )
        )

        val type = determineSyncType(lines)

        assertEquals(SyncType.LINE_SYNC, type)
    }
}
