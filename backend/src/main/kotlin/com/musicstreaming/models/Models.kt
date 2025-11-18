package com.musicstreaming.models

import kotlinx.serialization.Serializable

@Serializable
data class Room(
    val id: Int? = null,
    val roomCode: String,
    val roomName: String,
    val vibe: String? = null,
    val capacity: Int? = null,
    val hostName: String? = null,
    val createdAt: String? = null,
    val isActive: Boolean = true,
    val memberCount: Int = 0
)

@Serializable
data class CreateRoomRequest(
    val roomName: String,
    val vibe: String? = null,
    val capacity: Int? = null,
    val hostName: String? = null
)

@Serializable
data class CreateRoomResponse(
    val success: Boolean,
    val roomCode: String,
    val room: Room
)

@Serializable
data class JoinRoomRequest(
    val roomCode: String,
    val userName: String
)

@Serializable
data class JoinRoomResponse(
    val success: Boolean,
    val room: Room,
    val message: String
)

@Serializable
data class Song(
    val id: Int? = null,
    val songTitle: String,
    val artistName: String,
    val votes: Int = 0,
    val addedBy: String
)

@Serializable
data class AddSongRequest(
    val roomCode: String,
    val songTitle: String,
    val artistName: String,
    val addedBy: String
)
