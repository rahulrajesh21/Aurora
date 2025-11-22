package com.example.music_room.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import com.example.music_room.R
import com.example.music_room.data.repository.SyncedLyricLine

class LyricsAdapter : RecyclerView.Adapter<LyricsAdapter.LyricViewHolder>() {

	private var lines: List<SyncedLyricLine> = emptyList()
	private var activeIndex: Int = -1

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
			holder.bind(line, isActive, textColor, secondaryTextColor)
		}

	override fun getItemCount(): Int = lines.size

	fun submitLines(newLines: List<SyncedLyricLine>) {
		lines = newLines
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

	fun updatePlaybackPosition(positionMs: Long): Int {
		if (lines.isEmpty()) return -1

		val clampedPosition = positionMs.coerceAtLeast(0L)

		var targetIndex = when {
			activeIndex in lines.indices -> activeIndex
			else -> findIndexByStart(clampedPosition)
		}

		if (targetIndex == -1) {
			targetIndex = 0
		}

		while (targetIndex + 1 < lines.size && clampedPosition >= lineEnd(targetIndex)) {
			targetIndex += 1
		}
		while (targetIndex > 0 && clampedPosition < lines[targetIndex].startTimeMs) {
			targetIndex -= 1
		}

		if (targetIndex == activeIndex) {
			return activeIndex
		}

		val previous = activeIndex
		activeIndex = targetIndex
		if (previous in lines.indices) {
			notifyItemChanged(previous)
		}
		notifyItemChanged(activeIndex)
		return activeIndex
	}

	private fun findIndexByStart(positionMs: Long): Int {
		for (i in lines.indices.reversed()) {
			if (positionMs >= lines[i].startTimeMs) {
				return i
			}
		}
		return -1
	}

	private fun lineEnd(index: Int): Long {
		val line = lines[index]
		val duration = line.durationMs.takeIf { it > 0 } ?: 1000L
		return line.startTimeMs + duration
	}

	class LyricViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
		private val lyricText: TextView = itemView.findViewById(R.id.lyricText)
		private val translationText: TextView = itemView.findViewById(R.id.translationText)

		fun bind(line: SyncedLyricLine, active: Boolean, color: Int, secondaryColor: Int) {
			lyricText.text = line.words.ifBlank { "\u266a" }
			lyricText.setTextColor(color)
            
            val supplemental = line.translation ?: line.romanization
            if (supplemental.isNullOrBlank()) {
                translationText.visibility = View.GONE
            } else {
                translationText.visibility = View.VISIBLE
                translationText.text = supplemental
				translationText.setTextColor(secondaryColor)
            }

			val targetAlpha = if (active) 1f else 0.4f
			lyricText.alpha = targetAlpha
			translationText.alpha = targetAlpha * 0.85f
			lyricText.textSize = if (active) 22f else 18f
		}
	}
}
