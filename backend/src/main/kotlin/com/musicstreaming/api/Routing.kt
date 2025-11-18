package com.musicstreaming.api

import io.ktor.server.application.*
import io.ktor.server.routing.*

/**
 * Configure all API routes for the application
 */
fun Application.configureRouting() {
    routing {
        searchRoutes()
        playbackRoutes()
        queueRoutes()
        webSocketRoutes()
    }
}
