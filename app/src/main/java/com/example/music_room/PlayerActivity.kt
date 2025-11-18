package com.example.music_room

import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class PlayerActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Hide action bar completely
        supportActionBar?.hide()
        
        setContentView(R.layout.activity_player)
        
        // Get data from intent
        val songTitle = intent.getStringExtra("SONG_TITLE") ?: "Unknown Song"
        val artistName = intent.getStringExtra("ARTIST_NAME") ?: "Unknown Artist"
        
        // Set song info
        findViewById<TextView>(R.id.songTitle).text = songTitle
        findViewById<TextView>(R.id.artistName).text = artistName
        
        // Back button
        findViewById<ImageView>(R.id.backButton).setOnClickListener {
            finish()
        }
    }
}
