package com.example.music_room

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class RoomsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        supportActionBar?.hide()
        
        setContentView(R.layout.activity_rooms)
    }
}
