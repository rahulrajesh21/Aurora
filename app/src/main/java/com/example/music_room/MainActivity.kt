package com.example.music_room

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Hide action bar completely
        supportActionBar?.hide()
        
        setContentView(R.layout.activity_main)
        
        setupMenuButton()
        setupPopularAlbums()
        setupTrendingPlaylist()
    }

    private fun setupMenuButton() {
        val menuButton = findViewById<com.google.android.material.button.MaterialButton>(R.id.menuButton)
        menuButton.setOnClickListener { anchor ->
            PopupMenu(this, anchor).apply {
                menuInflater.inflate(R.menu.menu_main, menu)
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        R.id.action_create_room -> {
                            startActivity(Intent(this@MainActivity, CreateRoomActivity::class.java))
                            true
                        }
                        R.id.action_browse_rooms -> {
                            startActivity(Intent(this@MainActivity, RoomsActivity::class.java))
                            true
                        }
                        else -> false
                    }
                }
                show()
            }
        }
    }
    
    private fun setupPopularAlbums() {
        val albumsRecyclerView = findViewById<RecyclerView>(R.id.popularAlbumsRecyclerView)
        albumsRecyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        
        val albums = listOf(
            Album("After Hours", "The Weeknd", R.drawable.album_placeholder),
            Album("Mercury", "Billie Eilish", R.drawable.album_placeholder),
            Album("Starboy", "The Weeknd", R.drawable.album_placeholder)
        )
        
        albumsRecyclerView.adapter = AlbumAdapter(albums) { album ->
            openPlayer(album.title, album.artist)
        }
    }
    
    private fun setupTrendingPlaylist() {
        // Join room button
        val joinRoomButton = findViewById<com.google.android.material.button.MaterialButton>(R.id.joinRoomButton)
        joinRoomButton.setOnClickListener {
            val intent = Intent(this, RoomDetailActivity::class.java)
            startActivity(intent)
        }
    }
    
    private fun openPlayer(songTitle: String, artistName: String) {
        val intent = Intent(this, PlayerActivity::class.java)
        intent.putExtra("SONG_TITLE", songTitle)
        intent.putExtra("ARTIST_NAME", artistName)
        startActivity(intent)
    }
}
