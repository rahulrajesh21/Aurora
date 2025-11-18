package com.example.music_room.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.music_room.R
import com.example.music_room.data.remote.model.RoomSnapshotDto

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
        private val hostName: TextView = view.findViewById(R.id.hostName)
        private val listenerCount: TextView = view.findViewById(R.id.listenerCount)
        private val currentSong: TextView = view.findViewById(R.id.currentSong)
        private val currentArtist: TextView = view.findViewById(R.id.currentArtist)

        fun bind(snapshot: RoomSnapshotDto) {
            roomName.text = snapshot.room.name
            hostName.text = view.context.getString(R.string.hosted_by_template, snapshot.room.hostName)
            listenerCount.text = snapshot.memberCount.toString()
            val playingTrack = snapshot.nowPlaying?.currentTrack
            currentSong.text = playingTrack?.title ?: view.context.getString(R.string.no_track_playing)
            currentArtist.text = playingTrack?.artist ?: view.context.getString(R.string.no_track_artist)
        }

        private val view: View = itemView
    }
}
