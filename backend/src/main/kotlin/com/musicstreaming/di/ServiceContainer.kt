package com.musicstreaming.di

import com.musicstreaming.adapters.MusicProvider
import com.musicstreaming.adapters.YouTubeMusicProvider
import com.musicstreaming.config.AppConfig
import com.musicstreaming.models.ProviderType
import com.musicstreaming.services.PlaybackEngine
import com.musicstreaming.services.QueueManager
import com.musicstreaming.services.StateManager
import com.musicstreaming.services.StreamingService
import com.musicstreaming.services.WebSocketManager
import com.musicstreaming.storage.FileQueueStorage
import com.musicstreaming.storage.QueueStorage
import io.ktor.server.application.*

/**
 * Simple dependency injection container for the application
 * Manages lifecycle and dependencies of all services
 */
class ServiceContainer(private val environment: ApplicationEnvironment) {
    
    // Load and validate configuration on initialization
    private val appConfig: AppConfig by lazy {
        AppConfig.load(environment)
    }
    
    // Storage
    private val queueStorage: QueueStorage by lazy {
        FileQueueStorage(java.io.File(appConfig.queue.storagePath))
    }
    
    // Providers
    private val providers: Map<ProviderType, MusicProvider> by lazy {
        println("Initializing YouTube provider with API key: ${appConfig.youtube.apiKey.take(10)}...")
        
        mapOf(
            ProviderType.YOUTUBE to YouTubeMusicProvider(appConfig.youtube.apiKey)
        )
    }
    
    // Services
    val queueManager: QueueManager by lazy {
        QueueManager(queueStorage, appConfig.queue.maxSize)
    }
    
    val playbackEngine: PlaybackEngine by lazy {
        PlaybackEngine(providers, queueManager)
    }
    
    val stateManager: StateManager by lazy {
        StateManager()
    }
    
    val webSocketManager: WebSocketManager by lazy {
        WebSocketManager()
    }
    
    val streamingService: StreamingService by lazy {
        StreamingService(providers, queueManager, playbackEngine, stateManager, webSocketManager)
    }
    
    /**
     * Get the loaded application configuration
     */
    fun getConfig(): AppConfig = appConfig
    
    companion object {
        private var instance: ServiceContainer? = null
        
        fun initialize(environment: ApplicationEnvironment): ServiceContainer {
            if (instance == null) {
                instance = ServiceContainer(environment)
            }
            return instance!!
        }
        
        fun getInstance(): ServiceContainer {
            return instance ?: throw IllegalStateException("ServiceContainer not initialized")
        }
    }
}
