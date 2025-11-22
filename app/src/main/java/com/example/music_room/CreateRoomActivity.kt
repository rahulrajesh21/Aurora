package com.example.music_room

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.music_room.data.AuroraServiceLocator
import com.example.music_room.data.RoomSessionStore
import com.example.music_room.data.UserIdentity
import com.example.music_room.databinding.ActivityCreateRoomBinding
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.launch

data class Vibe(val name: String, val imageRes: Int)

class CreateRoomActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCreateRoomBinding
    private val repository = AuroraServiceLocator.repository

    private val vibes = mutableListOf(
        Vibe("Chill", R.drawable.chill),
        Vibe("Main Character", R.drawable.main_character),
        Vibe("Questioning Life", R.drawable.questioning_life),
        Vibe("Trippin", R.drawable.trippin),
        Vibe("Vibin", R.drawable.vibin),
        Vibe("Custom", R.drawable.ic_add) // Custom Vibe
    )

    private var selectedVibe = vibes[0]
    private var customVibeName: String? = null
    private var isPrivate = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar?.hide()
        binding = ActivityCreateRoomBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupVibeSelector()
        setupCreateRoomButton()
        setupBackNavigation()
        setupAccessToggle()
    }

    private fun setupBackNavigation() {
        binding.backButton.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    private fun setupVibeSelector() {
        val adapter = VibeAdapter(vibes) { vibe ->
            selectedVibe = vibe
            if (vibe.name == "Custom") {
                binding.customVibeSection.isVisible = true
                binding.customVibeInput.requestFocus()
            } else {
                binding.customVibeSection.isVisible = false
                customVibeName = null
            }
        }
        binding.vibeRecyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.vibeRecyclerView.adapter = adapter
    }

    // Removed showCustomVibeDialog as it's no longer used

    private fun setupAccessToggle() {
        binding.optionPublic.setOnClickListener {
            if (isPrivate) animateToggle(false)
        }
        binding.optionPrivate.setOnClickListener {
            if (!isPrivate) animateToggle(true)
        }
    }

    private fun animateToggle(toPrivate: Boolean) {
        isPrivate = toPrivate
        
        // Animate Indicator using TranslationX for better performance
        val indicatorWidth = binding.toggleIndicator.width.toFloat()
        val startTranslation = binding.toggleIndicator.translationX
        val endTranslation = if (toPrivate) indicatorWidth else 0f

        val animator = android.animation.ValueAnimator.ofFloat(startTranslation, endTranslation)
        animator.addUpdateListener { valueAnimator ->
            val value = valueAnimator.animatedValue as Float
            binding.toggleIndicator.translationX = value
        }
        
        // OvershootInterpolator gives the "jelly" / bounce effect
        animator.interpolator = android.view.animation.OvershootInterpolator(1.5f)
        animator.duration = 400
        animator.start()

        // Update Text Colors
        binding.optionPublic.setTextColor(if (toPrivate) 0xFF888888.toInt() else android.graphics.Color.BLACK)
        binding.optionPrivate.setTextColor(if (toPrivate) android.graphics.Color.BLACK else 0xFF888888.toInt())

        // Show/Hide Password with simple Alpha Animation
        if (toPrivate) {
            binding.passwordSection.alpha = 0f
            binding.passwordSection.isVisible = true
            binding.passwordSection.animate()
                .alpha(1f)
                .setDuration(300)
                .start()
        } else {
            binding.passwordSection.animate()
                .alpha(0f)
                .setDuration(200)
                .withEndAction { binding.passwordSection.isVisible = false }
                .start()
        }
    }

    private fun setupCreateRoomButton() {
        binding.createRoomButton.setOnClickListener {
            if (validateInputs()) {
                lifecycleScope.launch {
                    binding.createRoomButton.isEnabled = false
                    try {
                        val roomName = binding.roomNameInput.text?.toString()?.trim().orEmpty()
                        
                        // Determine description: Custom input or selected vibe name
                        val description = if (selectedVibe.name == "Custom") {
                             binding.customVibeInput.text?.toString()?.trim().takeIf { !it.isNullOrEmpty() } ?: "Custom Vibe"
                        } else {
                            selectedVibe.name
                        }

                        val hostName = UserIdentity.getDisplayName(this@CreateRoomActivity)
                        val visibility = if (isPrivate) "PRIVATE" else "PUBLIC"
                        val passcode = if (isPrivate) binding.passcodeInput.text?.toString()?.trim() else null

                        repository.createRoom(roomName, hostName, description = description, visibility = visibility, passcode = passcode)
                            .onSuccess { response ->
                                RoomSessionStore.saveMemberId(
                                    context = this@CreateRoomActivity,
                                    roomId = response.room.id,
                                    memberId = response.host.id
                                )
                                handleRoomCreated(response.room.id, response.room.name, response.room.hostId, isPrivate)
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
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Room Code", text)
        clipboard.setPrimaryClip(clip)
    }

    private fun validateInputs(): Boolean {
        val roomName = binding.roomNameInput.text?.toString()?.trim().orEmpty()
        if (roomName.isEmpty()) {
            binding.roomNameInput.error = "Room name is required"
            return false
        }
        
        if (isPrivate) {
            val passcode = binding.passcodeInput.text?.toString()?.trim().orEmpty()
            if (passcode.length < 4) {
                binding.passcodeInput.error = "Enter 4-digit passcode"
                return false
            }
        }
        return true
    }

    inner class VibeAdapter(
        private val vibes: List<Vibe>,
        private val onVibeSelected: (Vibe) -> Unit
    ) : RecyclerView.Adapter<VibeAdapter.VibeViewHolder>() {

        private var selectedPosition = 0

        inner class VibeViewHolder(val view: android.view.View) : RecyclerView.ViewHolder(view) {
            val image: ImageView = view.findViewById(R.id.vibeImage)
            val title: TextView = view.findViewById(R.id.vibeTitle)
            val card: MaterialCardView = view.findViewById(R.id.cardRoot)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VibeViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_vibe_card, parent, false)
            return VibeViewHolder(view)
        }

        override fun onBindViewHolder(holder: VibeViewHolder, position: Int) {
            val vibe = vibes[position]
            holder.image.setImageResource(vibe.imageRes)
            holder.title.text = vibe.name

            // Special styling for Custom card
            if (vibe.name == "Custom") {
                 holder.image.setPadding(40, 40, 40, 40)
                 // Make it greyscale
                 holder.image.imageTintList = android.content.res.ColorStateList.valueOf(0xFF888888.toInt())
            } else {
                 holder.image.setPadding(16, 16, 16, 16)
                 holder.image.imageTintList = null // Reset tint
            }

            val isSelected = position == selectedPosition
            holder.card.strokeColor = if (isSelected) ContextCompat.getColor(holder.view.context, R.color.black) else ContextCompat.getColor(holder.view.context, android.R.color.transparent)
            holder.card.strokeWidth = if (isSelected) 4 else 0

            holder.itemView.setOnClickListener {
                val previous = selectedPosition
                selectedPosition = holder.adapterPosition
                notifyItemChanged(previous)
                notifyItemChanged(selectedPosition)
                onVibeSelected(vibe)
            }
        }

        override fun getItemCount() = vibes.size
    }
}
