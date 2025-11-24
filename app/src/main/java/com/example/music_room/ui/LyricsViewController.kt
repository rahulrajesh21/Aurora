package com.example.music_room.ui

import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.example.music_room.ui.manager.LyricsState

/**
 * LyricsViewController - Manages lyrics UI state and visibility
 * 
 * Extracted from PlayerActivity and RoomDetailActivity to eliminate duplication.
 * Handles the 3-part lyrics UI: RecyclerView, loading indicator, and message text.
 */
class LyricsViewController(
    private val lyricsRecyclerView: RecyclerView,
    private val lyricsLoadingView: View,
    private val lyricsMessageView: TextView
) {
    
    /**
     * Update the UI based on lyrics state.
     */
    fun handleLyricsState(state: LyricsState) {
        when (state) {
            is LyricsState.Loading -> {
                lyricsRecyclerView.isVisible = false
                lyricsLoadingView.isVisible = true
                lyricsMessageView.isVisible = false
            }
            is LyricsState.Success -> {
                lyricsRecyclerView.isVisible = true
                lyricsLoadingView.isVisible = false
                lyricsMessageView.isVisible = false
            }
            is LyricsState.Error -> {
                lyricsRecyclerView.isVisible = false
                lyricsLoadingView.isVisible = false
                lyricsMessageView.isVisible = true
                lyricsMessageView.text = state.message
            }
            is LyricsState.Hidden -> {
                lyricsRecyclerView.isVisible = false
                lyricsLoadingView.isVisible = false
                lyricsMessageView.isVisible = false
            }
        }
    }
    
    /**
     * Hide all lyrics UI elements.
     */
    fun hideAll() {
        lyricsRecyclerView.isVisible = false
        lyricsLoadingView.isVisible = false
        lyricsMessageView.isVisible = false
    }
    
    /**
     * Show only the lyrics list.
     */
    fun showLyrics() {
        lyricsRecyclerView.isVisible = true
        lyricsLoadingView.isVisible = false
        lyricsMessageView.isVisible = false
    }
}
