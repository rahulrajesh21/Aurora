package com.musicstreaming.routes

import com.musicstreaming.database.RoomMembers
import com.musicstreaming.database.RoomQueue
import com.musicstreaming.database.Rooms
import com.musicstreaming.models.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.random.Random

fun Route.roomRoutes() {
    route("/rooms") {
        // Create a new room
        post("/create") {
            val request = call.receive<CreateRoomRequest>()
            
            val roomCode = generateRoomCode()
            
            val roomId = transaction {
                Rooms.insert {
                    it[Rooms.roomCode] = roomCode
                    it[roomName] = request.roomName
                    it[vibe] = request.vibe
                    it[capacity] = request.capacity
                    it[hostName] = request.hostName
                } get Rooms.id
            }
            
            val room = transaction {
                Rooms.select { Rooms.id eq roomId }
                    .map { it.toRoom() }
                    .first()
            }
            
            call.respond(
                HttpStatusCode.Created,
                CreateRoomResponse(success = true, roomCode = roomCode, room = room)
            )
        }
        
        // Join a room
        post("/join") {
            val request = call.receive<JoinRoomRequest>()
            
            val room = transaction {
                Rooms.select { Rooms.roomCode eq request.roomCode }
                    .map { it.toRoom() }
                    .firstOrNull()
            }
            
            if (room == null) {
                call.respond(
                    HttpStatusCode.NotFound,
                    JoinRoomResponse(success = false, room = null, message = "Room not found")
                )
                return@post
            }
            
            if (!room.isActive) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    JoinRoomResponse(success = false, room = room, message = "Room is no longer active")
                )
                return@post
            }
            
            // Check capacity
            val memberCount = transaction {
                RoomMembers.select { RoomMembers.roomId eq room.id!! }.count()
            }
            
            if (room.capacity != null && memberCount >= room.capacity) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    JoinRoomResponse(success = false, room = room, message = "Room is full")
                )
                return@post
            }
            
            // Add member
            transaction {
                RoomMembers.insert {
                    it[roomId] = room.id!!
                    it[userName] = request.userName
                }
            }
            
            call.respond(
                HttpStatusCode.OK,
                JoinRoomResponse(success = true, room = room, message = "Successfully joined room")
            )
        }
        
        // Get room by code
        get("/{code}") {
            val code = call.parameters["code"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            
            val room = transaction {
                Rooms.select { Rooms.roomCode eq code }
                    .map { it.toRoom() }
                    .firstOrNull()
            }
            
            if (room == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Room not found"))
            } else {
                call.respond(room)
            }
        }
        
        // Get active rooms
        get {
            val rooms = transaction {
                Rooms.select { Rooms.isActive eq true }
                    .orderBy(Rooms.createdAt, SortOrder.DESC)
                    .map { it.toRoom() }
            }
            call.respond(rooms)
        }
        
        // Add song to queue
        post("/queue/add") {
            val request = call.receive<AddSongRequest>()
            
            val room = transaction {
                Rooms.select { Rooms.roomCode eq request.roomCode }
                    .map { it.toRoom() }
                    .firstOrNull()
            }
            
            if (room == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Room not found"))
                return@post
            }
            
            transaction {
                RoomQueue.insert {
                    it[roomId] = room.id!!
                    it[songTitle] = request.songTitle
                    it[artistName] = request.artistName
                    it[addedBy] = request.addedBy
                }
            }
            
            call.respond(HttpStatusCode.Created, mapOf("success" to true))
        }
        
        // Get room queue
        get("/{code}/queue") {
            val code = call.parameters["code"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            
            val room = transaction {
                Rooms.select { Rooms.roomCode eq code }
                    .map { it.toRoom() }
                    .firstOrNull()
            }
            
            if (room == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Room not found"))
                return@get
            }
            
            val queue = transaction {
                RoomQueue.select { RoomQueue.roomId eq room.id!! }
                    .orderBy(RoomQueue.votes, SortOrder.DESC)
                    .map { it.toSong() }
            }
            
            call.respond(queue)
        }
    }
}

private fun generateRoomCode(): String {
    val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    return (1..6)
        .map { chars[Random.nextInt(chars.length)] }
        .joinToString("")
}

private fun ResultRow.toRoom() = Room(
    id = this[Rooms.id].value,
    roomCode = this[Rooms.roomCode],
    roomName = this[Rooms.roomName],
    vibe = this[Rooms.vibe],
    capacity = this[Rooms.capacity],
    hostName = this[Rooms.hostName],
    createdAt = this[Rooms.createdAt].toString(),
    isActive = this[Rooms.isActive]
)

private fun ResultRow.toSong() = Song(
    id = this[RoomQueue.id].value,
    songTitle = this[RoomQueue.songTitle],
    artistName = this[RoomQueue.artistName],
    votes = this[RoomQueue.votes],
    addedBy = this[RoomQueue.addedBy]
)
