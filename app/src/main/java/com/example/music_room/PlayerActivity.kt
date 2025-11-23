package com.example.music_room

import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.CompositePageTransformer
import androidx.viewpager2.widget.MarginPageTransformer
import androidx.viewpager2.widget.ViewPager2
import coil.load
import com.example.music_room.data.AuroraServiceLocator
import com.example.music_room.data.manager.PlayerTelemetryManager
import com.example.music_room.data.remote.model.LyricsResponseDto
import com.example.music_room.data.remote.model.PlaybackStateDto
import com.example.music_room.data.remote.model.TrackDto
import com.example.music_room.data.repository.SyncedLyrics
import com.example.music_room.data.repository.toSyncedLyrics
import com.example.music_room.databinding.ActivityPlayerBinding
import com.example.music_room.databinding.ItemQueueCarouselBinding
import com.example.music_room.service.MediaServiceManager
import com.example.music_room.ui.LyricsAdapter
import com.example.music_room.utils.PermissionUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import androidx.core.graphics.ColorUtils
import coil.Coil
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale
import com.example.music_room.utils.PaletteThemeHelper
import kotlin.math.abs
import kotlin.math.roundToInt

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
    
    private val queueAdapter = QueueAdapter()
    private var queueRecyclerView: RecyclerView? = null
    private val carouselPadding by lazy { resources.getDimensionPixelOffset(R.dimen.carousel_padding) }
    private val carouselPageMargin by lazy { resources.getDimensionPixelOffset(R.dimen.carousel_page_margin) }
    private var isUserSeeking = false
    
    // Carousel Animation State
    private var pendingNewState: PlaybackStateDto? = null
    private var isAnimatingTrackChange = false

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

        setupHeader()
        setupCarousel()
        setupLyrics()
        setupProgressBar()

        // Initialize background media service
        mediaServiceManager = MediaServiceManager.getInstance(this)

        lifecycleScope.launch { refreshPlaybackState() }
    }

    private fun setupHeader() {
        binding.backButton.setOnClickListener { finish() }
        binding.shareButton.setOnClickListener { 
             // Placeholder for share/add
             Toast.makeText(this, "Share/Add feature coming soon", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupCarousel() {
        binding.queueCarousel.apply {
            adapter = queueAdapter
            clipToPadding = false
            clipChildren = false
            offscreenPageLimit = 3
            queueRecyclerView = getChildAt(0) as? RecyclerView
            queueRecyclerView?.apply {
                clipToPadding = false
                clipChildren = false
                overScrollMode = RecyclerView.OVER_SCROLL_NEVER
                setPadding(carouselPadding, 0, carouselPadding, 0)
            }

            setPageTransformer(createCarouselTransformer())

            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    super.onPageSelected(position)
                    // Browse Mode: Update title/artist but DO NOT skip track automatically
                    val adapter = adapter as? QueueAdapter ?: return
                    val track = adapter.getItem(position) ?: return
                    
                    // Only update text if we are NOT currently playing (or if it's the current track)
                    // Actually, we want to show the browsed song's info
                    binding.songTitle.text = track.title
                    val displayArtist = track.artist
                    binding.artistName.text = displayArtist.takeIf { !it.isNullOrBlank() }
                        ?: getString(R.string.no_track_artist)
                }

                override fun onPageScrollStateChanged(state: Int) {
                    super.onPageScrollStateChanged(state)
                    if (state == ViewPager2.SCROLL_STATE_IDLE && isAnimatingTrackChange) {
                        isAnimatingTrackChange = false
                        pendingNewState?.let { newState ->
                            updateCarouselList(newState)
                            pendingNewState = null
                        }
                    }
                }
            })
        }
    }

    private fun createCarouselTransformer(): CompositePageTransformer {
        return CompositePageTransformer().apply {
            addTransformer(MarginPageTransformer(carouselPageMargin))
            addTransformer { page, position ->
                // If we are programmatically animating a track change, disable the shrink/fade effect
                // so the incoming card looks "active" immediately.
                if (isAnimatingTrackChange) {
                    page.scaleY = 1f
                    page.scaleX = 1f
                    page.alpha = 1f
                    page.translationX = 0f
                    return@addTransformer
                }

                val clampedPosition = position.coerceIn(-1f, 1f)
                val focusProgress = 1 - abs(clampedPosition)
                val minScale = 0.75f
                val scale = minScale + focusProgress * (1f - minScale)
                page.scaleY = scale
                page.scaleX = scale
                page.alpha = 0.35f + focusProgress * 0.65f
                // Increased overlap factor from 0.18f to 0.25f to bring side items closer
                page.translationX = -clampedPosition * page.width * 0.25f
            }
        }
    }

    private fun updateCarouselDecor(itemCount: Int) {
        val recyclerView = queueRecyclerView ?: return
        if (itemCount > 1) {
            recyclerView.clipToPadding = false
            recyclerView.setPadding(carouselPadding, 0, carouselPadding, 0)
            binding.queueCarousel.offscreenPageLimit = 3
        } else {
            recyclerView.clipToPadding = true
            recyclerView.setPadding(0, 0, 0, 0)
            // Prevent ghost views when list is small
            binding.queueCarousel.offscreenPageLimit = 1
        }
        binding.queueCarousel.isUserInputEnabled = itemCount > 1
    }

    private fun setupLyrics() {
        binding.lyricsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@PlayerActivity)
            adapter = lyricsAdapter
        }
    }

    private fun setupProgressBar() {
        // Progress bar temporarily disabled in layout for debugging
        val progressBar = binding.root.findViewById<SeekBar?>(R.id.progressBar) ?: return
        progressBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    // Progress is in milliseconds
                    val seconds = progress / 1000
                    binding.currentTime.text = formatTime(seconds)
                    
                    // Update thumb alignment while dragging
                    val max = seekBar?.max ?: 1
                    val fraction = if (max > 0) progress.toFloat() / max else 0f
                    (seekBar?.thumb as? com.example.music_room.ui.ProgressPillDrawable)?.setProgressFraction(fraction)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = false
                val progressMs = seekBar?.progress ?: 0
                val progressSeconds = progressMs / 1000
                lifecycleScope.launch {
                    val id = roomId ?: return@launch
                    repository.seekTo(id, progressSeconds)
                }
            }
        })
    }

    override fun onStart() {
        super.onStart()
        if (PermissionUtils.requestNotificationPermissionIfNeeded(this)) {
            startBackgroundService()
        }

        playbackSocket.setLyricsListener { response ->
            runOnUiThread { handleLyricsUpdate(response) }
        }

        playbackSocket.connect(
            onState = { state -> runOnUiThread { applyState(state) } },
            onError = { error ->
                runOnUiThread {
                    Toast.makeText(this, error.message ?: getString(R.string.player_error), Toast.LENGTH_SHORT).show()
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        PermissionUtils.handlePermissionResult(
            requestCode, permissions, grantResults,
            onGranted = { startBackgroundService() },
            onDenied = { Toast.makeText(this, "Permission required", Toast.LENGTH_LONG).show() }
        )
    }

    private suspend fun refreshPlaybackState() {
        val id = roomId ?: return
        repository.getPlaybackState(id)
            .onSuccess { applyState(it) }
            .onFailure { Toast.makeText(this, it.message, Toast.LENGTH_SHORT).show() }
    }

    private fun applyState(state: PlaybackStateDto) {
        val oldState = currentState
        currentState = state
        basePositionSeconds = state.positionSeconds.toFloat()
        baseTimestamp = SystemClock.elapsedRealtime()
        
        val track = state.currentTrack
        val artistForDisplay = track?.artist?.takeIf { it.isNotBlank() }
        
        // Only update text if we are NOT browsing (or if it's the first load)
        if (binding.queueCarousel.currentItem == 0) {
            binding.songTitle.text = track?.title ?: getString(R.string.no_track_playing)
            binding.artistName.text = artistForDisplay ?: getString(R.string.no_track_artist)
        }
        
        // Update Progress Bar (if present in layout)
        val duration = track?.durationSeconds ?: 0
        binding.totalTime.text = formatTime(duration)
        binding.root.findViewById<SeekBar?>(R.id.progressBar)?.let { bar ->
            // Use milliseconds for smooth progress
            bar.max = duration * 1000
            if (!isUserSeeking) {
                bar.progress = (state.positionSeconds * 1000).toInt()
            }
        }
        if (!isUserSeeking) {
            binding.currentTime.text = formatTime(state.positionSeconds.toInt())
        }

        // Background service handles playback
        telemetryManager.updateCurrentTrack(track)

        if (state.isPlaying) {
            schedulePlaybackTicker()
            binding.queueCarousel.alpha = 1.0f
        } else {
            stopPlaybackTicker()
            binding.queueCarousel.alpha = 0.5f
        }

        syncedLyrics?.let { updateLyricsForPosition(basePositionSeconds) }
        
        // Fetch lyrics if changed
        val artistForLyrics = artistForDisplay
        if (track?.id != null && track.id != lastFetchedTrackId && !artistForLyrics.isNullOrBlank()) {
            lastFetchedTrackId = track.id
            fetchLyrics(track.title, artistForLyrics, track.durationSeconds, track.id)
            
            // Use track thumbnail directly
            updateTheme(track.thumbnailUrl)
        }
        
        // Aggressive Prefetch: Coil handles caching automatically
        state.queue.forEach { queueTrack ->
             queueTrack.thumbnailUrl?.let { url ->
                 val request = ImageRequest.Builder(this)
                     .data(url)
                     .build()
                 Coil.imageLoader(this).enqueue(request)
             }
        }

        // --- Carousel Logic ---
        val trackChanged = oldState?.currentTrack?.id != state.currentTrack?.id
        
        if (trackChanged && track != null) {
            // Check if the new track is in the CURRENT adapter list
            val existingIndex = queueAdapter.indexOf(track.id)
            
            if (existingIndex != -1) {
                // Phase 1: Animate to the new track
                isAnimatingTrackChange = true
                pendingNewState = state
                binding.queueCarousel.setCurrentItem(existingIndex, true)
                return // Wait for animation to finish before rebuilding list
            } else {
                // Track changed but not in list (or rapid change). Force immediate update.
                isAnimatingTrackChange = false
                pendingNewState = null
            }
        }
        
        // If not animating (or track not found in current list), update immediately
        if (!isAnimatingTrackChange) {
            updateCarouselList(state)
        }
    }

    private fun updateCarouselList(state: PlaybackStateDto) {
        val track = state.currentTrack
        val items = (listOfNotNull(track) + state.queue)
            .distinctBy { candidate ->
                candidate.id.takeIf { !it.isNullOrBlank() } ?: "${candidate.title}-${candidate.artist}"
            }

        queueAdapter.submitList(items)
        updateCarouselDecor(items.size)
        
        // Snap to 0 (Current Track)
        binding.queueCarousel.setCurrentItem(0, false)
    }

    private fun updateTheme(url: String?) {
        PaletteThemeHelper.applyFromAlbumArt(this, url) { background, accent ->
            applyLyricTheme(background, accent)
        }
    }

    private fun applyLyricTheme(backgroundColor: Int, textColor: Int) {
        val gradient = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                adjustAlpha(backgroundColor, 0.95f),
                adjustAlpha(backgroundColor, 0.7f),
                adjustAlpha(backgroundColor, 0.2f)
            )
        )
        binding.lyricsRecyclerView.background = gradient
        lyricsAdapter.setTextColor(textColor)
        binding.songTitle.setTextColor(textColor)

        // Tint only the progress layer to the accent color, keep background black (if bar exists)
        val accentColor = textColor
        val bar = binding.root.findViewById<SeekBar?>(R.id.progressBar)
        
        // Set custom thumb if not already set
        if (bar?.thumb !is com.example.music_room.ui.ProgressPillDrawable) {
            bar?.thumb = com.example.music_room.ui.ProgressPillDrawable(this)
            // Offset the thumb so it centers correctly if needed, though standard behavior should be fine.
            // SeekBar might need thumbOffset adjustment if the drawable has padding, but our drawable is exact.
            bar?.thumbOffset = 0 
        }
        
        (bar?.thumb as? com.example.music_room.ui.ProgressPillDrawable)?.setPillColor(accentColor)
        
        // Ensure thumb has correct progress if we are paused or just starting
        val max = bar?.max ?: 1
        val progress = bar?.progress ?: 0
        val fraction = if (max > 0) progress.toFloat() / max else 0f
        (bar?.thumb as? com.example.music_room.ui.ProgressPillDrawable)?.setProgressFraction(fraction)

        val progressDrawable = bar?.progressDrawable?.mutate() as? android.graphics.drawable.LayerDrawable
        progressDrawable?.let { layer ->
            val progressLayer = layer.findDrawableByLayerId(android.R.id.progress)
            progressLayer?.mutate()?.setTint(accentColor)
            bar.progressDrawable = layer
        }
    }

    private fun adjustAlpha(color: Int, factor: Float): Int {
        val alpha = (Color.alpha(color) * factor).roundToInt().coerceIn(0, 255)
        return ColorUtils.setAlphaComponent(color, alpha)
    }

    /**
     * Handle lyrics update from WebSocket (Better Lyrics format)
     */
    private fun handleLyricsUpdate(response: LyricsResponseDto) {
        val synced = response.toSyncedLyrics() ?: return

        syncedLyrics = synced
        lyricsAdapter.submitLines(synced.lines, synced.syncType)
        binding.lyricsRecyclerView.isVisible = true
        updateLyricsForPosition(basePositionSeconds)
    }

    /**
     * Fetch lyrics using Better Lyrics approach
     * - Minimal name cleaning (handled in repository)
     * - Direct pass-through to backend
     * - Support for all sync types
     */
    private fun fetchLyrics(title: String?, artist: String?, duration: Int, videoId: String?) {
        if (title == null || artist == null) return
        
        currentLyricsJob?.cancel()
        lyricsAdapter.clear()
        syncedLyrics = null
        
        currentLyricsJob = lifecycleScope.launch {
            val result = lyricsRepository.getLyrics(title, artist, duration, videoId)
            
            result.onSuccess { response ->
                if (response.lines.isNotEmpty()) {
                    syncedLyrics = response
                    // Pass sync type to adapter (Better Lyrics style)
                    lyricsAdapter.submitLines(response.lines, response.syncType)
                    binding.lyricsRecyclerView.isVisible = true
                    updateLyricsForPosition(basePositionSeconds)
                }
            }.onFailure { error ->
                // Show error message if lyrics fetch fails
                android.util.Log.w("PlayerActivity", "Lyrics fetch failed: ${error.message}")
            }
        }
    }

    private suspend fun togglePlayPause() {
        val id = roomId ?: return
        if (currentState?.isPlaying == true) {
            val positionSeconds = resolveAccuratePlaybackPositionSeconds()
            repository.pause(id, positionSeconds)
        } else {
            repository.resume(id)
        }
    }

    private suspend fun skipTrack() {
        val id = roomId ?: return
        repository.next(id)
    }

    private suspend fun previousTrack() {
        val id = roomId ?: return
        repository.previous(id)
    }

    private fun schedulePlaybackTicker() {
        playbackTickerHandler.removeCallbacks(playbackTicker)
        if (currentState?.isPlaying == true) {
            // Run at 60fps (approx 16ms) for smooth animation
            playbackTickerHandler.postDelayed(playbackTicker, 16)
        }
    }

    private fun stopPlaybackTicker() {
        playbackTickerHandler.removeCallbacks(playbackTicker)
    }

    private fun tickPlaybackPosition() {
        val state = currentState ?: return
        if (!state.isPlaying) return

        val elapsed = basePositionSeconds + ((SystemClock.elapsedRealtime() - baseTimestamp) / 1000f)
        val clamped = elapsed.coerceAtLeast(0f)
        val duration = state.currentTrack?.durationSeconds?.toFloat() ?: 1f

        // --- Pill Animation Logic ---
        val animationDuration = 10f // 10 seconds to grow/shrink
        val scale = when {
            clamped < animationDuration -> (clamped / animationDuration).coerceIn(0f, 1f)
            clamped > (duration - animationDuration) -> ((duration - clamped) / animationDuration).coerceIn(0f, 1f)
            else -> 1f
        }
        
        val bar = binding.root.findViewById<SeekBar?>(R.id.progressBar)
        val thumb = bar?.thumb as? com.example.music_room.ui.ProgressPillDrawable
        thumb?.setWidthScale(scale)
        
        // Update progress for alignment
        val progressFraction = if (duration > 0) clamped / duration else 0f
        thumb?.setProgressFraction(progressFraction)

        if (!isUserSeeking) {
            // Update progress in milliseconds
            bar?.progress = (clamped * 1000).toInt()
            binding.currentTime.text = formatTime(clamped.toInt())
        }

        basePositionSeconds = clamped
        baseTimestamp = SystemClock.elapsedRealtime()
        updateLyricsForPosition(clamped)
    }

    private fun updateLyricsForPosition(positionSeconds: Float) {
        val lyrics = syncedLyrics ?: return
        if (lyrics.lines.isEmpty()) return
        val index = lyricsAdapter.updatePlaybackPosition((positionSeconds * 1000L).toLong())
        if (index != -1) {
            val layoutManager = binding.lyricsRecyclerView.layoutManager as? LinearLayoutManager ?: return
            layoutManager.scrollToPositionWithOffset(index, binding.lyricsRecyclerView.height / 2)
        }
    }

    private fun formatTime(seconds: Int): String {
        val safeSeconds = seconds.coerceAtLeast(0)
        val minutes = safeSeconds / 60
        val remaining = safeSeconds % 60
        return String.format("%02d:%02d", minutes, remaining)
    }

    private fun resolveAccuratePlaybackPositionSeconds(): Double {
        val state = currentState ?: return basePositionSeconds.toDouble()
        if (!state.isPlaying) return basePositionSeconds.toDouble()
        val elapsedSeconds = (SystemClock.elapsedRealtime() - baseTimestamp) / 1000f
        return (basePositionSeconds + elapsedSeconds).coerceAtLeast(0f).toDouble()
    }

    companion object {
        const val EXTRA_ROOM_ID = "extra_room_id"
        const val EXTRA_SONG_TITLE = "extra_song_title"
        const val EXTRA_ARTIST_NAME = "extra_artist_name"
        const val EXTRA_TRACK_ID = "extra_track_id"
        const val EXTRA_PROVIDER = "extra_provider"
        const val EXTRA_TRANSITION_NAME = "extra_transition_name"
    }

    inner class QueueAdapter : RecyclerView.Adapter<QueueAdapter.QueueViewHolder>() {
        private var items: List<TrackDto> = emptyList()

        fun submitList(newItems: List<TrackDto>) {
            items = newItems
            notifyDataSetChanged()
        }
        
        fun getItem(position: Int): TrackDto? {
            return items.getOrNull(position)
        }

        fun indexOf(trackId: String): Int {
            return items.indexOfFirst { it.id == trackId }
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): QueueViewHolder {
            val binding = ItemQueueCarouselBinding.inflate(android.view.LayoutInflater.from(parent.context), parent, false)
            return QueueViewHolder(binding)
        }

        override fun onBindViewHolder(holder: QueueViewHolder, position: Int) {
            // Safety check for ghost views
            if (position >= items.size) return
            holder.bind(items[position], position)
        }

        override fun getItemCount(): Int = items.size

        inner class QueueViewHolder(private val binding: ItemQueueCarouselBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(track: TrackDto, position: Int) {
                // Load art using repository (async)
                // Clear previous image to prevent stale art on recycled views
                binding.albumArt.setImageDrawable(null)
                
                // Load art using Coil
                binding.albumArt.load(track.thumbnailUrl) {
                    placeholder(R.drawable.album_placeholder)
                    error(R.drawable.album_placeholder)
                    precision(Precision.EXACT)
                    scale(Scale.FILL)
                    crossfade(true)
                }
                
                binding.root.setOnClickListener {
                    // Tap to Play logic
                    lifecycleScope.launch {
                        val id = roomId ?: return@launch
                        // If it's the current track (pos 0), toggle play/pause
                        if (position == 0) {
                            togglePlayPause()
                        } else {
                            // Animate to the clicked item
                            this@PlayerActivity.binding.queueCarousel.setCurrentItem(position, true)
                            
                            // Play track
                            repository.playTrack(id, track.id, track.provider)
                        }
                    }
                }
            }
        }
    }
}
