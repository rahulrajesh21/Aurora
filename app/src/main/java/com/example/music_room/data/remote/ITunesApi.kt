package com.example.music_room.data.remote

import com.example.music_room.data.remote.model.AppleMusicFeedResponse
import com.example.music_room.data.remote.model.ITunesSearchResponse
import retrofit2.http.GET
import retrofit2.http.Query

interface ITunesApi {
    @GET("search?entity=album&limit=1")
    suspend fun searchAlbums(@Query("term") term: String): ITunesSearchResponse

    @GET("https://rss.applemarketingtools.com/api/v2/us/music/most-played/50/albums.json")
    suspend fun getTopAlbums(): AppleMusicFeedResponse
}
