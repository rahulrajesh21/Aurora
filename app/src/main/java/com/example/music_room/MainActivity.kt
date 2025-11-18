package com.example.music_room

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Hide action bar completely
        supportActionBar?.hide()
        
        setContentView(R.layout.activity_main)
        
        setupPopularAlbums()
    }
    
    private fun setupPopularAlbums() {
        val albumsRecyclerView = findViewById<RecyclerView>(R.id.popularAlbumsRecyclerView)
        albumsRecyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        
        val albums = listOf(
            Album("After Hours", "The Weeknd", R.drawable.album_placeholder),
            Album("Mercury", "Billie Eilish", R.drawable.album_placeholder),
            Album("Starboy", "The Weeknd", R.drawable.album_placeholder)
        )
        
        albumsRecyclerView.adapter = AlbumAdapter(albums)
    }
}
