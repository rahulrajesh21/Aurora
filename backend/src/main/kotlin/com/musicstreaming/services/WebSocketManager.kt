package com.musicstreaming.services

import com.musicstreaming.models.PlaybackState
import io.ktor.websocket.*
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages WebSocket connections and broadcasts playback state updates
 * Requirements: 8.4, 9.4, 3.4
 */
class WebSocketManager {
    private val logger = LoggerFactory.getLogger(WebSocketManager::class.java)
    private val connections = ConcurrentHashMap<String, WebSocketSession>()
    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
    }

    /**
     * Register a new WebSocket connection
     * @param sessionId Unique identifier for the session
     * @param session The WebSocket session
     */
    fun addConnection(sessionId: String, session: WebSocketSession) {
        connections[sessionId] = session
        logger.info("WebSocket connection added: $sessionId (total: ${connections.size})")
    }

    /**
     * Remove a WebSocket connection
     * @param sessionId Unique identifier for the session
     */
    fun removeConnection(sessionId: String) {
        connections.remove(sessionId)
        logger.info("WebSocket connection removed: $sessionId (total: ${connections.size})")
    }

    /**
     * Broadcast playback state to all connected clients
     * Requirement 3.4: Send updates within 500ms of state change
     * Requirement 8.4: Broadcast on all state changes
     * Requirement 9.4: Handle client disconnections gracefully
     * @param state The playback state to broadcast
     */
    suspend fun broadcastState(state: PlaybackState) {
        if (connections.isEmpty()) {
            logger.debug("No WebSocket connections to broadcast to")
            return
        }

        val stateJson = json.encodeToString(state)
        val disconnectedSessions = mutableListOf<String>()

        logger.debug("Broadcasting state to ${connections.size} connections")

        connections.forEach { (sessionId, session) ->
            try {
                session.send(Frame.Text(stateJson))
                logger.debug("State broadcasted to session: $sessionId")
            } catch (e: ClosedSendChannelException) {
                logger.warn("Failed to send to session $sessionId: connection closed")
                disconnectedSessions.add(sessionId)
            } catch (e: Exception) {
                logger.error("Error broadcasting to session $sessionId: ${e.message}", e)
                disconnectedSessions.add(sessionId)
            }
        }

        // Clean up disconnected sessions
        disconnectedSessions.forEach { sessionId ->
            removeConnection(sessionId)
        }
    }

    /**
     * Get the number of active connections
     */
    fun getConnectionCount(): Int = connections.size
}
