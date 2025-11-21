package com.example.music_room.data.repository

import com.example.music_room.data.remote.model.LyricsResponse
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsRepositoryTest {

    @Test
    fun sanitizeArtistName_removesTopicSuffix() {
        val result = LyricsRepository.sanitizeArtistName("dudeontheguitar - Topic")
        assertEquals("dudeontheguitar", result)
    }

    @Test
    fun sanitizeTrackName_stripsNoiseWords() {
        val result = LyricsRepository.sanitizeTrackName("bet (Official Lyric Video)")
        assertEquals("bet", result)
    }

    @Test
    fun sanitizeTrackName_removesArtistPrefixWithHyphen() {
        val result = LyricsRepository.sanitizeTrackName("Sleep Token - Caramel", "Sleep Token")
        assertEquals("Caramel", result)
    }

    @Test
    fun sanitizeTrackName_keepsTitleWhenArtistDoesNotMatch() {
        val result = LyricsRepository.sanitizeTrackName("Random Upload - Caramel", "Sleep Token")
        assertEquals("Random Upload - Caramel", result)
    }

    @Test
    fun selectBestMatch_prefersClosestDuration() {
        val responses = listOf(
            response(id = 1, duration = 200.0),
            response(id = 2, duration = 260.5),
            response(id = 3, duration = 100.0)
        )

        val selected = LyricsRepository.selectBestMatch(responses, durationSeconds = 262)

        assertEquals(2, selected?.id)
    }

    @Test
    fun selectBestMatch_fallsBackWhenOnlyRemixAvailable() {
        val responses = listOf(
            response(id = 10, duration = 248.0)
        )

        val selected = LyricsRepository.selectBestMatch(responses, durationSeconds = 262)

        assertEquals(10, selected?.id)
    }

    private fun response(id: Int, duration: Double?): LyricsResponse {
        return LyricsResponse(
            id = id,
            plainLyrics = null,
            syncedLyrics = null,
            instrumental = false,
            duration = duration
        )
    }
}
