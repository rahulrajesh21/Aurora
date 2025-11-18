package com.example.music_room.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.music_room.R
import com.example.music_room.data.remote.model.TrackDto

class QueueAdapter(
    private val onVote: (position: Int) -> Unit,
    private val onRemove: (position: Int) -> Unit
) : RecyclerView.Adapter<QueueAdapter.QueueViewHolder>() {

    private val tracks = mutableListOf<TrackDto>()

    fun submitList(items: List<TrackDto>) {
        tracks.clear()
        tracks.addAll(items)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QueueViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_queue_song, parent, false)
        return QueueViewHolder(view)
    }

    override fun onBindViewHolder(holder: QueueViewHolder, position: Int) {
        val track = tracks[position]
        holder.bind(track, position)
        holder.voteButton.setOnClickListener { onVote(position) }
        holder.itemView.setOnLongClickListener {
            onRemove(position)
            true
        }
    }

    override fun getItemCount(): Int = tracks.size

    class QueueViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val songImage: ImageView = view.findViewById(R.id.songImage)
        private val songTitle: TextView = view.findViewById(R.id.songTitle)
        private val songArtist: TextView = view.findViewById(R.id.songArtist)
        private val voteCount: TextView = view.findViewById(R.id.voteCount)
        val voteButton: View = view.findViewById(R.id.voteButton)

        fun bind(track: TrackDto, position: Int) {
            songTitle.text = track.title
            songArtist.text = track.artist
            voteCount.text = (position + 1).toString()
            if (!track.thumbnailUrl.isNullOrBlank()) {
                songImage.load(track.thumbnailUrl) {
                    placeholder(R.drawable.album_placeholder)
                    error(R.drawable.album_placeholder)
                }
            } else {
                songImage.setImageResource(R.drawable.album_placeholder)
            }
        }
    }
}
