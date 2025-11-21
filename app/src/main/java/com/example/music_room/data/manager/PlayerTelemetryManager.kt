package com.example.music_room.data.manager

import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import com.example.music_room.data.remote.model.PlayerTelemetryState
import com.example.music_room.data.remote.model.TrackDto
import com.example.music_room.data.socket.PlaybackSocketClient

class PlayerTelemetryManager(
    private val socketClient: PlaybackSocketClient
) {
    private var player: Player? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isTracking = false
    private var currentTrack: TrackDto? = null

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!isTracking || player == null) return
            
            sendTelemetry()
            handler.postDelayed(this, 20)
        }
    }

    fun attachPlayer(player: Player) {
        this.player = player
        startTracking()
    }

    fun detachPlayer() {
        stopTracking()
        this.player = null
    }
    
    fun updateCurrentTrack(track: TrackDto?) {
        this.currentTrack = track
    }

    private fun startTracking() {
        if (isTracking) return
        isTracking = true
        handler.post(tickRunnable)
    }

    private fun stopTracking() {
        isTracking = false
        handler.removeCallbacks(tickRunnable)
    }

    private fun sendTelemetry() {
        val p = player ?: return
        val track = currentTrack ?: return 

        val state = PlayerTelemetryState(
            currentTime = p.currentPosition,
            trackId = track.id,
            song = track.title,
            artist = track.artist,
            duration = p.duration,
            isPlaying = p.isPlaying,
            isBuffering = p.playbackState == Player.STATE_BUFFERING,
            browserTime = System.currentTimeMillis()
        )
        
        socketClient.sendPlayerTick(state)
    }
}
