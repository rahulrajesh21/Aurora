package com.example.music_room.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.music_room.data.AuroraServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Handles media control actions from notification buttons
 */
class MediaActionReceiver : BroadcastReceiver() {
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repository = AuroraServiceLocator.repository

    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.getStringExtra("action") ?: return
        val roomId = intent.getStringExtra("roomId") ?: return
        
        scope.launch {
            try {
                when (action) {
                    "PLAY" -> repository.resume(roomId)
                    "PAUSE" -> repository.pause(roomId, null)
                    "NEXT" -> repository.skip(roomId)
                    "PREVIOUS" -> repository.previous(roomId)
                }
            } catch (e: Exception) {
                android.util.Log.e("MediaActionReceiver", "Failed to handle action $action: ${e.message}")
            }
        }
    }
}