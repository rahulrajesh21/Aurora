package com.example.music_room

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import coil.load
import com.example.music_room.data.AuroraServiceLocator
import com.example.music_room.data.remote.model.PlaybackStateDto
import com.example.music_room.databinding.ActivityPlayerBinding
import com.example.music_room.service.MediaServiceManager
import com.example.music_room.utils.PermissionUtils
import kotlinx.coroutines.launch
import com.example.music_room.R
import androidx.annotation.StringRes
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.music_room.data.repository.SyncedLyrics
import com.example.music_room.ui.LyricsAdapter
import com.example.music_room.data.manager.PlayerTelemetryManager
import com.example.music_room.data.remote.model.LyricsResponseDto
import com.example.music_room.data.repository.SyncedLyricLine
import com.example.music_room.data.repository.SyncedLyricPart

class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private val repository = AuroraServiceLocator.repository
    private val roomId by lazy { intent.getStringExtra(EXTRA_ROOM_ID) }
    private val playbackSocket by lazy { 
        val id = roomId ?: throw IllegalStateException("Room ID missing")
        AuroraServiceLocator.createPlaybackSocket(id) 
    }
    private var currentState: PlaybackStateDto? = null
    private lateinit var mediaServiceManager: MediaServiceManager
    private val playbackTickerHandler = Handler(Looper.getMainLooper())
    private val playbackTicker = object : Runnable {
        override fun run() {
            tickPlaybackPosition()
            schedulePlaybackTicker()
        }
    }
    private var basePositionSeconds = 0f
    private var baseTimestamp = 0L

    private val telemetryManager by lazy { PlayerTelemetryManager(playbackSocket) }
    private val lyricsRepository = AuroraServiceLocator.lyricsRepository
    private var currentLyricsJob: Job? = null
    private var lastFetchedTrackId: String? = null
    private val lyricsAdapter = LyricsAdapter()
    private var syncedLyrics: SyncedLyrics? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (roomId == null) {
            Toast.makeText(this, "Error: No room ID provided", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        binding.backButton.setOnClickListener { finish() }
        binding.favoriteButton.setOnClickListener {
            Toast.makeText(this, R.string.feature_coming_soon, Toast.LENGTH_SHORT).show()
        }
        binding.playPauseButton.setOnClickListener { lifecycleScope.launch { togglePlayPause() } }
        binding.nextButton.setOnClickListener { lifecycleScope.launch { skipTrack() } }
        binding.previousButton.setOnClickListener { lifecycleScope.launch { previousTrack() } }
        binding.shuffleButton.setOnClickListener { lifecycleScope.launch { shuffleQueue() } }
        binding.repeatButton.setOnClickListener { lifecycleScope.launch { restartTrack() } }

        binding.lyricsRecycler.apply {
            layoutManager = LinearLayoutManager(this@PlayerActivity)
            adapter = lyricsAdapter
            itemAnimator = null
        }

        binding.lyricsButton.setOnClickListener {
            binding.lyricsOverlay.isVisible = true
            syncedLyrics?.let { updateLyricsForPosition(basePositionSeconds, immediate = true) }
        }
        binding.closeLyricsButton.setOnClickListener {
            binding.lyricsOverlay.isVisible = false
        }

        // Initialize background media service
        mediaServiceManager = MediaServiceManager.getInstance(this)

        lifecycleScope.launch { refreshPlaybackState() }
    }

    override fun onStart() {
        super.onStart()
        
        // Request notification permission and start background service
        if (PermissionUtils.requestNotificationPermissionIfNeeded(this)) {
            startBackgroundService()
        }

        playbackSocket.setLyricsListener { response ->
            runOnUiThread {
                handleLyricsUpdate(response)
            }
        }

        playbackSocket.connect(
            onState = { state -> runOnUiThread { applyState(state) } },
            onError = { error ->
                runOnUiThread {
                    Toast.makeText(
                        this,
                        error.message ?: getString(R.string.player_error),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }
    
    private fun startBackgroundService() {
        roomId?.let { mediaServiceManager.startPlaybackService(it) }
    }

    override fun onStop() {
        playbackSocket.disconnect()
        stopPlaybackTicker()
        telemetryManager.detachPlayer()
        super.onStop()
    }

    override fun onDestroy() {
        currentLyricsJob?.cancel()
        currentLyricsJob = null
        telemetryManager.detachPlayer()
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

    private suspend fun refreshPlaybackState() {
        val id = roomId ?: return
        repository.getPlaybackState(id)
            .onSuccess { applyState(it) }
            .onFailure {
                Toast.makeText(this, it.message ?: getString(R.string.player_error), Toast.LENGTH_SHORT).show()
            }
    }

    private fun applyState(state: PlaybackStateDto) {
        currentState = state
        basePositionSeconds = state.positionSeconds.toFloat()
        baseTimestamp = SystemClock.elapsedRealtime()
        val track = state.currentTrack
        binding.songTitle.text = track?.title ?: getString(R.string.no_track_playing)
        binding.artistName.text = track?.artist ?: getString(R.string.no_track_artist)
        binding.nowPlayingText.text = if (state.isPlaying) {
            getString(R.string.now_playing_label)
        } else {
            getString(R.string.paused_label)
        }
        binding.playPauseButton.setImageResource(
            if (state.isPlaying) R.drawable.ic_pause_large else R.drawable.ic_play_circle
        )
        val transitionName = intent.getStringExtra(EXTRA_TRANSITION_NAME)
        if (transitionName != null) {
            binding.albumArtImage.transitionName = transitionName
            supportPostponeEnterTransition()
        }

        if (!track?.thumbnailUrl.isNullOrBlank()) {
            binding.albumArtImage.load(track?.thumbnailUrl) {
                placeholder(R.drawable.album_placeholder)
                error(R.drawable.album_placeholder)
                listener(
                    onSuccess = { _, _ -> supportStartPostponedEnterTransition() },
                    onError = { _, _ -> supportStartPostponedEnterTransition() }
                )
            }
        } else {
            binding.albumArtImage.setImageResource(R.drawable.album_placeholder)
            supportStartPostponedEnterTransition()
        }
        binding.currentTime.text = formatTime(state.positionSeconds.toInt())
        val duration = track?.durationSeconds ?: state.positionSeconds.toInt()
        binding.totalTime.text = formatTime(duration)
        
        // Background service handles playback
        telemetryManager.updateCurrentTrack(track)

        if (state.isPlaying) {
            schedulePlaybackTicker()
        } else {
            stopPlaybackTicker()
        }

        syncedLyrics?.let { updateLyricsForPosition(basePositionSeconds, immediate = false) }
    }

    private fun handleLyricsUpdate(response: LyricsResponseDto) {
        val lines = response.lyrics.map { line ->
            SyncedLyricLine(
                startTimeMs = line.startTimeMs,
                durationMs = line.durationMs,
                words = line.words,
                translation = line.translation?.text,
                romanization = line.romanization,
                parts = line.parts?.map { part ->
                    SyncedLyricPart(
                        startTimeMs = part.startTimeMs,
                        durationMs = part.durationMs,
                        words = part.words,
                        isBackground = part.isBackground == true
                    )
                } ?: emptyList()
            )
        }

        if (lines.isNotEmpty()) {
            syncedLyrics = SyncedLyrics(
                lines = lines,
                sourceLabel = response.source ?: "Better Lyrics",
                sourceUrl = response.sourceHref
            )
            lyricsAdapter.submitLines(lines)
            binding.lyricsRecycler.isVisible = true
            binding.lyricsMessage.isVisible = false
            updateLyricsForPosition(basePositionSeconds, immediate = false)
        } else {
            showLyricsMessage(R.string.lyrics_not_found)
        }
    }

    private fun fetchLyrics(title: String?, artist: String?, duration: Int, videoId: String?) {
        if (title == null || artist == null) return

        currentLyricsJob?.cancel()
        binding.lyricsRecycler.isVisible = false
        binding.lyricsMessage.isVisible = false
        lyricsAdapter.clear()
        syncedLyrics = null
        currentLyricsJob = lifecycleScope.launch {
            binding.lyricsLoading.isVisible = true
            binding.lyricsMessage.isVisible = false

            delay(500)

            val result = lyricsRepository.getLyrics(title, artist, duration, videoId)

            binding.lyricsLoading.isVisible = false

            result.onSuccess { response ->
                if (response.lines.isEmpty()) {
                    showLyricsMessage(R.string.lyrics_not_found)
                } else {
                    syncedLyrics = response
                    lyricsAdapter.submitLines(response.lines)
                    binding.lyricsRecycler.isVisible = true
                    binding.lyricsMessage.isVisible = false
                    updateLyricsForPosition(basePositionSeconds, immediate = false)
                }
            }.onFailure {
                showLyricsError()
            }
        }
    }

    private fun showLyricsMessage(@StringRes messageRes: Int) {
        binding.lyricsMessage.text = getString(messageRes)
        binding.lyricsMessage.isVisible = true
        binding.lyricsRecycler.isVisible = false
        syncedLyrics = null
        lyricsAdapter.clear()
    }

    private fun showLyricsError() {
        showLyricsMessage(R.string.lyrics_not_found)
    }

    private suspend fun togglePlayPause() {
        val id = roomId ?: return
        val result = if (currentState?.isPlaying == true) {
            val positionSeconds = resolveAccuratePlaybackPositionSeconds()
            repository.pause(id, positionSeconds)
        } else {
            repository.resume(id)
        }
        result
            .onSuccess { applyState(it) }
            .onFailure { showControlError(it) }
    }

    private suspend fun skipTrack() {
        val id = roomId ?: return
        repository.next(id)
            .onSuccess { applyState(it) }
            .onFailure { showControlError(it) }
    }

    private suspend fun previousTrack() {
        val id = roomId ?: return
        repository.previous(id)
            .onSuccess { applyState(it) }
            .onFailure { showControlError(it) }
    }

    private suspend fun shuffleQueue() {
        val id = roomId ?: return
        repository.shuffleQueue(id)
            .onSuccess {
                Toast.makeText(this, R.string.shuffle_success, Toast.LENGTH_SHORT).show()
                refreshPlaybackState()
            }
            .onFailure { showControlError(it) }
    }

    private suspend fun restartTrack() {
        val id = roomId ?: return
        repository.seekTo(id, 0)
            .onSuccess {
                Toast.makeText(this, R.string.restart_track_success, Toast.LENGTH_SHORT).show()
                applyState(it)
            }
            .onFailure { showControlError(it) }
    }

    private fun schedulePlaybackTicker() {
        playbackTickerHandler.removeCallbacks(playbackTicker)
        if (currentState?.isPlaying == true) {
            playbackTickerHandler.postDelayed(playbackTicker, 1000)
        }
    }

    private fun stopPlaybackTicker() {
        playbackTickerHandler.removeCallbacks(playbackTicker)
    }

    private fun tickPlaybackPosition() {
        val state = currentState ?: return
        if (!state.isPlaying) {
            return
        }
        val elapsed = basePositionSeconds + ((SystemClock.elapsedRealtime() - baseTimestamp) / 1000f)
        val clamped = elapsed.coerceAtLeast(0f)
        binding.currentTime.text = formatTime(clamped.toInt())
        
        basePositionSeconds = clamped
        baseTimestamp = SystemClock.elapsedRealtime()
        updateLyricsForPosition(clamped, immediate = binding.lyricsOverlay.isVisible)
    }

    private fun updateLyricsForPosition(positionSeconds: Float, immediate: Boolean) {
        val lyrics = syncedLyrics ?: return
        if (lyrics.lines.isEmpty()) return

        val index = lyricsAdapter.updatePlaybackPosition((positionSeconds * 1000L).toLong())
        if (index != -1 && binding.lyricsOverlay.isVisible) {
            scrollLyricsTo(index, immediate)
        }
    }

    private fun scrollLyricsTo(index: Int, immediate: Boolean) {
        if (index !in 0 until lyricsAdapter.itemCount) return
        val layoutManager = binding.lyricsRecycler.layoutManager as? LinearLayoutManager ?: return
        if (immediate) {
            layoutManager.scrollToPositionWithOffset(index, binding.lyricsRecycler.height / 2)
        } else {
            binding.lyricsRecycler.smoothScrollToPosition(index)
        }
    }

    private fun showControlError(error: Throwable) {
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
        return String.format("%02d:%02d", minutes, remaining)
    }

    private fun resolveAccuratePlaybackPositionSeconds(): Double {
        val state = currentState
        if (state == null) {
            return basePositionSeconds.toDouble()
        }
        if (!state.isPlaying) {
            return basePositionSeconds.toDouble()
        }
        val elapsedSeconds = (SystemClock.elapsedRealtime() - baseTimestamp) / 1000f
        val position = (basePositionSeconds + elapsedSeconds).coerceAtLeast(0f)
        return position.toDouble()
    }

    companion object {
        const val EXTRA_ROOM_ID = "extra_room_id"
        const val EXTRA_SONG_TITLE = "extra_song_title"
        const val EXTRA_ARTIST_NAME = "extra_artist_name"
        const val EXTRA_TRACK_ID = "extra_track_id"
        const val EXTRA_PROVIDER = "extra_provider"
        const val EXTRA_TRANSITION_NAME = "extra_transition_name"
    }
}
