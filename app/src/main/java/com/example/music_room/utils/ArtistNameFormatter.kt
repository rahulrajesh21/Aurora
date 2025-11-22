package com.example.music_room.utils

import com.example.music_room.data.remote.model.TrackDto

object ArtistNameFormatter {
    private val topicSuffixPattern = Regex("(?i)\\s*-\\s*topic$")

    fun stripTopicSuffix(input: String?): String {
        if (input.isNullOrBlank()) {
            return input?.trim().orEmpty()
        }
        return topicSuffixPattern.replace(input, "").trim()
    }
}

fun TrackDto.displayArtist(): String = ArtistNameFormatter.stripTopicSuffix(this.artist)

fun String?.sanitizeArtistLabel(): String = ArtistNameFormatter.stripTopicSuffix(this)
