package com.example.music_room

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
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
        binding.backButton.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    private fun setupCreateRoomButton() {
        binding.createRoomButton.setOnClickListener {
            if (validateInputs()) {
                lifecycleScope.launch {
                    binding.createRoomButton.isEnabled = false
                    try {
                        val roomName = binding.roomNameInput.text?.toString()?.trim().orEmpty()
                        val passcodeText = binding.passcodeInput.text?.toString()?.trim().orEmpty()
                        val hostName = UserIdentity.getDisplayName(this@CreateRoomActivity)
                        val privateRoom = isPrivateRoom()
                        val visibility = if (privateRoom) "PRIVATE" else "PUBLIC"
                        val passcode = if (privateRoom) passcodeText else null

                        repository.createRoom(roomName, hostName, visibility = visibility, passcode = passcode)
                            .onSuccess { response ->
                                RoomSessionStore.saveMemberId(
                                    context = this@CreateRoomActivity,
                                    roomId = response.room.id,
                                    memberId = response.host.id
                                )
                                handleRoomCreated(response.room.id, response.room.name, response.room.hostId, privateRoom || !passcode.isNullOrBlank())
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

    private fun handleRoomCreated(roomId: String, roomName: String, hostMemberId: String, isLocked: Boolean) {
        val shareCode = roomId.takeLast(6).uppercase()
        copyToClipboard(shareCode)
        Toast.makeText(this, getString(R.string.room_created_toast, shareCode), Toast.LENGTH_LONG).show()

        val intent = Intent(this, RoomDetailActivity::class.java).apply {
            putExtra(RoomDetailActivity.EXTRA_ROOM_ID, roomId)
            putExtra(RoomDetailActivity.EXTRA_ROOM_NAME, roomName)
            putExtra(RoomDetailActivity.EXTRA_ROOM_LOCKED, isLocked)
            putExtra(RoomDetailActivity.EXTRA_ROOM_HOST_ID, hostMemberId)
        }
        startActivity(intent)
        finish()
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
        binding.chipVisibilityOpen.isChecked = true
        binding.visibilityChips.setOnCheckedStateChangeListener { _, checkedIds: MutableList<Int> ->
            val isPrivate = checkedIds.contains(binding.chipVisibilityPrivate.id)
            binding.passcodeLayout.isVisible = isPrivate
            binding.visibilityHelp.setText(
                if (isPrivate) R.string.create_room_visibility_help_private else R.string.create_room_visibility_help_public
            )
        }
    }

    private fun isPrivateRoom(): Boolean = binding.chipVisibilityPrivate.isChecked

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
