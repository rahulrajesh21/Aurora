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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.MediaItem
import kotlinx.coroutines.launch

class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private val repository = AuroraServiceLocator.repository
    private val playbackSocket by lazy { AuroraServiceLocator.createPlaybackSocket() }
    private var currentState: PlaybackStateDto? = null
    private var exoPlayer: ExoPlayer? = null
    private var currentStreamUrl: String? = null
    private val playbackTickerHandler = Handler(Looper.getMainLooper())
    private val playbackTicker = object : Runnable {
        override fun run() {
            tickPlaybackPosition()
            schedulePlaybackTicker()
        }
    }
    private var basePositionSeconds = 0f
    private var baseTimestamp = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }
        binding.favoriteButton.setOnClickListener {
            Toast.makeText(this, R.string.feature_coming_soon, Toast.LENGTH_SHORT).show()
        }
        binding.playPauseButton.setOnClickListener { lifecycleScope.launch { togglePlayPause() } }
        binding.nextButton.setOnClickListener { lifecycleScope.launch { skipTrack() } }
        binding.previousButton.setOnClickListener { lifecycleScope.launch { previousTrack() } }
        binding.shuffleButton.setOnClickListener { lifecycleScope.launch { shuffleQueue() } }
        binding.repeatButton.setOnClickListener { lifecycleScope.launch { restartTrack() } }

        binding.lyricsButton.setOnClickListener {
            binding.lyricsOverlay.isVisible = true
        }
        binding.closeLyricsButton.setOnClickListener {
            binding.lyricsOverlay.isVisible = false
        }

        lifecycleScope.launch { refreshPlaybackState() }
    }

    override fun onStart() {
        super.onStart()
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

    private suspend fun refreshPlaybackState() {
        repository.getPlaybackState()
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
        binding.currentTime.text = formatTime(state.positionSeconds)
        val duration = track?.durationSeconds ?: state.positionSeconds
        binding.totalTime.text = formatTime(duration)
        ensurePlayerForState(state)
        if (state.isPlaying) {
            schedulePlaybackTicker()
        } else {
            stopPlaybackTicker()
        }
    }

    private suspend fun togglePlayPause() {
        val result = if (currentState?.isPlaying == true) {
            repository.pause()
        } else {
            repository.resume()
        }
        result
            .onSuccess { applyState(it) }
            .onFailure { showControlError(it) }
    }

    private suspend fun skipTrack() {
        repository.next()
            .onSuccess { applyState(it) }
            .onFailure { showControlError(it) }
    }

    private suspend fun previousTrack() {
        repository.previous()
            .onSuccess { applyState(it) }
            .onFailure { showControlError(it) }
    }

    private suspend fun shuffleQueue() {
        repository.shuffleQueue()
            .onSuccess {
                Toast.makeText(this, R.string.shuffle_success, Toast.LENGTH_SHORT).show()
                refreshPlaybackState()
            }
            .onFailure { showControlError(it) }
    }

    private suspend fun restartTrack() {
        repository.seekTo(0)
            .onSuccess {
                Toast.makeText(this, R.string.restart_track_success, Toast.LENGTH_SHORT).show()
                applyState(it)
            }
            .onFailure { showControlError(it) }
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

    companion object {
        const val EXTRA_SONG_TITLE = "extra_song_title"
        const val EXTRA_ARTIST_NAME = "extra_artist_name"
        const val EXTRA_TRACK_ID = "extra_track_id"
        const val EXTRA_PROVIDER = "extra_provider"
        const val EXTRA_TRANSITION_NAME = "extra_transition_name"
    }
}
