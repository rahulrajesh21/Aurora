package com.example.music_room.data.remote

import com.example.music_room.data.remote.model.ITunesSearchResponse
import retrofit2.http.GET
import retrofit2.http.Query

interface ITunesApi {
    @GET("search")
    suspend fun searchAlbums(
        @Query("term") term: String,
        @Query("entity") entity: String = "album",
        @Query("limit") limit: Int = 1
    ): ITunesSearchResponse
}
