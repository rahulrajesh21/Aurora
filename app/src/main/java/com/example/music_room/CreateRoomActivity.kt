package com.example.music_room

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.lifecycleScope
import com.example.music_room.data.AuroraServiceLocator
import com.example.music_room.data.UserIdentity
import com.example.music_room.data.RoomSessionStore
import com.example.music_room.databinding.ActivityCreateRoomBinding
import kotlinx.coroutines.launch

class CreateRoomActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCreateRoomBinding
    private val repository = AuroraServiceLocator.repository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar?.hide()
        binding = ActivityCreateRoomBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupCreateRoomButton()
        setupBackNavigation()
        setupVibePreview()
        setupVibeSuggestions()
        setupVisibilityToggle()
    }

    private fun setupBackNavigation() {
        binding.backButton?.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    private fun setupCreateRoomButton() {
        binding.createRoomButton.setOnClickListener {
            if (validateInputs()) {
                lifecycleScope.launch {
                    binding.createRoomButton.isEnabled = false
                    try {
                        val roomName = binding.roomNameInput.text?.toString()?.trim().orEmpty()
                        val vibe = binding.vibeInput.text?.toString()?.trim().orEmpty()
                        val capacity = binding.capacityInput.text?.toString()?.trim().orEmpty()
                        val passcodeText = binding.passcodeInput.text?.toString()?.trim().orEmpty()
                        val hostName = UserIdentity.getDisplayName(this@CreateRoomActivity)
                        val visibility = if (isPrivateRoom()) "PRIVATE" else "PUBLIC"
                        val passcode = if (isPrivateRoom()) passcodeText else null

                        repository.createRoom(roomName, hostName, visibility = visibility, passcode = passcode)
                            .onSuccess { response ->
                                RoomSessionStore.saveMemberId(
                                    context = this@CreateRoomActivity,
                                    roomId = response.room.id,
                                    memberId = response.host.id
                                )
                                showRoomCodeDialog(
                                    roomName = response.room.name,
                                    roomCode = response.room.id.takeLast(6).uppercase(),
                                    vibe = vibe,
                                    capacity = capacity,
                                    backendRoomId = response.room.id,
                                    isPrivate = isPrivateRoom(),
                                    passcode = passcode
                                )
                            }
                            .onFailure { error ->
                                Toast.makeText(
                                    this@CreateRoomActivity,
                                    error.message ?: getString(R.string.loading),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                    } finally {
                        binding.createRoomButton.isEnabled = true
                    }
                }
            }
        }
    }

    private fun showRoomCodeDialog(
        roomName: String,
        roomCode: String,
        vibe: String,
        capacity: String,
        backendRoomId: String,
        isPrivate: Boolean,
        passcode: String?
    ) {
        val message = buildString {
            append("Room Created Successfully!\n\n")
            append("Shareable Code: $roomCode\n\n")
            append("Room ID: $backendRoomId\n\n")
            append("Share this code with friends to invite them to \"$roomName\"")
            if (vibe.isNotEmpty()) {
                append("\n\nVibe: $vibe")
            }
            if (capacity.isNotEmpty()) {
                append("\nCapacity: $capacity listeners")
            }
            if (isPrivate && !passcode.isNullOrBlank()) {
                append("\nPasscode: $passcode")
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

    private fun setupVibePreview() {
        binding.vibeInput.doOnTextChanged { text, _, _, _ ->
            val vibe = text?.toString()?.trim().orEmpty()
            binding.vibePreview.text = if (vibe.isEmpty()) {
                getString(R.string.create_room_vibe_preview_default)
            } else {
                getString(R.string.create_room_vibe_preview, vibe)
            }
        }
    }

    private fun setupVibeSuggestions() {
        val chips = listOfNotNull(binding.chipVibeChill, binding.chipVibeParty, binding.chipVibeStudy)
        chips.forEach { chip ->
            chip.setOnClickListener {
                binding.vibeInput.setText(chip.text)
                binding.vibeInput.setSelection(chip.text?.length ?: 0)
            }
        }
    }

    private fun setupVisibilityToggle() {
        binding.visibilityToggle.check(binding.visibilityPublic.id)
        binding.visibilityToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val isPrivate = checkedId == binding.visibilityPrivate.id
            binding.passcodeLayout.isVisible = isPrivate
            binding.visibilityHelp.setText(
                if (isPrivate) R.string.create_room_visibility_help_private else R.string.create_room_visibility_help_public
            )
        }
    }

    private fun isPrivateRoom(): Boolean = binding.visibilityToggle.checkedButtonId == binding.visibilityPrivate.id

    private fun validateInputs(): Boolean {
        var isValid = true

        val roomName = binding.roomNameInput.text?.toString()?.trim().orEmpty()
        if (roomName.isEmpty()) {
            binding.roomNameLayout.error = "Room name is required"
            isValid = false
        } else {
            binding.roomNameLayout.error = null
        }

        val capacityText = binding.capacityInput.text?.toString()?.trim().orEmpty()
        if (capacityText.isNotEmpty()) {
            val capacity = capacityText.toIntOrNull()
            if (capacity == null || capacity <= 0) {
                binding.capacityLayout.error = "Enter a valid number"
                isValid = false
            } else {
                binding.capacityLayout.error = null
            }
        } else {
            binding.capacityLayout.error = null
        }

        if (isPrivateRoom()) {
            val passcodeText = binding.passcodeInput.text?.toString()?.trim().orEmpty()
            if (passcodeText.length < 4) {
                binding.passcodeLayout.error = getString(R.string.create_room_passcode_error)
                isValid = false
            } else {
                binding.passcodeLayout.error = null
            }
        } else {
            binding.passcodeLayout.error = null
        }

        return isValid
    }
}
