package com.example.music_room.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

/**
 * Manager for controlling the background media playback service
 */
class MediaServiceManager private constructor(private val context: Context) {
    
    companion object {
        @Volatile
        private var INSTANCE: MediaServiceManager? = null
        
        fun getInstance(context: Context): MediaServiceManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MediaServiceManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    private var mediaControllerFuture: ListenableFuture<MediaController>? = null
    private var serviceConnection: ServiceConnection? = null
    private var boundService: MediaPlaybackService? = null

    /**
     * Start background playback service for a room
     */
    fun startPlaybackService(roomId: String) {
        val serviceIntent = Intent(context, MediaPlaybackService::class.java)
        context.startForegroundService(serviceIntent)
        
        // Bind to service to get direct access
        bindToService(roomId)
        
        // Also create media controller for system integration
        createMediaController()
    }

    /**
     * Stop background playback service
     */
    fun stopPlaybackService() {
        // Stop playback in the service first
        boundService?.stopPlayback()
        
        unbindFromService()
        
        mediaControllerFuture?.let { future ->
            if (!future.isDone) {
                future.cancel(true)
            } else {
                try {
                    future.get()?.release()
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }
        mediaControllerFuture = null
        
        val serviceIntent = Intent(context, MediaPlaybackService::class.java)
        context.stopService(serviceIntent)
    }

    /**
     * Check if service is currently running
     */
    fun isServiceRunning(): Boolean {
        return boundService != null
    }

    /**
     * Get media controller for system integration
     */
    fun getMediaController(): ListenableFuture<MediaController>? {
        return mediaControllerFuture
    }

    private fun bindToService(roomId: String) {
        if (serviceConnection != null) return
        
        serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val binder = service as? MediaPlaybackService.LocalBinder
                boundService = binder?.getService()
                boundService?.startPlaybackForRoom(roomId)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                boundService = null
            }
        }
        
        val serviceIntent = Intent(context, MediaPlaybackService::class.java).apply {
            action = "LOCAL_BIND"
        }
        context.bindService(serviceIntent, serviceConnection!!, Context.BIND_AUTO_CREATE)
    }

    private fun unbindFromService() {
        serviceConnection?.let { connection ->
            try {
                context.unbindService(connection)
            } catch (e: Exception) {
                // Ignore
            }
        }
        serviceConnection = null
        boundService = null
    }

    private fun createMediaController() {
        val sessionToken = SessionToken(context, ComponentName(context, MediaPlaybackService::class.java))
        mediaControllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
    }
}