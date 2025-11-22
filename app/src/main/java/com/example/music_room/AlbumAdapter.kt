package com.example.music_room

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load

class AlbumAdapter(
    private val onAlbumClick: (Album, ImageView) -> Unit
) : androidx.recyclerview.widget.ListAdapter<Album, AlbumAdapter.AlbumViewHolder>(AlbumDiffCallback()) {

    class AlbumViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val albumImage: ImageView = view.findViewById(R.id.albumArt)
        val albumTitle: TextView = view.findViewById(R.id.albumTitle)
        val albumArtist: TextView = view.findViewById(R.id.albumArtist)
        val menuButton: ImageView = view.findViewById(R.id.menuButton)
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlbumViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_album, parent, false)
        return AlbumViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: AlbumViewHolder, position: Int) {
        val album = getItem(position)
        
        // Set unique transition name for each item
        holder.albumImage.transitionName = "album_art_${album.trackId}"
        
        val imageResId = album.imageResId
        if (imageResId != null) {
            holder.albumImage.setImageResource(imageResId)
        } else if (!album.imageUrl.isNullOrBlank()) {
            holder.albumImage.load(album.imageUrl) {
                placeholder(R.drawable.album_placeholder)
                error(R.drawable.album_placeholder)
            }
        } else {
            holder.albumImage.setImageResource(R.drawable.album_placeholder)
        }
        holder.albumTitle.text = album.title
        holder.albumArtist.text = album.artist
        
        // Click listener for entire card
        holder.itemView.setOnClickListener {
            onAlbumClick(album, holder.albumImage)
        }
    }
    
    class AlbumDiffCallback : androidx.recyclerview.widget.DiffUtil.ItemCallback<Album>() {
        override fun areItemsTheSame(oldItem: Album, newItem: Album): Boolean {
            return oldItem.trackId == newItem.trackId
        }

        override fun areContentsTheSame(oldItem: Album, newItem: Album): Boolean {
            return oldItem == newItem
        }
    }
}
