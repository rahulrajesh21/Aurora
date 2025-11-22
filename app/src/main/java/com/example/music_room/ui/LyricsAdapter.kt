package com.example.music_room.ui

import android.animation.ValueAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import com.example.music_room.R
import com.example.music_room.data.repository.SyncedLyricLine
import com.example.music_room.data.repository.SyncType

/**
 * LyricsAdapter - Better Lyrics Parity Implementation
 * 
 * Mimics Better Lyrics animation engine (animationEngine.ts):
 * - Timing offsets for sync accuracy
 * - Smooth scrolling with anticipation
 * - Alpha transitions for active/inactive states
 * - Support for rich-sync (word-level) display
 */
class LyricsAdapter : RecyclerView.Adapter<LyricsAdapter.LyricViewHolder>() {

	private var lines: List<SyncedLyricLine> = emptyList()
	private var activeIndex: Int = -1
	private var syncType: SyncType = SyncType.LINE_SYNC
	
	// Better Lyrics timing offsets (animationEngine.ts:123-127)
	private val richSyncTimingOffset: Long = 115  // ms - for word-level sync
	private val lineSyncTimingOffset: Long = 20   // ms - for line-level sync

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LyricViewHolder {
		val view = LayoutInflater.from(parent.context).inflate(R.layout.item_lyric_line, parent, false)
		return LyricViewHolder(view)
	}

	private var textColor: Int = android.graphics.Color.WHITE
	private var secondaryTextColor: Int = android.graphics.Color.LTGRAY

    fun setTextColor(color: Int) {
        textColor = color
		secondaryTextColor = ColorUtils.blendARGB(color, android.graphics.Color.WHITE, 0.4f)
        notifyDataSetChanged()
    }

	override fun onBindViewHolder(holder: LyricViewHolder, position: Int) {
		val line = lines[position]
		val isActive = position == activeIndex
		holder.bind(line, isActive, textColor, secondaryTextColor, syncType)
	}

	override fun getItemCount(): Int = lines.size

	/**
	 * Submit new lyrics lines (called when lyrics are loaded)
	 */
	fun submitLines(newLines: List<SyncedLyricLine>, newSyncType: SyncType) {
		lines = newLines
		syncType = newSyncType
		val previous = activeIndex
		activeIndex = if (lines.isNotEmpty()) 0 else -1
		notifyDataSetChanged()
		if (previous != -1 && previous < lines.size) {
			notifyItemChanged(previous)
		}
	}

	fun clear() {
		lines = emptyList()
		activeIndex = -1
		notifyDataSetChanged()
	}

	/**
	 * Update playback position - Better Lyrics style (animationEngine.ts:68-127)
	 * 
	 * Applies timing offset based on sync type, then finds active line
	 */
	fun updatePlaybackPosition(positionMs: Long): Int {
		if (lines.isEmpty()) return -1

		// Apply timing offset based on sync type (like Better Lyrics)
		val offset = when (syncType) {
			SyncType.RICH_SYNC -> richSyncTimingOffset
			SyncType.LINE_SYNC -> lineSyncTimingOffset
			SyncType.NONE -> 0L
		}
		
		val adjustedPosition = (positionMs + offset).coerceAtLeast(0L)

		// Find the active line
		var targetIndex = findActiveLine(adjustedPosition)
		
		if (targetIndex == -1) {
			targetIndex = 0
		}

		// No change, skip update
		if (targetIndex == activeIndex) {
			return activeIndex
		}

		// Update active state with smooth transition
		val previous = activeIndex
		activeIndex = targetIndex
		
		if (previous in lines.indices) {
			notifyItemChanged(previous)
		}
		notifyItemChanged(activeIndex)
		
		return activeIndex
	}

	/**
	 * Find active line based on Better Lyrics logic (animationEngine.ts:129-145)
	 */
	private fun findActiveLine(positionMs: Long): Int {
		// Find the last line that has started
		var activeIdx = -1
		
		for (i in lines.indices) {
			val line = lines[i]
			if (positionMs >= line.startTimeMs) {
				// Check if we're still within this line's duration
				val lineEnd = line.startTimeMs + line.durationMs
				if (positionMs < lineEnd || i == lines.lastIndex) {
					activeIdx = i
				} else if (i + 1 < lines.size) {
					// Check if we've reached the next line
					val nextLine = lines[i + 1]
					if (positionMs < nextLine.startTimeMs) {
						activeIdx = i
						break
					}
				}
			}
		}
		
		return activeIdx
	}

	class LyricViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
		private val lyricText: TextView = itemView.findViewById(R.id.lyricText)
		private val translationText: TextView = itemView.findViewById(R.id.translationText)
		private var currentAnimator: ValueAnimator? = null

		fun bind(
			line: SyncedLyricLine, 
			active: Boolean, 
			color: Int, 
			secondaryColor: Int,
			syncType: SyncType
		) {
			// Display main lyrics (or music note if empty)
			lyricText.text = line.words.ifBlank { "♪" }
			lyricText.setTextColor(color)
            
			// Display translation or romanization (Better Lyrics order: translation first)
            val supplemental = line.translation ?: line.romanization
            if (supplemental.isNullOrBlank()) {
                translationText.visibility = View.GONE
            } else {
                translationText.visibility = View.VISIBLE
                translationText.text = supplemental
				translationText.setTextColor(secondaryColor)
            }

			// Animate transition (Better Lyrics style smooth fade)
			animateActivationState(active)
		}
		
		/**
		 * Animate between active/inactive states
		 * Better Lyrics uses CSS transitions, we use ValueAnimator
		 */
		private fun animateActivationState(active: Boolean) {
			currentAnimator?.cancel()
			
			val targetAlpha = if (active) 1f else 0.4f
			val targetSize = if (active) 22f else 18f
			
			val startAlpha = lyricText.alpha
			val startSize = lyricText.textSize / lyricText.resources.displayMetrics.scaledDensity
			
			currentAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
				duration = 200 // Smooth transition
				addUpdateListener { animator ->
					val fraction = animator.animatedFraction
					
					// Interpolate alpha
					val newAlpha = startAlpha + (targetAlpha - startAlpha) * fraction
					lyricText.alpha = newAlpha
					translationText.alpha = newAlpha * 0.85f
					
					// Interpolate text size
					val newSize = startSize + (targetSize - startSize) * fraction
					lyricText.textSize = newSize
				}
				start()
			}
		}
	}
}
