package com.musicstreaming.database

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant

object Rooms : IntIdTable() {
    val roomCode = varchar("room_code", 6).uniqueIndex()
    val roomName = varchar("room_name", 100)
    val vibe = text("vibe").nullable()
    val capacity = integer("capacity").nullable()
    val hostName = varchar("host_name", 100).nullable()
    val createdAt = timestamp("created_at").default(Instant.now())
    val isActive = bool("is_active").default(true)
}

object RoomMembers : IntIdTable() {
    val roomId = reference("room_id", Rooms)
    val userName = varchar("user_name", 100)
    val joinedAt = timestamp("joined_at").default(Instant.now())
}

object RoomQueue : IntIdTable() {
    val roomId = reference("room_id", Rooms)
    val songTitle = varchar("song_title", 200)
    val artistName = varchar("artist_name", 200)
    val votes = integer("votes").default(0)
    val addedBy = varchar("added_by", 100)
    val addedAt = timestamp("added_at").default(Instant.now())
}
