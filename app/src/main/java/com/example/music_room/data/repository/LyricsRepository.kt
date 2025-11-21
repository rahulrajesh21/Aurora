package com.example.music_room.data.repository

import com.example.music_room.data.remote.LyricsApi
import com.example.music_room.data.remote.model.LyricLineDto
import com.example.music_room.data.remote.model.LyricPartDto
import com.example.music_room.data.remote.model.LyricsRequestDto
import com.example.music_room.data.remote.model.LyricsResponseDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LyricsRepository(
    private val api: LyricsApi
) {
    suspend fun getLyrics(
        trackName: String,
        artistName: String,
        durationSeconds: Int,
        videoId: String?
    ): Result<SyncedLyrics> = withContext(Dispatchers.IO) {
        try {
            val cleanArtist = sanitizeArtistName(artistName)
            val cleanTrack = sanitizeTrackName(trackName, cleanArtist)

            val request = LyricsRequestDto(
                song = cleanTrack,
                artist = cleanArtist,
                videoId = videoId?.takeIf { it.isNotBlank() } ?: cleanTrack,
                durationMs = durationSeconds.coerceAtLeast(0).toLong() * 1000L
            )

            val response = api.fetchLyrics(request)
            val lines = normalizeLines(response)

            if (lines.isEmpty()) {
                Result.failure(Exception("No synced lyrics available"))
            } else {
                Result.success(
                    SyncedLyrics(
                        lines = lines,
                        sourceLabel = response.source ?: "",
                        sourceUrl = response.sourceHref
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {
        private val noisePattern = Regex("(?i)(official video|official audio|lyrics|lyric video|video|audio|hd|hq|4k|remastered)")
        private val bracketContent = Regex("(?i)[\\(\\[].*?[\\)\\]]")
        private val multipleSpaces = Regex("\\s+")
        private val topicSuffixPattern = Regex("(?i)\\s*-\\s*topic$")

        internal fun sanitizeTrackName(input: String, artistHint: String? = null): String {
            val normalized = normalizeCommonNoise(input)
            val artist = artistHint?.takeIf { it.isNotBlank() } ?: return normalized
            return stripArtistPrefix(normalized, artist)
        }

        internal fun sanitizeArtistName(input: String): String {
            val cleaned = normalizeCommonNoise(input)
            return topicSuffixPattern.replace(cleaned, "").trim()
        }

        private val artistPrefixDelimiters = charArrayOf('-', '–', '—', ':')

        private fun stripArtistPrefix(track: String, artist: String): String {
            val delimiterIndex = track.indexOfAny(artistPrefixDelimiters)
            if (delimiterIndex == -1) return track

            val possibleArtist = track.substring(0, delimiterIndex).trim()
            val songPortion = track.substring(delimiterIndex + 1).trim()
            if (songPortion.isEmpty()) return track

            val normalizedArtist = canonicalName(artist)
            val normalizedPrefix = canonicalName(possibleArtist)

            return if (normalizedArtist.isNotEmpty() && normalizedPrefix.isNotEmpty() &&
                (normalizedArtist == normalizedPrefix ||
                 normalizedArtist.startsWith(normalizedPrefix) ||
                 normalizedPrefix.startsWith(normalizedArtist))) {
                songPortion
            } else {
                track
            }
        }

        private fun canonicalName(value: String): String {
            return value
                .lowercase()
                .replace(Regex("[^a-z0-9]"), "")
        }

        private fun normalizeCommonNoise(input: String): String {
            return input
                .replace(noisePattern, "")
                .replace(bracketContent, "")
                .replace(Regex("(?i)vevo"), "")
                .trim()
                .replace(multipleSpaces, " ")
        }

        private fun normalizeLines(response: LyricsResponseDto): List<SyncedLyricLine> {
            val rich = response.lyrics
                .map { it.toDomain() }
                .filter { it.words.isNotBlank() }

            if (rich.isNotEmpty()) {
                return rich
            }

            val fallbackText = response.text?.takeIf { it.isNotBlank() }
                ?: return emptyList()

            return fallbackText.split('\n')
                .mapIndexed { index, words ->
                    SyncedLyricLine(
                        startTimeMs = (index * 2000L),
                        durationMs = 2000L,
                        words = words.trim(),
                        translation = null,
                        romanization = null,
                        parts = emptyList()
                    )
                }
                .filter { it.words.isNotEmpty() }
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
    }
}

data class SyncedLyrics(
    val lines: List<SyncedLyricLine>,
    val sourceLabel: String,
    val sourceUrl: String?
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
