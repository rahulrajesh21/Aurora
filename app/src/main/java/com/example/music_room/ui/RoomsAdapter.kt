package com.example.music_room.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.music_room.R
import com.example.music_room.data.remote.model.RoomSnapshotDto
import com.example.music_room.utils.displayArtist
import com.example.music_room.utils.displayTitle

class RoomsAdapter(
    private val onRoomSelected: (RoomSnapshotDto) -> Unit
) : RecyclerView.Adapter<RoomsAdapter.RoomViewHolder>() {

    private val rooms = mutableListOf<RoomSnapshotDto>()

    fun submitList(newRooms: List<RoomSnapshotDto>) {
        rooms.clear()
        rooms.addAll(newRooms)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RoomViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_room, parent, false)
        return RoomViewHolder(view)
    }

    override fun onBindViewHolder(holder: RoomViewHolder, position: Int) {
        val snapshot = rooms[position]
        holder.bind(snapshot)
        holder.itemView.setOnClickListener { onRoomSelected(snapshot) }
    }

    override fun getItemCount(): Int = rooms.size

    class RoomViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val roomName: TextView = view.findViewById(R.id.roomName)
        private val songTitle: TextView = view.findViewById(R.id.songTitle)
        private val artistName: TextView = view.findViewById(R.id.artistName)
        private val memberCount: TextView = view.findViewById(R.id.memberCount)
        private val roomImage: ImageView = view.findViewById(R.id.roomImage)

        fun bind(snapshot: RoomSnapshotDto) {
            roomName.text = snapshot.room.name
            
            val playingTrack = snapshot.nowPlaying?.currentTrack ?: snapshot.nowPlaying?.queue?.firstOrNull()
            
            if (playingTrack != null) {
                songTitle.text = playingTrack.displayTitle()
                val artistLabel = playingTrack.displayArtist().ifBlank { playingTrack.artist }
                artistName.text = artistLabel
                songTitle.visibility = View.VISIBLE
                artistName.visibility = View.VISIBLE
            } else {
                songTitle.text = " " // Maintain height
                songTitle.visibility = View.VISIBLE
                artistName.text = "Nothing playing"
                artistName.visibility = View.VISIBLE
            }

            memberCount.text = snapshot.memberCount.toString()

            // Image Logic
            val vibeName = snapshot.room.description
            val vibeDrawable = getVibeDrawable(vibeName)

            if (vibeDrawable != null) {
                roomImage.setImageResource(vibeDrawable)
                roomImage.scaleType = ImageView.ScaleType.FIT_CENTER
                roomImage.setPadding(8, 8, 8, 8)
                roomImage.setBackgroundColor(android.graphics.Color.WHITE) // White background for vibe
            } else {
                // Custom or No Vibe -> Use Album Art
                roomImage.setPadding(0, 0, 0, 0)
                roomImage.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                roomImage.scaleType = ImageView.ScaleType.CENTER_CROP
                
                if (playingTrack?.thumbnailUrl != null) {
                    roomImage.load(playingTrack.thumbnailUrl) {
                        placeholder(R.drawable.album_placeholder)
                        error(R.drawable.album_placeholder)
                    }
                } else {
                    roomImage.setImageResource(R.drawable.album_placeholder)
                }
            }
        }

        private fun getVibeDrawable(vibeName: String?): Int? {
            return when (vibeName) {
                "Chill" -> R.drawable.chill
                "Main Character" -> R.drawable.main_character
                "Questioning Life" -> R.drawable.questioning_life
                "Trippin" -> R.drawable.trippin
                "Vibin" -> R.drawable.vibin
                else -> null // Custom or unknown -> Use Album Art
            }
        }
    }
}
