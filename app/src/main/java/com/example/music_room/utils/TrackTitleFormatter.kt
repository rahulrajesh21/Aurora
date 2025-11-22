package com.example.music_room.utils

import com.example.music_room.data.remote.model.TrackDto

object TrackTitleFormatter {
    private val noisePattern = Regex("(?i)(official video|official audio|lyrics|lyric video|video|audio|hd|hq|4k|remastered)")
    private val bracketPattern = Regex("(?i)[\\(\\[].*?[\\)\\]]")
    private val vevoPattern = Regex("(?i)vevo")
    private val whitespacePattern = Regex("\\s+")

    fun stripCommonNoise(input: String?): String {
        if (input.isNullOrBlank()) return input?.trim().orEmpty()
        return input
            .replace(noisePattern, "")
            .replace(bracketPattern, "")
            .replace(vevoPattern, "")
            .trim()
            .replace(whitespacePattern, " ")
            .trim()
    }
}

fun TrackDto.displayTitle(): String = TrackTitleFormatter.stripCommonNoise(this.title)
fun String?.sanitizeTrackTitle(): String = TrackTitleFormatter.stripCommonNoise(this)
