package com.musicstreaming

import com.musicstreaming.api.configureRouting
import com.musicstreaming.config.ConfigurationException
import com.musicstreaming.database.DatabaseConfig
import com.musicstreaming.di.ServiceContainer
import com.musicstreaming.routes.roomRoutes
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import kotlinx.serialization.json.Json
import java.time.Duration
import kotlin.system.exitProcess

fun main() {
    try {
        embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
            .start(wait = true)
    } catch (e: ConfigurationException) {
        System.err.println("Configuration error: ${e.message}")
        System.err.println("\nPlease ensure all required configuration is set.")
        exitProcess(1)
    } catch (e: Exception) {
        System.err.println("Failed to start application: ${e.message}")
        e.printStackTrace()
        exitProcess(1)
    }
}

fun Application.module() {
    try {
        // Initialize NeonDB database connection
        DatabaseConfig.init()
        log.info("Database initialized successfully")
        
        // Initialize dependency injection container
        // This will load and validate configuration
        val container = ServiceContainer.initialize(environment)
        val config = container.getConfig()
        
        // Log configuration summary
        log.info("Configuration loaded successfully:")
        log.info("  - YouTube API configured: ${config.youtube.apiKey.isNotEmpty()}")
        log.info("  - Queue max size: ${config.queue.maxSize}")
        log.info("  - Queue persistence: ${config.queue.persistenceEnabled}")
        log.info("  - Playback retry attempts: ${config.playback.retryAttempts}")
        log.info("  - State persistence: ${config.state.persistenceEnabled}")
        
    } catch (e: ConfigurationException) {
        log.error("Configuration validation failed: ${e.message}")
        throw e
    }
    
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
        })
    }
    
    install(CORS) {
        anyHost()
        allowHeader("Content-Type")
        allowMethod(io.ktor.http.HttpMethod.Get)
        allowMethod(io.ktor.http.HttpMethod.Post)
        allowMethod(io.ktor.http.HttpMethod.Put)
        allowMethod(io.ktor.http.HttpMethod.Delete)
        allowMethod(io.ktor.http.HttpMethod.Options)
    }
    
    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(15)
        timeout = Duration.ofSeconds(15)
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }
    
    // Configure API routes
    routing {
        configureRouting()
        roomRoutes()  // Add room management routes
    }
}
