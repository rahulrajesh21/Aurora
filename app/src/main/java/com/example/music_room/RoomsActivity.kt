package com.example.music_room

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity

class RoomsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        supportActionBar?.hide()
        
        setContentView(R.layout.activity_rooms)

        setupCreateRoomAction()
    }

    private fun setupCreateRoomAction() {
        val createRoomButton = findViewById<ImageView>(R.id.createRoomButton)
        createRoomButton.setOnClickListener {
            val intent = Intent(this, CreateRoomActivity::class.java)
            startActivity(intent)
        }
    }
}
