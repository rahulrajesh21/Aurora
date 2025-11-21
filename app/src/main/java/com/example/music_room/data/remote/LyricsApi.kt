package com.example.music_room.data.remote

import com.example.music_room.data.remote.model.LyricsRequestDto
import com.example.music_room.data.remote.model.LyricsResponseDto
import retrofit2.http.Body
import retrofit2.http.POST

interface LyricsApi {
    @POST("api/lyrics")
    suspend fun fetchLyrics(
        @Body request: LyricsRequestDto
    ): LyricsResponseDto
}
