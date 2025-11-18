package com.example.music_room.data.remote.model

import com.squareup.moshi.Json

data class ApiErrorResponse(
    @Json(name = "error") val error: String? = null,
    @Json(name = "message") val message: String? = null
)
