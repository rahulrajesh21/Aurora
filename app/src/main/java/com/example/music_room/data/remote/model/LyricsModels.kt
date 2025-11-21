package com.example.music_room.data.remote.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class LyricsRequestDto(
    @Json(name = "song") val song: String,
    @Json(name = "artist") val artist: String,
    @Json(name = "videoId") val videoId: String,
    @Json(name = "durationMs") val durationMs: Long,
    @Json(name = "album") val album: String? = null,
    @Json(name = "audioTrackData") val audioTrackData: AudioTrackDataDto? = null,
    @Json(name = "youtubeLyricsText") val youtubeLyricsText: String? = null,
    @Json(name = "youtubeLyricsSource") val youtubeLyricsSource: String? = null
)

@JsonClass(generateAdapter = true)
data class AudioTrackDataDto(
    @Json(name = "captionTracks") val captionTracks: List<CaptionTrackDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class CaptionTrackDto(
    @Json(name = "languageCode") val languageCode: String,
    @Json(name = "languageName") val languageName: String,
    @Json(name = "kind") val kind: String?,
    @Json(name = "name") val name: String?,
    @Json(name = "displayName") val displayName: String,
    @Json(name = "id") val id: String? = null,
    @Json(name = "isTranslateable") val isTranslateable: Boolean = false,
    @Json(name = "url") val url: String,
    @Json(name = "vssId") val vssId: String? = null,
    @Json(name = "isDefault") val isDefault: Boolean = false,
    @Json(name = "translationLanguage") val translationLanguage: String? = null,
    @Json(name = "captionId") val captionId: String? = null
)

@JsonClass(generateAdapter = true)
data class LyricsResponseDto(
    @Json(name = "song") val song: String,
    @Json(name = "artist") val artist: String,
    @Json(name = "album") val album: String?,
    @Json(name = "durationMs") val durationMs: Long,
    @Json(name = "videoId") val videoId: String,
    @Json(name = "source") val source: String?,
    @Json(name = "sourceHref") val sourceHref: String?,
    @Json(name = "lyrics") val lyrics: List<LyricLineDto> = emptyList(),
    @Json(name = "language") val language: String? = null,
    @Json(name = "musicVideoSynced") val musicVideoSynced: Boolean? = null,
    @Json(name = "cacheAllowed") val cacheAllowed: Boolean? = null,
    @Json(name = "text") val text: String? = null,
    @Json(name = "segmentMap") val segmentMap: SegmentMapDto? = null
)

@JsonClass(generateAdapter = true)
data class SegmentMapDto(
    @Json(name = "primaryVideoId") val primaryVideoId: String,
    @Json(name = "counterpartVideoId") val counterpartVideoId: String,
    @Json(name = "primaryVideoStartTimeMilliseconds") val primaryVideoStartTimeMilliseconds: Long,
    @Json(name = "counterpartVideoStartTimeMilliseconds") val counterpartVideoStartTimeMilliseconds: Long,
    @Json(name = "durationMilliseconds") val durationMilliseconds: Long
)

@JsonClass(generateAdapter = true)
data class LyricLineDto(
    @Json(name = "startTimeMs") val startTimeMs: Long,
    @Json(name = "durationMs") val durationMs: Long,
    @Json(name = "words") val words: String,
    @Json(name = "parts") val parts: List<LyricPartDto>?,
    @Json(name = "translation") val translation: TranslationDto?,
    @Json(name = "romanization") val romanization: String? = null,
    @Json(name = "timedRomanization") val timedRomanization: List<LyricPartDto>? = null
)

@JsonClass(generateAdapter = true)
data class LyricPartDto(
    @Json(name = "startTimeMs") val startTimeMs: Long,
    @Json(name = "durationMs") val durationMs: Long,
    @Json(name = "words") val words: String,
    @Json(name = "isBackground") val isBackground: Boolean? = null
)

@JsonClass(generateAdapter = true)
data class TranslationDto(
    @Json(name = "text") val text: String,
    @Json(name = "lang") val lang: String
)
