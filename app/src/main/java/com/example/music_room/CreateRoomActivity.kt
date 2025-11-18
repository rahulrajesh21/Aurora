package com.example.music_room

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlin.random.Random

class CreateRoomActivity : AppCompatActivity() {
    private lateinit var roomNameLayout: TextInputLayout
    private lateinit var vibeLayout: TextInputLayout
    private lateinit var capacityLayout: TextInputLayout

    private lateinit var roomNameInput: TextInputEditText
    private lateinit var vibeInput: TextInputEditText
    private lateinit var capacityInput: TextInputEditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar?.hide()
        setContentView(R.layout.activity_create_room)

        initViews()
        setupCreateRoomButton()
        setupBackNavigation()
    }

    private fun initViews() {
        roomNameLayout = findViewById(R.id.roomNameLayout)
        vibeLayout = findViewById(R.id.vibeLayout)
        capacityLayout = findViewById(R.id.capacityLayout)

        roomNameInput = findViewById(R.id.roomNameInput)
        vibeInput = findViewById(R.id.vibeInput)
        capacityInput = findViewById(R.id.capacityInput)
    }

    private fun setupBackNavigation() {
        findViewById<MaterialButton>(R.id.backButton)?.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setupCreateRoomButton() {
        val createRoomButton = findViewById<MaterialButton>(R.id.createRoomButton)
        createRoomButton.setOnClickListener {
            if (validateInputs()) {
                val roomName = roomNameInput.text?.toString()?.trim().orEmpty()
                val vibe = vibeInput.text?.toString()?.trim().orEmpty()
                val capacity = capacityInput.text?.toString()?.trim().orEmpty()

                // Generate a unique room code
                val roomCode = generateRoomCode()

                showRoomCodeDialog(roomName, roomCode, vibe, capacity)
            }
        }
    }

    private fun generateRoomCode(): String {
        // Generate a 6-character alphanumeric code
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return (1..6)
            .map { chars[Random.nextInt(chars.length)] }
            .joinToString("")
    }

    private fun showRoomCodeDialog(roomName: String, roomCode: String, vibe: String, capacity: String) {
        val message = buildString {
            append("Room Created Successfully!\n\n")
            append("Room Code: $roomCode\n\n")
            append("Share this code with friends to invite them to \"$roomName\"")
            if (vibe.isNotEmpty()) {
                append("\n\nVibe: $vibe")
            }
            if (capacity.isNotEmpty()) {
                append("\nCapacity: $capacity listeners")
            }
        }

        AlertDialog.Builder(this)
            .setTitle("🎉 Room Created!")
            .setMessage(message)
            .setPositiveButton("Copy Code") { _, _ ->
                copyToClipboard(roomCode)
                Toast.makeText(this, "Room code copied!", Toast.LENGTH_SHORT).show()
                finish()
            }
            .setNegativeButton("Done") { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Room Code", text)
        clipboard.setPrimaryClip(clip)
    }

    private fun validateInputs(): Boolean {
        var isValid = true

        val roomName = roomNameInput.text?.toString()?.trim().orEmpty()
        if (roomName.isEmpty()) {
            roomNameLayout.error = "Room name is required"
            isValid = false
        } else {
            roomNameLayout.error = null
        }

        val capacityText = capacityInput.text?.toString()?.trim().orEmpty()
        if (capacityText.isNotEmpty()) {
            val capacity = capacityText.toIntOrNull()
            if (capacity == null || capacity <= 0) {
                capacityLayout.error = "Enter a valid number"
                isValid = false
            } else {
                capacityLayout.error = null
            }
        } else {
            capacityLayout.error = null
        }

        return isValid
    }
}
