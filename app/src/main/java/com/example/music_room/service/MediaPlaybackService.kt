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
import com.example.music_room.data.manager.StreamPrefetchCache
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
    private var lastProcessedTimestamp: Long = 0

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
        
        // Start foreground immediately to prevent ANR
        // This is required when service is started with startForegroundService()
        startForeground(NOTIFICATION_ID, createInitialNotification())
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Ensure we're in foreground mode
        startForeground(NOTIFICATION_ID, createInitialNotification())
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
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

        // Configure optimized load control for faster startup and smoother streaming
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                30_000, // Min buffer: 30s
                120_000, // Max buffer: 2 mins
                1_500, // Buffer for playback: 1.5s (reduced for faster start)
                3_000 // Buffer for rebuffer: 3s
            )
            .build()

        // Create ExoPlayer with optimized settings for streaming
        exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setLoadControl(loadControl)
            .build().apply {
                
                // Handle playback events
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        super.onIsPlayingChanged(isPlaying)
                        updateNotification()
                        
                        // Sync with backend when playback state changes locally
                        // But avoid syncing if we are in a transient state (buffering, ended, or idle during transition)
                        val playbackState = exoPlayer?.playbackState
                        
                        // Only sync if:
                        // 1. We are playing (isPlaying = true)
                        // 2. OR we are paused (isPlaying = false) AND it's a deliberate pause (READY state)
                        val shouldSync = isPlaying || (playbackState == Player.STATE_READY)

                        if (shouldSync && isPlaying != (currentState?.isPlaying == true)) {
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

    /**
     * Stop playback and prepare service for shutdown
     */
    fun stopPlayback() {
        // Stop the player
        exoPlayer?.apply {
            stop()
            clearMediaItems()
        }
        
        // Disconnect socket
        playbackSocket?.disconnect()
        currentRoomId = null
        currentState = null
        lastProcessedTimestamp = 0
        
        // Stop foreground and remove notification
        stopForeground(STOP_FOREGROUND_REMOVE)
        
        // Signal service can be stopped
        stopSelf()
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
        android.util.Log.d("MediaService", "handleBackendStateUpdate: isPlaying=${state.isPlaying}, ts=${state.timestamp}, currentTs=$lastProcessedTimestamp")
        
        // Stale state protection
        if (state.timestamp < lastProcessedTimestamp) {
            android.util.Log.w("MediaService", "Ignoring stale state update. State ts: ${state.timestamp}, Last processed: $lastProcessedTimestamp")
            return
        }
        
        lastProcessedTimestamp = state.timestamp
        currentState = state
        
        val player = exoPlayer ?: return
            val backendStreamUrl = state.streamUrl?.let { url ->
                if (url.startsWith("/")) {
                    "${com.example.music_room.BuildConfig.BACKEND_BASE_URL.removeSuffix("/")}$url"
                } else {
                    url
                }
            }
            val prefetched = state.currentTrack?.id?.let { StreamPrefetchCache.consume(it) }
            val streamUrl = prefetched ?: backendStreamUrl
        
        if (!streamUrl.isNullOrBlank()) {
            // Update media item if stream URL changed
            val currentMediaItem = player.currentMediaItem
            val currentUri = currentMediaItem?.localConfiguration?.uri?.toString()
            
            if (currentUri != streamUrl) {
                // New stream URL - need to load new media
                android.util.Log.d("MediaService", "Loading new media item: $streamUrl")
                val mediaItem = MediaItem.fromUri(streamUrl)
                player.setMediaItem(mediaItem)
                player.prepare()
                
                // Set playWhenReady AFTER preparing to ensure smooth playback
                player.playWhenReady = state.isPlaying
            } else {
                // Same stream URL - just update play/pause state
                if (player.playWhenReady != state.isPlaying) {
                     android.util.Log.d("MediaService", "Updating playWhenReady to ${state.isPlaying}")
                     player.playWhenReady = state.isPlaying
                }
                
                // Sync position ONLY if there's a significant difference (>2 seconds)
                // This indicates a user seek action, not just polling drift
                val backendPositionMs = (state.positionSeconds * 1000).toLong()
                val currentPositionMs = player.currentPosition
                val positionDeltaMs = kotlin.math.abs(backendPositionMs - currentPositionMs)
                
                if (positionDeltaMs > 2000 && player.playbackState == Player.STATE_READY) {
                    android.util.Log.d("MediaService", "Seeking to ${state.positionSeconds}s (delta: ${positionDeltaMs}ms)")
                    player.seekTo(backendPositionMs)
                }
            }
            
            updateNotification()
        }
    }
    
    fun seekTo(positionSeconds: Int) {
        val player = exoPlayer ?: return
        val positionMs = (positionSeconds * 1000).toLong()
        player.seekTo(positionMs)
        android.util.Log.d("MediaService", "Seeked to $positionSeconds seconds")
    }

    private suspend fun syncPlaybackStateWithBackend(isPlaying: Boolean) {
        val roomId = currentRoomId ?: return
        
        try {
            if (isPlaying) {
                repository.resume(roomId)
            } else {
                repository.pause(roomId, null)
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
    
    /**
     * Creates an initial notification for foreground service
     * This prevents ANR when service starts before playback state is available
     */
    private fun createInitialNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle("Aurora Music")
            .setContentText("Connecting to room...")
            .setContentIntent(createContentPendingIntent())
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setShowActionsInCompactView()
            )
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun updateNotification() {
        val state = currentState ?: return
        val track = state.currentTrack
        val artistLabel = track?.artist?.takeIf { it.isNotBlank() } ?: track?.artist
        val titleLabel = track?.title ?: "Aurora Music"
        
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
                .setContentTitle(titleLabel)
            .setContentText(artistLabel ?: "No track playing")
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