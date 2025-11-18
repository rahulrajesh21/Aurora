package com.example.music_room

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class RoomDetailActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        supportActionBar?.hide()
        
        setContentView(R.layout.activity_room_detail)
    }
}
