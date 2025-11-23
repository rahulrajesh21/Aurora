package com.example.music_room.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.size.Precision
import coil.size.Scale
import com.example.music_room.R
import com.example.music_room.data.remote.model.TrackDto
import com.example.music_room.databinding.ItemQueueCarouselBinding

class TrackCarouselAdapter(
    private val onTrackClick: (TrackDto, Int) -> Unit
) : RecyclerView.Adapter<TrackCarouselAdapter.ViewHolder>() {

    private var items: List<TrackDto> = emptyList()
    private var isPlaying: Boolean = false

    fun submitList(newItems: List<TrackDto>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun setPlayingState(playing: Boolean) {
        if (isPlaying != playing) {
            isPlaying = playing
            // Only the current track (index 0) visual might change based on playing state
            if (items.isNotEmpty()) {
                notifyItemChanged(0)
            }
        }
    }

    fun getItem(position: Int): TrackDto? = items.getOrNull(position)

    fun indexOf(trackId: String): Int = items.indexOfFirst { it.id == trackId }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemQueueCarouselBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val track = items.getOrNull(position) ?: return
        holder.bind(track, position)
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemQueueCarouselBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(track: TrackDto, position: Int) {
            // Load art using Coil
            binding.albumArt.load(track.thumbnailUrl) {
                placeholder(R.drawable.album_placeholder)
                error(R.drawable.album_placeholder)
                precision(Precision.EXACT)
                scale(Scale.FILL)
                crossfade(true)
            }

            // Visual indication for current track state (optional, based on existing logic)
            if (position == 0) {
                binding.albumArt.alpha = if (isPlaying) 1.0f else 0.5f
            } else {
                binding.albumArt.alpha = 1.0f
            }

            binding.root.setOnClickListener {
                onTrackClick(track, position)
            }
        }
    }
}
