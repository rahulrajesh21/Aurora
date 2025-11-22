package com.example.music_room.utils

import com.example.music_room.data.remote.model.TrackDto
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackTitleFormatterTest {

    @Test
    fun stripCommonNoise_removesBracketedContent() {
        val result = "Something Just Like This (Official Lyric Video)".sanitizeTrackTitle()
        assertEquals("Something Just Like This", result)
    }

    @Test
    fun displayTitle_stripsTaggingNoise() {
        val track = TrackDto(title = "The Chainsmokers - Closer [OFFICIAL VIDEO] VEVO")
        assertEquals("The Chainsmokers - Closer", track.displayTitle())
    }

    @Test
    fun stripCommonNoise_handlesNullGracefully() {
        val result = null.sanitizeTrackTitle()
        assertEquals("", result)
    }
}
