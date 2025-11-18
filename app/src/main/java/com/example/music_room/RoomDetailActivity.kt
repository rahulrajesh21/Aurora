package com.example.music_room

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
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
import com.google.android.material.slider.Slider
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import kotlinx.coroutines.launch

class RoomDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRoomDetailBinding
    private val repository = AuroraServiceLocator.repository
    private val playbackSocket by lazy { AuroraServiceLocator.createPlaybackSocket() }
    private val queueAdapter = QueueAdapter(
        onVote = { position -> lifecycleScope.launch { promoteTrack(position) } },
        onRemove = { position -> lifecycleScope.launch { removeTrack(position) } }
    )

    private val roomId: String by lazy { intent.getStringExtra(EXTRA_ROOM_ID).orEmpty() }
    private val roomName: String by lazy { intent.getStringExtra(EXTRA_ROOM_NAME).orEmpty() }
    private val isLocked: Boolean by lazy { intent.getBooleanExtra(EXTRA_ROOM_LOCKED, false) }
    private var memberId: String? = null
    private var leaveRequested = false
    private var currentPlaybackState: PlaybackStateDto? = null
    private var sliderBeingDragged = false
    private var exoPlayer: ExoPlayer? = null
    private var currentStreamUrl: String? = null
    private val playbackTickerHandler = Handler(Looper.getMainLooper())
    private val playbackTicker = object : Runnable {
        override fun run() {
            tickPlaybackProgress()
            schedulePlaybackTicker()
        }
    }
    private var sliderBasePositionSeconds = 0f
    private var sliderBaseTimestamp = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        binding = ActivityRoomDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.roomNameTitle.text = roomName

        val cachedSession = RoomSessionStore.getSession(this, roomId)
        if (cachedSession != null) {
            if (RoomSessionStore.isSameProcess(cachedSession)) {
                memberId = cachedSession.memberId
            } else {
                lifecycleScope.launch { cleanupStaleMembership(cachedSession.memberId) }
            }
        }
        binding.leaveButton.isEnabled = memberId != null
        binding.backButton.setOnClickListener { leaveRoomAndFinish() }
        onBackPressedDispatcher.addCallback(this) { leaveRoomAndFinish() }
        binding.leaveButton.setOnClickListener { leaveRoomAndFinish() }
        binding.addSongButton.setOnClickListener { promptAddSong() }
        setupPlaybackControls()

        binding.queueRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@RoomDetailActivity)
            adapter = queueAdapter
        }

        lifecycleScope.launch {
            joinRoomIfNeeded()
            refreshPlaybackState()
            refreshQueue()
        }
    }

    override fun onStart() {
        super.onStart()
        playbackSocket.connect(
            onState = { state -> runOnUiThread { applyPlaybackState(state) } },
            onError = { error ->
                runOnUiThread {
                    Toast.makeText(
                        this,
                        error.message ?: getString(R.string.playback_controls_error),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }

    override fun onStop() {
        playbackSocket.disconnect()
        stopPlaybackTicker()
        exoPlayer?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        exoPlayer?.release()
        exoPlayer = null
        super.onDestroy()
    }

    private suspend fun joinRoomIfNeeded() {
        if (roomId.isEmpty()) {
            Toast.makeText(this, getString(R.string.room_not_found), Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        if (memberId != null) return
        if (isLocked) {
            promptCredentialsAndJoin()
            return
        } else {
            attemptJoin()
        }
    }

    private fun promptCredentialsAndJoin() {
        val sheet = JoinRoomBottomSheet(
            context = this,
            roomName = roomName,
            onJoinRequested = { passcode, inviteCode, onSuccess, onError ->
                lifecycleScope.launch {
                    repository.joinRoom(
                        roomId = roomId,
                        displayName = UserIdentity.getDisplayName(this@RoomDetailActivity),
                        passcode = passcode,
                        inviteCode = inviteCode
                    ).onSuccess { response ->
                        memberId = response.member.id
                        RoomSessionStore.saveMemberId(this@RoomDetailActivity, roomId, response.member.id)
                        binding.leaveButton.isEnabled = true
                        Toast.makeText(
                            this@RoomDetailActivity,
                            getString(R.string.joined_as_template, response.member.displayName),
                            Toast.LENGTH_SHORT
                        ).show()
                        onSuccess(response)
                    }.onFailure { error ->
                        onError(error)
                    }
                }
            },
            onCancelled = { finish() }
        )
        sheet.show()
    }

    private suspend fun attemptJoin(passcode: String? = null, inviteCode: String? = null) {
        binding.leaveButton.isEnabled = false
        repository.joinRoom(
            roomId = roomId,
            displayName = UserIdentity.getDisplayName(this),
            passcode = passcode,
            inviteCode = inviteCode
        ).onSuccess { response ->
            memberId = response.member.id
            RoomSessionStore.saveMemberId(this, roomId, response.member.id)
            binding.leaveButton.isEnabled = true
            Toast.makeText(
                this,
                getString(R.string.joined_as_template, response.member.displayName),
                Toast.LENGTH_SHORT
            ).show()
        }.onFailure { error ->
            Toast.makeText(
                this,
                error.message ?: getString(R.string.room_join_failed),
                Toast.LENGTH_LONG
            ).show()
            if (isLocked) {
                promptCredentialsAndJoin()
            }
        }
    }

    private suspend fun leaveRoom() {
        val id = memberId ?: RoomSessionStore.getSession(this, roomId)?.memberId ?: return
        repository.leaveRoom(roomId, id)
            .onFailure {
                Toast.makeText(this, getString(R.string.leaving_room_error), Toast.LENGTH_SHORT).show()
            }
        memberId = null
        binding.leaveButton.isEnabled = false
        RoomSessionStore.clearMemberId(this, roomId)
    }

    private fun leaveRoomAndFinish() {
        if (leaveRequested) return
        leaveRequested = true
        binding.leaveButton.isEnabled = false
        lifecycleScope.launch {
            leaveRoom()
            finish()
        }
    }

    private suspend fun cleanupStaleMembership(staleMemberId: String) {
        repository.leaveRoom(roomId, staleMemberId)
            .onFailure { error ->
                Log.w(TAG, "Failed to clean up stale membership", error)
            }
        RoomSessionStore.clearMemberId(this, roomId)
    }

    private suspend fun refreshPlaybackState() {
        repository.getPlaybackState().onSuccess { state ->
            applyPlaybackState(state)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: getString(R.string.loading), Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun refreshQueue() {
        binding.queueLoading.isVisible = true
        repository.getQueue()
            .onSuccess { queue ->
                updateQueueFromState(queue.queue)
                if (queue.queue.isNotEmpty()) {
                    binding.queueEmptyState.text = getString(R.string.queue_empty)
                }
            }
            .onFailure { error ->
                binding.queueEmptyState.isVisible = true
                binding.queueEmptyState.text = error.message ?: getString(R.string.queue_empty)
            }
        binding.queueLoading.isVisible = false
    }

    private suspend fun promoteTrack(position: Int) {
        repository.reorderQueue(position, 0)
            .onSuccess {
                Toast.makeText(this, R.string.queue_updated, Toast.LENGTH_SHORT).show()
                refreshQueue()
            }
            .onFailure {
                Toast.makeText(this, it.message ?: getString(R.string.queue_update_failed), Toast.LENGTH_SHORT).show()
            }
    }

    private suspend fun removeTrack(position: Int) {
        repository.removeFromQueue(position)
            .onSuccess {
                Toast.makeText(this, R.string.queue_updated, Toast.LENGTH_SHORT).show()
                refreshQueue()
            }
            .onFailure {
                Toast.makeText(this, it.message ?: getString(R.string.queue_update_failed), Toast.LENGTH_SHORT).show()
            }
    }

    private fun promptAddSong() {
        val sheet = AddSongBottomSheet(
            context = this,
            onSearch = { query, onResults, onError ->
                lifecycleScope.launch {
                    repository.search(query)
                        .onSuccess { response ->
                            onResults(response.tracks)
                        }
                        .onFailure { error ->
                            onError(error)
                        }
                }
            },
            onPlayNow = { track ->
                lifecycleScope.launch {
                    repository.playTrack(track.id, track.provider)
                        .onSuccess {
                            Toast.makeText(this@RoomDetailActivity, R.string.now_playing_label, Toast.LENGTH_SHORT).show()
                            applyPlaybackState(it)
                        }
                        .onFailure { showPlaybackError(it) }
                }
            },
            onAddToQueue = { track ->
                lifecycleScope.launch {
                    repository.addToQueue(
                        track.id,
                        track.provider,
                        UserIdentity.getDisplayName(this@RoomDetailActivity)
                    ).onSuccess {
                        Toast.makeText(this@RoomDetailActivity, R.string.track_added, Toast.LENGTH_SHORT).show()
                        refreshQueue()
                    }.onFailure {
                        Toast.makeText(
                            this@RoomDetailActivity,
                            it.message ?: getString(R.string.queue_update_failed),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        )
        sheet.show()
    }

    private fun setupPlaybackControls() {
        binding.playPauseButton.setOnClickListener { lifecycleScope.launch { togglePlayPause() } }
        binding.nextButton.setOnClickListener { lifecycleScope.launch { skipTrack() } }
        binding.previousButton.setOnClickListener { lifecycleScope.launch { previousTrack() } }
        binding.shuffleButton.setOnClickListener { lifecycleScope.launch { shuffleQueue() } }
        binding.repeatButton.setOnClickListener { lifecycleScope.launch { restartTrack() } }
        binding.playbackSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                binding.playbackElapsed.text = formatTime(value.toInt())
            }
        }
        binding.playbackSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                sliderBeingDragged = true
            }

            override fun onStopTrackingTouch(slider: Slider) {
                sliderBeingDragged = false
                lifecycleScope.launch { seekTo(slider.value.toInt()) }
            }
        })
    }

    private fun applyPlaybackState(state: PlaybackStateDto) {
        currentPlaybackState = state
        sliderBasePositionSeconds = state.positionSeconds.toFloat()
        sliderBaseTimestamp = SystemClock.elapsedRealtime()
        val track = state.currentTrack
        binding.currentSongTitle.text = track?.title ?: getString(R.string.no_track_playing)
        binding.currentSongArtist.text = track?.artist ?: getString(R.string.no_track_artist)
        binding.nowPlayingLabel.text = if (state.isPlaying) getString(R.string.now_playing_label) else getString(R.string.paused_label)
        binding.playPauseButton.setImageResource(if (state.isPlaying) R.drawable.ic_pause_large else R.drawable.ic_play_circle)

        if (!track?.thumbnailUrl.isNullOrBlank()) {
            binding.albumArtImage.load(track.thumbnailUrl) {
                placeholder(R.drawable.album_placeholder)
                error(R.drawable.album_placeholder)
            }
            binding.currentSongImage.load(track.thumbnailUrl) {
                placeholder(R.drawable.album_placeholder)
                error(R.drawable.album_placeholder)
            }
        } else {
            binding.albumArtImage.setImageResource(R.drawable.album_placeholder)
            binding.currentSongImage.setImageResource(R.drawable.album_placeholder)
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

        updateQueueFromState(state.queue)
        ensurePlayerForState(state)
        if (state.isPlaying) {
            schedulePlaybackTicker()
        } else {
            stopPlaybackTicker()
        }
    }

    private suspend fun togglePlayPause() {
        val result = if (currentPlaybackState?.isPlaying == true) {
            repository.pause()
        } else {
            repository.resume()
        }
        result.onSuccess { applyPlaybackState(it) }.onFailure { showPlaybackError(it) }
    }

    private suspend fun skipTrack() {
        repository.next().onSuccess { applyPlaybackState(it) }.onFailure { showPlaybackError(it) }
    }

    private suspend fun previousTrack() {
        repository.previous().onSuccess { applyPlaybackState(it) }.onFailure { showPlaybackError(it) }
    }

    private suspend fun restartTrack() {
        repository.seekTo(0)
            .onSuccess {
                Toast.makeText(this, R.string.restart_track_success, Toast.LENGTH_SHORT).show()
                applyPlaybackState(it)
            }
            .onFailure { showPlaybackError(it) }
    }

    private suspend fun shuffleQueue() {
        repository.shuffleQueue()
            .onSuccess {
                Toast.makeText(this, R.string.shuffle_success, Toast.LENGTH_SHORT).show()
                refreshQueue()
            }
            .onFailure { showPlaybackError(it) }
    }

    private suspend fun seekTo(position: Int) {
        repository.seekTo(position)
            .onSuccess { applyPlaybackState(it) }
            .onFailure { showPlaybackError(it) }
    }

    private fun ensurePlayerForState(state: PlaybackStateDto) {
        val streamUrl = state.streamUrl ?: return
        val player = exoPlayer ?: ExoPlayer.Builder(this).build().also { exoPlayer = it }
        if (currentStreamUrl != streamUrl) {
            player.setMediaItem(MediaItem.fromUri(streamUrl))
            player.prepare()
            currentStreamUrl = streamUrl
        }
        player.playWhenReady = state.isPlaying
    }

    private fun schedulePlaybackTicker() {
        playbackTickerHandler.removeCallbacks(playbackTicker)
        if (currentPlaybackState?.isPlaying == true) {
            playbackTickerHandler.postDelayed(playbackTicker, 1000)
        }
    }

    private fun stopPlaybackTicker() {
        playbackTickerHandler.removeCallbacks(playbackTicker)
    }

    private fun tickPlaybackProgress() {
        val state = currentPlaybackState ?: return
        if (!state.isPlaying || sliderBeingDragged) {
            return
        }
        val duration = state.currentTrack?.durationSeconds?.takeIf { it > 0 }
            ?: binding.playbackSlider.valueTo.toInt().coerceAtLeast(1)
        val elapsed = sliderBasePositionSeconds + ((SystemClock.elapsedRealtime() - sliderBaseTimestamp) / 1000f)
        val clamped = elapsed.coerceIn(0f, duration.toFloat())
        
        // Round to nearest integer to match slider stepSize(1.0)
        val rounded = kotlin.math.round(clamped)
        binding.playbackSlider.value = rounded
        binding.playbackElapsed.text = formatTime(rounded.toInt())
        
        sliderBasePositionSeconds = clamped
        sliderBaseTimestamp = SystemClock.elapsedRealtime()
    }

    private fun updateQueueFromState(tracks: List<TrackDto>) {
        queueAdapter.submitList(tracks)
        binding.queueEmptyState.isVisible = tracks.isEmpty()
        binding.queueLoading.isVisible = false
    }

    private fun showPlaybackError(error: Throwable) {
        Toast.makeText(
            this,
            error.message ?: getString(R.string.playback_controls_error),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun formatTime(seconds: Int): String {
        val safeSeconds = seconds.coerceAtLeast(0)
        val minutes = safeSeconds / 60
        val remaining = safeSeconds % 60
        return String.format("%d:%02d", minutes, remaining)
    }

    companion object {
        const val EXTRA_ROOM_ID = "extra_room_id"
        const val EXTRA_ROOM_NAME = "extra_room_name"
        const val EXTRA_ROOM_LOCKED = "extra_room_locked"
        private const val TAG = "RoomDetailActivity"
    }
}
