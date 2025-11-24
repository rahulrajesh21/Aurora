package com.example.music_room.utils

import android.os.Handler
import android.os.Looper

/**
 * PlaybackTickerManager - Manages periodic playback position updates
 * 
 * Extracted from PlayerActivity and RoomDetailActivity to eliminate duplication.
 * Supports adaptive update intervals based on lyrics sync type.
 */
class PlaybackTickerManager(
    private val onTick: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            onTick()
            // Schedule next tick if still active
            if (isScheduled) {
                handler.postDelayed(this, currentInterval)
            }
        }
    }
    
    private var isScheduled = false
    private var currentInterval = DEFAULT_INTERVAL_MS
    
    /**
     * Start the ticker with the specified interval.
     * 
     * @param intervalMs Update interval in milliseconds
     *                   - 16ms for smooth 60fps animations
     *                   - 100ms for rich-sync word-level karaoke
     *                   - 1000ms for line-level sync or no lyrics
     */
    fun start(intervalMs: Long = DEFAULT_INTERVAL_MS) {
        stop() // Ensure no duplicate callbacks
        currentInterval = intervalMs
        isScheduled = true
        handler.postDelayed(ticker, intervalMs)
    }
    
    /**
     * Stop the ticker and remove all pending callbacks.
     */
    fun stop() {
        isScheduled = false
        handler.removeCallbacks(ticker)
    }
    
    /**
     * Check if the ticker is currently active.
     */
    fun isActive(): Boolean = isScheduled
    
    companion object {
        const val INTERVAL_60FPS = 16L        // ~60fps for smooth animations
        const val INTERVAL_RICH_SYNC = 100L   // Word-level karaoke timing
        const val INTERVAL_LINE_SYNC = 1000L  // Line-level sync timing
        private const val DEFAULT_INTERVAL_MS = INTERVAL_60FPS
    }
}
