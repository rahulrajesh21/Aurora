package com.musicstreaming.api

import com.musicstreaming.di.ServiceContainer
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.*

private val logger = LoggerFactory.getLogger("WebSocketRoutes")

/**
 * Configure WebSocket routes for real-time playback state updates
 * Requirement 8.4: WebSocket endpoint for real-time updates
 * Requirement 9.4: Connection management
 */
fun Route.webSocketRoutes() {
    val webSocketManager = ServiceContainer.getInstance().webSocketManager
    val streamingService = ServiceContainer.getInstance().streamingService
    val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
    }
    
    // WS /api/playback/stream - Real-time playback state updates
    webSocket("/api/playback/stream") {
        val sessionId = UUID.randomUUID().toString()
        
        try {
            // Register the connection
            webSocketManager.addConnection(sessionId, this)
            logger.info("WebSocket client connected: $sessionId")
            
            // Send initial state to the newly connected client
            try {
                val currentState = streamingService.getState()
                val stateJson = json.encodeToString(currentState)
                send(Frame.Text(stateJson))
                logger.info("Initial state sent to client: $sessionId")
            } catch (e: Exception) {
                logger.error("Failed to send initial state to client $sessionId: ${e.message}", e)
            }
            
            // Keep the connection alive and handle incoming messages
            for (frame in incoming) {
                when (frame) {
                    is Frame.Text -> {
                        val text = frame.readText()
                        logger.debug("Received message from client $sessionId: $text")
                        
                        // Handle ping/pong or other client messages if needed
                        if (text == "ping") {
                            send(Frame.Text("pong"))
                        }
                    }
                    is Frame.Close -> {
                        logger.info("Client $sessionId requested close")
                        break
                    }
                    else -> {
                        logger.debug("Received non-text frame from client $sessionId")
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("WebSocket error for client $sessionId: ${e.message}", e)
        } finally {
            // Clean up the connection
            webSocketManager.removeConnection(sessionId)
            logger.info("WebSocket client disconnected: $sessionId")
        }
    }
}
