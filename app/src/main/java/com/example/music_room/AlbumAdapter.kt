package com.example.music_room

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AlbumAdapter(
    private val albums: List<Album>,
    private val onAlbumClick: (Album) -> Unit
) : RecyclerView.Adapter<AlbumAdapter.AlbumViewHolder>() {
    
    class AlbumViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val albumImage: ImageView = view.findViewById(R.id.albumImage)
        val albumTitle: TextView = view.findViewById(R.id.albumTitleInside)
        val albumArtist: TextView = view.findViewById(R.id.albumArtistInside)
        val playButton: ImageView = view.findViewById(R.id.playButton)
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlbumViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_album, parent, false)
        return AlbumViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: AlbumViewHolder, position: Int) {
        val album = albums[position]
        holder.albumImage.setImageResource(album.imageResId)
        holder.albumTitle.text = album.title
        holder.albumArtist.text = album.artist
        
        // Click listener for play button
        holder.playButton.setOnClickListener {
            onAlbumClick(album)
        }
        
        // Click listener for entire card
        holder.itemView.setOnClickListener {
            onAlbumClick(album)
        }
    }
    
    override fun getItemCount() = albums.size
}
