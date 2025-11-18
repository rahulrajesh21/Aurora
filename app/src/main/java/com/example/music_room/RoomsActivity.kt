package com.example.music_room

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.music_room.data.AuroraServiceLocator
import com.example.music_room.databinding.ActivityRoomsBinding
import com.example.music_room.ui.RoomsAdapter
import kotlinx.coroutines.launch

class RoomsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRoomsBinding
    private val repository = AuroraServiceLocator.repository
    private val roomsAdapter = RoomsAdapter { snapshot ->
        val intent = Intent(this, RoomDetailActivity::class.java).apply {
            putExtra(RoomDetailActivity.EXTRA_ROOM_ID, snapshot.room.id)
            putExtra(RoomDetailActivity.EXTRA_ROOM_NAME, snapshot.room.name)
            putExtra(RoomDetailActivity.EXTRA_ROOM_LOCKED, snapshot.isLocked)
        }
        startActivity(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        binding = ActivityRoomsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.roomsRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.roomsRecyclerView.adapter = roomsAdapter

        binding.createRoomButton.setOnClickListener {
            startActivity(Intent(this, CreateRoomActivity::class.java))
        }

        lifecycleScope.launch { loadRooms() }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { loadRooms() }
    }

    private suspend fun loadRooms() {
        binding.roomsLoading.isVisible = true
        repository.getRooms()
            .onSuccess { rooms ->
                roomsAdapter.submitList(rooms)
                binding.roomsEmptyState.isVisible = rooms.isEmpty()
            }
            .onFailure { error ->
                binding.roomsEmptyState.isVisible = true
                binding.roomsEmptyState.text = error.message ?: getString(R.string.rooms_empty)
                Toast.makeText(this, error.message ?: getString(R.string.rooms_empty), Toast.LENGTH_LONG).show()
            }
        binding.roomsLoading.isVisible = false
    }
}
