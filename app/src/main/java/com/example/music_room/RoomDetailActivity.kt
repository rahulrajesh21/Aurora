package com.example.music_room

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast

import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.example.music_room.data.AuroraServiceLocator
import com.example.music_room.data.RoomSessionStore
import com.example.music_room.data.UserIdentity
import com.example.music_room.data.remote.model.PlaybackStateDto
import com.example.music_room.data.remote.model.TrackDto
import com.example.music_room.databinding.ActivityRoomDetailBinding
import com.example.music_room.ui.AddSongBottomSheet
import com.example.music_room.ui.JoinRoomBottomSheet
import com.example.music_room.ui.QueueAdapter
import com.example.music_room.service.MediaServiceManager
import com.example.music_room.utils.PermissionUtils
import com.google.android.material.slider.Slider
import kotlinx.coroutines.launch
import com.example.music_room.R

class RoomDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRoomDetailBinding
    private val viewModel: com.example.music_room.ui.viewmodel.RoomDetailViewModel by viewModels()
    
    private val queueAdapter = QueueAdapter(
        onVote = { position -> viewModel.promoteTrack(position) },
        onRemove = { position -> viewModel.removeTrack(position) },
        onItemMove = { from, to -> 
            // Optimistic update handled by adapter visually
        }
    )

    private val roomId: String by lazy { intent.getStringExtra(EXTRA_ROOM_ID).orEmpty() }
    private val roomName: String by lazy { intent.getStringExtra(EXTRA_ROOM_NAME).orEmpty() }
    private val isLocked: Boolean by lazy { intent.getBooleanExtra(EXTRA_ROOM_LOCKED, false) }
    private val roomHostId: String by lazy { intent.getStringExtra(EXTRA_ROOM_HOST_ID).orEmpty() }
    
    private var leaveRequested = false
    private var sliderBeingDragged = false
    private lateinit var mediaServiceManager: MediaServiceManager
    
    private val playbackTickerHandler = Handler(Looper.getMainLooper())
    private val playbackTicker = object : Runnable {
        override fun run() {
            tickPlaybackProgress()
            schedulePlaybackTicker()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        binding = ActivityRoomDetailBinding.inflate(layoutInflater)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(binding.root)

        binding.roomNameTitle.text = roomName
        
        viewModel.setRoomId(roomId)
        viewModel.setRoomHostId(roomHostId.ifEmpty { null })

        // Initialize background media service
        mediaServiceManager = MediaServiceManager.getInstance(this)

        setupSession()
        setupUI()
        setupPlaybackControls()
        setupQueueList()
        observeViewModel()

        lifecycleScope.launch {
            viewModel.refreshPlaybackState()
            viewModel.refreshQueue()
        }
    }

    private fun setupSession() {
        val cachedSession = RoomSessionStore.getSession(this, roomId)
        if (cachedSession != null) {
            viewModel.setMemberId(cachedSession.memberId)
        }
    }

    private fun setupUI() {
        binding.backButton.setOnClickListener { finish() }
        binding.leaveButton.setOnClickListener { leaveRoomAndFinish() }
        binding.deleteRoomButton.setOnClickListener { promptDeleteRoom() }
        binding.addSongButton.setOnClickListener { promptAddSong() }
    }

    private fun setupQueueList() {
        binding.queueRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@RoomDetailActivity)
            adapter = queueAdapter
        }

        val itemTouchHelper = androidx.recyclerview.widget.ItemTouchHelper(object : androidx.recyclerview.widget.ItemTouchHelper.Callback() {
            override fun getMovementFlags(
                recyclerView: androidx.recyclerview.widget.RecyclerView,
                viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder
            ): Int {
                val dragFlags = androidx.recyclerview.widget.ItemTouchHelper.UP or androidx.recyclerview.widget.ItemTouchHelper.DOWN
                val swipeFlags = 0 
                return makeMovementFlags(dragFlags, swipeFlags)
            }

            override fun onMove(
                recyclerView: androidx.recyclerview.widget.RecyclerView,
                viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                target: androidx.recyclerview.widget.RecyclerView.ViewHolder
            ): Boolean {
                queueAdapter.onItemMove(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }

            override fun onSwiped(viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder, direction: Int) {}

            var dragFrom = -1
            var dragTo = -1

            override fun onSelectedChanged(viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == androidx.recyclerview.widget.ItemTouchHelper.ACTION_STATE_DRAG) {
                    viewHolder?.let { dragFrom = it.bindingAdapterPosition }
                }
            }
            
            override fun clearView(recyclerView: androidx.recyclerview.widget.RecyclerView, viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                dragTo = viewHolder.bindingAdapterPosition
                if (dragFrom != -1 && dragTo != -1 && dragFrom != dragTo) {
                    viewModel.reorderQueue(dragFrom, dragTo)
                }
                dragFrom = -1
                dragTo = -1
            }
        })
        itemTouchHelper.attachToRecyclerView(binding.queueRecyclerView)
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    // Member ID / Join State
                    binding.leaveButton.isEnabled = state.memberId != null
                    binding.deleteRoomButton.isVisible = state.canDeleteRoom
                    if (state.memberId == null && !leaveRequested) {
                        joinRoomIfNeeded()
                    }

                    // Playback State
                    state.playbackState?.let { applyPlaybackState(it) }

                    // Queue
                    updateQueueFromState(state.queue, state.isQueueEmpty, state.isQueueLoading)

                    // Error
                    if (state.error != null) {
                        Toast.makeText(this@RoomDetailActivity, state.error, Toast.LENGTH_SHORT).show()
                        viewModel.clearError()
                    }
                }
            }
        }
    }

    private fun promptDeleteRoom() {
        if (!viewModel.uiState.value.canDeleteRoom) {
            Toast.makeText(this, R.string.delete_room_not_allowed, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_room_dialog_title)
            .setMessage(R.string.delete_room_dialog_message)
            .setPositiveButton(R.string.delete_room_confirm) { _, _ -> performDeleteRoom() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun performDeleteRoom() {
        val memberId = viewModel.uiState.value.memberId
        if (memberId == null) {
            Toast.makeText(this, R.string.delete_room_not_joined, Toast.LENGTH_SHORT).show()
            return
        }
        binding.deleteRoomButton.isEnabled = false
        viewModel.deleteRoom(roomId) { success, error ->
            binding.deleteRoomButton.isEnabled = true
            if (success) {
                RoomSessionStore.clearMemberId(this, roomId)
                Toast.makeText(this, R.string.delete_room_success, Toast.LENGTH_SHORT).show()
                finish()
            } else if (!error.isNullOrEmpty()) {
                Toast.makeText(this, error, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun joinRoomIfNeeded() {
        if (roomId.isEmpty()) {
            Toast.makeText(this, getString(R.string.room_not_found), Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        // If we are already joining or have joined (checked in observeViewModel), skip.
        // But here we need to trigger the join if memberId is null.
        // We should check if we are already attempting to join? 
        // For simplicity, we'll just check if we have a cached session or need to prompt.
        
        // Note: This logic is slightly tricky to move fully to VM without UI callbacks for prompts.
        // We'll keep the prompt logic here but delegate the actual join call to VM.
        
        if (isLocked) {
            promptCredentialsAndJoin()
        } else {
            attemptJoin()
        }
    }

    private fun promptCredentialsAndJoin() {
        val sheet = JoinRoomBottomSheet(
            context = this,
            roomName = roomName,
            onJoinRequested = { passcode, inviteCode, onSuccess, onError ->
                viewModel.joinRoom(roomId, UserIdentity.getDisplayName(this), passcode, inviteCode) { memberId ->
                    RoomSessionStore.saveMemberId(this, roomId, memberId)
                    Toast.makeText(
                        this,
                        getString(R.string.joined_as_template, UserIdentity.getDisplayName(this)),
                        Toast.LENGTH_SHORT
                    ).show()
                    val now = System.currentTimeMillis()
                    onSuccess(
                        com.example.music_room.data.remote.model.JoinRoomResponseDto(
                            com.example.music_room.data.remote.model.RoomMemberDto(
                                memberId,
                                UserIdentity.getDisplayName(this),
                                now,
                                now,
                                isHost = false
                            )
                        )
                    ) // Mock response object for callback compatibility if needed, or adjust callback
                }
            },
            onCancelled = { finish() }
        )
        sheet.show()
    }

    private fun attemptJoin(passcode: String? = null, inviteCode: String? = null) {
        viewModel.joinRoom(roomId, UserIdentity.getDisplayName(this), passcode, inviteCode) { memberId ->
            RoomSessionStore.saveMemberId(this, roomId, memberId)
            Toast.makeText(
                this,
                getString(R.string.joined_as_template, UserIdentity.getDisplayName(this)),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun leaveRoomAndFinish() {
        if (leaveRequested) return
        leaveRequested = true
        binding.leaveButton.isEnabled = false
        val memberId = viewModel.uiState.value.memberId
        if (memberId != null) {
            lifecycleScope.launch {
                viewModel.leaveRoom(roomId, memberId)
                // Wait a moment for the request to complete
                kotlinx.coroutines.delay(500)
                RoomSessionStore.clearMemberId(this@RoomDetailActivity, roomId)
                finish()
            }
        } else {
            finish()
        }
    }

    private fun setupPlaybackControls() {
        binding.playPauseButton.setOnClickListener { viewModel.togglePlayPause() }
        binding.nextButton.setOnClickListener { viewModel.skipTrack() }
        binding.previousButton.setOnClickListener { viewModel.previousTrack() }
        binding.shuffleButton.setOnClickListener { viewModel.shuffleQueue() }
        binding.repeatButton.setOnClickListener { viewModel.restartTrack() }
        
        binding.playbackSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val rounded = kotlin.math.round(value).toInt()
                binding.playbackElapsed.text = formatTime(rounded)
            }
        }
        binding.playbackSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                sliderBeingDragged = true
            }

            override fun onStopTrackingTouch(slider: Slider) {
                sliderBeingDragged = false
                val rounded = kotlin.math.round(slider.value).toInt()
                
                // Optimistic local update
                binding.playbackElapsed.text = formatTime(rounded)
                viewModel.sliderBasePositionSeconds = rounded.toFloat()
                viewModel.sliderBaseTimestamp = SystemClock.elapsedRealtime()
                
                // Background service handles seeking
                viewModel.seekTo(rounded)
            }
        })
    }

    private fun applyPlaybackState(state: PlaybackStateDto) {
        val track = state.currentTrack
        binding.currentSongTitle.text = track?.title ?: getString(R.string.no_track_playing)
        binding.currentSongArtist.text = track?.artist ?: getString(R.string.no_track_artist)
        binding.playPauseButton.setImageResource(if (state.isPlaying) R.drawable.ic_pause_large else R.drawable.ic_play_circle)

        if (!track?.thumbnailUrl.isNullOrBlank()) {
            val highResUrl = getHighResThumbnailUrl(track.thumbnailUrl)
            binding.albumArtImage.load(highResUrl) {
                placeholder(R.drawable.album_placeholder)
                error(R.drawable.album_placeholder)
            }
        } else {
            binding.albumArtImage.setImageResource(R.drawable.album_placeholder)
        }

        val duration = track?.durationSeconds?.takeIf { it > 0 } ?: state.positionSeconds.coerceAtLeast(1)
        binding.playbackDuration.text = formatTime(duration)
        binding.playbackSlider.isEnabled = track != null
        
        if (!sliderBeingDragged) {
            binding.playbackSlider.valueFrom = 0f
            binding.playbackSlider.valueTo = duration.coerceAtLeast(1).toFloat()
            binding.playbackSlider.value = state.positionSeconds.coerceIn(0, duration).toFloat()
            binding.playbackElapsed.text = formatTime(state.positionSeconds)
        }

        // Background service handles playback
        
        if (state.isPlaying) {
            schedulePlaybackTicker()
        } else {
            stopPlaybackTicker()
        }
    }

    private fun updateQueueFromState(queue: List<TrackDto>, isEmpty: Boolean, isLoading: Boolean) {
        queueAdapter.submitList(queue)
        binding.queueEmptyState.isVisible = isEmpty && !isLoading
        binding.queueSkeleton.isVisible = isLoading
        if (isLoading) {
            binding.queueRecyclerView.isVisible = false
        } else {
            binding.queueRecyclerView.isVisible = true
        }
        if (!isEmpty) {
            binding.queueEmptyState.isVisible = false
        }
    }

    private fun promptAddSong() {
        val sheet = AddSongBottomSheet(
            context = this,
            onSearch = { query, onResults, onError ->
                viewModel.search(query, onResults, onError)
            },
            onPlayNow = { track ->
                viewModel.playTrack(track.id, track.provider)
                Toast.makeText(this, R.string.now_playing_label, Toast.LENGTH_SHORT).show()
            },
            onAddToQueue = { track ->
                viewModel.addToQueue(track.id, track.provider, UserIdentity.getDisplayName(this))
                Toast.makeText(this, R.string.track_added, Toast.LENGTH_SHORT).show()
            }
        )
        sheet.show()
    }



    private fun schedulePlaybackTicker() {
        playbackTickerHandler.removeCallbacks(playbackTicker)
        if (viewModel.uiState.value.playbackState?.isPlaying == true) {
            playbackTickerHandler.postDelayed(playbackTicker, 1000)
        }
    }

    private fun stopPlaybackTicker() {
        playbackTickerHandler.removeCallbacks(playbackTicker)
    }

    private fun tickPlaybackProgress() {
        val state = viewModel.uiState.value.playbackState ?: return
        if (!state.isPlaying || sliderBeingDragged) {
            return
        }
        val duration = state.currentTrack?.durationSeconds?.takeIf { it > 0 }
            ?: binding.playbackSlider.valueTo.toInt().coerceAtLeast(1)
            
        val elapsed = viewModel.sliderBasePositionSeconds + ((SystemClock.elapsedRealtime() - viewModel.sliderBaseTimestamp) / 1000f)
        val clamped = elapsed.coerceIn(0f, duration.toFloat())
        
        val rounded = kotlin.math.round(clamped)
        binding.playbackSlider.value = rounded
        binding.playbackElapsed.text = formatTime(rounded.toInt())
        
        viewModel.sliderBasePositionSeconds = clamped
        viewModel.sliderBaseTimestamp = SystemClock.elapsedRealtime()
    }

    private fun getHighResThumbnailUrl(url: String?): String? {
        if (url == null) return null
        if (url.contains("i.ytimg.com")) {
            return url.replace("default.jpg", "sddefault.jpg")
                .replace("mqdefault.jpg", "sddefault.jpg")
                .replace("hqdefault.jpg", "sddefault.jpg")
        }
        return url
    }

    private fun formatTime(seconds: Int): String {
        val safeSeconds = seconds.coerceAtLeast(0)
        val minutes = safeSeconds / 60
        val remaining = safeSeconds % 60
        return String.format("%d:%02d", minutes, remaining)
    }

    override fun onStart() {
        super.onStart()
        viewModel.connectSocket()
        
        // Request notification permission and start background service
        if (PermissionUtils.requestNotificationPermissionIfNeeded(this)) {
            startBackgroundService()
        }
    }
    
    private fun startBackgroundService() {
        // Start background media service for this room
        mediaServiceManager.startPlaybackService(roomId)
    }

    override fun onStop() {
        viewModel.disconnectSocket()
        stopPlaybackTicker()
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        PermissionUtils.handlePermissionResult(
            requestCode, permissions, grantResults,
            onGranted = { startBackgroundService() },
            onDenied = { 
                Toast.makeText(this, "Notification permission required for background playback", Toast.LENGTH_LONG).show()
            }
        )
    }

    companion object {
        const val EXTRA_ROOM_ID = "extra_room_id"
        const val EXTRA_ROOM_NAME = "extra_room_name"
        const val EXTRA_ROOM_LOCKED = "extra_room_locked"
        const val EXTRA_ROOM_HOST_ID = "extra_room_host_id"
        private const val PLAYER_SYNC_TOLERANCE_MS = 750L
    }
}
