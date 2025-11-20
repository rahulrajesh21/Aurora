package com.example.music_room.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.music_room.MainActivity
import com.example.music_room.R
import com.example.music_room.data.AuroraServiceLocator
import com.example.music_room.data.remote.model.PlaybackStateDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Background media playback service for Aurora music streaming
 * Handles continuous playback even when the app is in background
 */
class MediaPlaybackService : MediaSessionService() {

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "aurora_playback_channel"
        private const val CHANNEL_NAME = "Aurora Music Playback"
    }

    private var exoPlayer: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val repository = AuroraServiceLocator.repository
    
    private var currentRoomId: String? = null
    private var currentState: PlaybackStateDto? = null
    private var playbackSocket: com.example.music_room.data.socket.PlaybackSocketClient? = null

    /**
     * Binder for local service binding
     */
    inner class LocalBinder : android.os.Binder() {
        fun getService(): MediaPlaybackService = this@MediaPlaybackService
    }

    override fun onCreate() {
        super.onCreate()
        initializePlayer()
        createNotificationChannel()
    }

    override fun onGetSession(controllerInfo: androidx.media3.session.MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onBind(intent: Intent?): IBinder? {
        return if (intent?.action == "LOCAL_BIND") {
            LocalBinder()
        } else {
            super.onBind(intent)
        }
    }

    private fun initializePlayer() {
        // Configure audio attributes for music playback
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        // Create ExoPlayer with optimized settings for streaming
        exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build().apply {
                
                // Handle playback events
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        super.onIsPlayingChanged(isPlaying)
                        updateNotification()
                        
                        // Sync with backend when playback state changes locally
                        if (isPlaying != (currentState?.isPlaying == true)) {
                            serviceScope.launch {
                                syncPlaybackStateWithBackend(isPlaying)
                            }
                        }
                    }

                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        super.onMediaItemTransition(mediaItem, reason)
                        when (reason) {
                            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
                            Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> {
                                // Handle automatic track transitions
                                serviceScope.launch {
                                    handleTrackTransition()
                                }
                            }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        super.onPlayerError(error)
                        // Try to recover or skip to next track
                        serviceScope.launch {
                            handlePlaybackError(error)
                        }
                    }
                })
            }

        // Create media session for system integration
        mediaSession = MediaSession.Builder(this, exoPlayer!!)
            .setCallback(MediaSessionCallback())
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows currently playing music and playback controls"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Start playback for a specific room
     */
    fun startPlaybackForRoom(roomId: String) {
        if (currentRoomId != roomId) {
            // Disconnect from previous room
            playbackSocket?.disconnect()
            
            // Connect to new room
            currentRoomId = roomId
            playbackSocket = AuroraServiceLocator.createPlaybackSocket(roomId)
            
            // Listen for playback state changes from backend
            playbackSocket?.connect(
                onState = { state ->
                    serviceScope.launch {
                        handleBackendStateUpdate(state)
                    }
                },
                onError = { error ->
                    // Handle socket errors
                    android.util.Log.e("MediaService", "Socket error: ${error.message}")
                }
            )
        }
        
        // Refresh current state
        serviceScope.launch {
            refreshPlaybackState()
        }
    }

    private suspend fun refreshPlaybackState() {
        val roomId = currentRoomId ?: return
        repository.getPlaybackState(roomId)
            .onSuccess { state ->
                handleBackendStateUpdate(state)
            }
            .onFailure { 
                android.util.Log.e("MediaService", "Failed to refresh state: ${it.message}")
            }
    }

    private suspend fun handleBackendStateUpdate(state: PlaybackStateDto) {
        currentState = state
        
        val player = exoPlayer ?: return
        val streamUrl = state.streamUrl
        
        if (!streamUrl.isNullOrBlank()) {
            // Update media item if stream URL changed
            val currentMediaItem = player.currentMediaItem
            val currentUri = currentMediaItem?.localConfiguration?.uri?.toString()
            
            if (currentUri != streamUrl) {
                val mediaItem = MediaItem.fromUri(streamUrl)
                player.setMediaItem(mediaItem)
                player.prepare()
            }
            
            // Sync playback state
            player.playWhenReady = state.isPlaying
            
            // Sync position (with some tolerance to avoid constant seeking)
            val currentPosition = player.currentPosition / 1000f
            val backendPosition = state.positionSeconds
            if (kotlin.math.abs(currentPosition - backendPosition) > 2.0f) {
                player.seekTo((backendPosition * 1000L).coerceAtLeast(0L))
            }
            
            updateNotification()
        }
    }

    private suspend fun syncPlaybackStateWithBackend(isPlaying: Boolean) {
        val roomId = currentRoomId ?: return
        
        try {
            if (isPlaying) {
                repository.resume(roomId)
            } else {
                repository.pause(roomId)
            }
        } catch (e: Exception) {
            android.util.Log.e("MediaService", "Failed to sync playback state: ${e.message}")
        }
    }

    private suspend fun handleTrackTransition() {
        val roomId = currentRoomId ?: return
        
        try {
            // Request next track from backend
            repository.skip(roomId).onSuccess { state ->
                handleBackendStateUpdate(state)
            }
        } catch (e: Exception) {
            android.util.Log.e("MediaService", "Failed to handle track transition: ${e.message}")
        }
    }

    private suspend fun handlePlaybackError(error: PlaybackException) {
        android.util.Log.e("MediaService", "Playback error: ${error.message}")
        
        // Try to skip to next track on error
        val roomId = currentRoomId ?: return
        
        try {
            repository.skip(roomId).onSuccess { state ->
                handleBackendStateUpdate(state)
            }
        } catch (e: Exception) {
            android.util.Log.e("MediaService", "Failed to recover from playback error: ${e.message}")
        }
    }

    private fun updateNotification() {
        val state = currentState ?: return
        val track = state.currentTrack
        
        val playPauseAction = if (state.isPlaying) {
            NotificationCompat.Action.Builder(
                R.drawable.ic_pause_large,
                "Pause",
                createMediaActionPendingIntent("PAUSE")
            ).build()
        } else {
            NotificationCompat.Action.Builder(
                R.drawable.ic_play_circle,
                "Play",
                createMediaActionPendingIntent("PLAY")
            ).build()
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(track?.title ?: "Aurora Music")
            .setContentText(track?.artist ?: "No track playing")
            .setContentIntent(createContentPendingIntent())
            .addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_back,
                    "Previous",
                    createMediaActionPendingIntent("PREVIOUS")
                ).build()
            )
            .addAction(playPauseAction)
            .addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_next,
                    "Next", 
                    createMediaActionPendingIntent("NEXT")
                ).build()
            )
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setOngoing(state.isPlaying)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        if (state.isPlaying) {
            startForeground(NOTIFICATION_ID, notification)
        } else {
            stopForeground(STOP_FOREGROUND_DETACH)
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun createContentPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this, 0, intent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createMediaActionPendingIntent(action: String): PendingIntent {
        val intent = Intent(this, MediaActionReceiver::class.java).apply {
            putExtra("action", action)
            putExtra("roomId", currentRoomId)
        }
        return PendingIntent.getBroadcast(
            this, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    override fun onDestroy() {
        playbackSocket?.disconnect()
        serviceScope.cancel()
        mediaSession?.run {
            player.release()
            release()
        }
        super.onDestroy()
    }

    /**
     * Media session callback for handling system media controls
     */
    private inner class MediaSessionCallback : MediaSession.Callback {
        // Media3 handles player commands through the Player interface
        // Custom playback control is handled via broadcast receiver
    }
}