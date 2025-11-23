package com.example.music_room

import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.CompositePageTransformer
import androidx.viewpager2.widget.MarginPageTransformer
import androidx.viewpager2.widget.ViewPager2
import coil.load
import com.example.music_room.data.AuroraServiceLocator
import com.example.music_room.data.RoomSessionStore
import com.example.music_room.data.UserIdentity
import com.example.music_room.data.manager.StreamPrefetcher
import com.example.music_room.data.remote.model.PlaybackStateDto
import com.example.music_room.data.remote.model.TrackDto
import com.example.music_room.data.remote.model.LyricsResponseDto
import com.example.music_room.data.repository.SyncedLyrics
import com.example.music_room.databinding.ActivityRoomDetailBinding
import com.example.music_room.databinding.ItemQueueCarouselBinding
import com.example.music_room.service.MediaServiceManager
import com.example.music_room.ui.JoinRoomBottomSheet
import com.example.music_room.ui.LyricsAdapter
import com.example.music_room.utils.PaletteThemeHelper
import com.example.music_room.utils.PermissionUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min

class RoomDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRoomDetailBinding
    private val viewModel: com.example.music_room.ui.viewmodel.RoomDetailViewModel by viewModels()
    
    private val repository = AuroraServiceLocator.repository
    private val lyricsAdapter = LyricsAdapter()
    private val lyricsManager by lazy {
        com.example.music_room.ui.manager.LyricsManager(
            repository = AuroraServiceLocator.lyricsRepository,
            adapter = lyricsAdapter,
            scope = lifecycleScope,
            onLyricsStateChanged = { state ->
                when (state) {
                    is com.example.music_room.ui.manager.LyricsState.Loading -> {
                        binding.lyricsRecycler.isVisible = false
                        binding.lyricsLoading.isVisible = true
                        binding.lyricsMessage.isVisible = false
                    }
                    is com.example.music_room.ui.manager.LyricsState.Success -> {
                        binding.lyricsRecycler.isVisible = true
                        binding.lyricsLoading.isVisible = false
                        binding.lyricsMessage.isVisible = false
                    }
                    is com.example.music_room.ui.manager.LyricsState.Error -> {
                        binding.lyricsRecycler.isVisible = false
                        binding.lyricsLoading.isVisible = false
                        binding.lyricsMessage.isVisible = true
                        binding.lyricsMessage.text = state.message
                    }
                    is com.example.music_room.ui.manager.LyricsState.Hidden -> {
                        binding.lyricsRecycler.isVisible = false
                        binding.lyricsLoading.isVisible = false
                        binding.lyricsMessage.isVisible = false
                    }
                }
            }
        )
    }
    private var upcomingLyricsJob: Job? = null
    private var audioPrefetchJob: Job? = null
    private val prefetchedAudioIds = LinkedHashSet<String>()
    private var lastPrefetchedNextTrackId: String? = null
    
    private val carouselAdapter = com.example.music_room.ui.adapter.TrackCarouselAdapter { track, position ->
        if (position == 0) {
            viewModel.togglePlayPause()
        } else {
            // For now, RoomDetailActivity only supported toggle on current. 
            // We can add "play this track" logic here if desired, but sticking to original behavior for safety first.
            // Actually, let's allow playing specific tracks if they are in the queue?
            // The original code ONLY handled position 0. I will stick to that for now to avoid behavioral changes in this refactor step.
        }
    }
    private var carouselPageChangeCallback: ViewPager2.OnPageChangeCallback? = null
    private var lastSyncedTrackId: String? = null
    private var lastCarouselItems: List<TrackDto> = emptyList()
    private var isCarouselBeingDragged = false
    private var isAnimatingToNextTrack = false
    private var pendingNextTrackItems: List<TrackDto>? = null

    private val roomId: String by lazy { intent.getStringExtra(EXTRA_ROOM_ID).orEmpty() }
    private val roomName: String by lazy { intent.getStringExtra(EXTRA_ROOM_NAME).orEmpty() }
    private val isLocked: Boolean by lazy { intent.getBooleanExtra(EXTRA_ROOM_LOCKED, false) }
    private val roomHostId: String by lazy { intent.getStringExtra(EXTRA_ROOM_HOST_ID).orEmpty() }
    
    private var leaveRequested = false
    private var sliderBeingDragged = false
    private lateinit var mediaServiceManager: MediaServiceManager
    private var joinRoomBottomSheet: JoinRoomBottomSheet? = null
    private var playbackServiceRunning = false
    private var lastObservedMemberId: String? = null
    private var hasAppliedMemberState = false
    
    private val playbackTickerHandler = Handler(Looper.getMainLooper())
    private val playbackTicker = object : Runnable {
        override fun run() {
            tickPlaybackProgress()
            schedulePlaybackTicker()
        }
    }
    
    // High-precision timing for lyrics sync
    private var lastServerPositionMs: Long = 0
    private var lastServerTimestamp: Long = 0
    private var audioLatencyMs: Long = 0  // Measured audio latency

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        binding = ActivityRoomDetailBinding.inflate(layoutInflater)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(binding.root)

        binding.roomNameTitle.text = roomName
        
        viewModel.setRoomId(roomId)
        viewModel.setRoomHostId(roomHostId.ifEmpty { null })

    mediaServiceManager = MediaServiceManager.getInstance(this)
    playbackServiceRunning = mediaServiceManager.isServiceRunning()

        setupSession()
        setupUI()
        setupCarousel()
        setupLyricsUi()
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
        binding.menuButton.setOnClickListener { showMenu(it) }
        binding.addMusicButton.setOnClickListener { promptAddSong() }
        
        binding.playbackSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val seconds = progress / 1000
                    binding.playbackElapsed.text = formatTime(seconds)
                    val duration = binding.playbackSlider.max.takeIf { it > 0 } ?: return
                    val progressMs = progress.toFloat()
                    val progressSeconds = progressMs / 1000f
                    val durationSeconds = duration / 1000
                    
                    updateProgressThumbVisuals(
                        displaySeconds = progressSeconds,
                        actualSeconds = progressSeconds,
                        durationSeconds = durationSeconds
                    )
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                sliderBeingDragged = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                sliderBeingDragged = false
                val progressMs = seekBar?.progress ?: 0
                val progressSeconds = progressMs / 1000
                binding.playbackElapsed.text = formatTime(progressSeconds)
                viewModel.sliderBasePositionSeconds = progressSeconds.toFloat()
                viewModel.sliderBaseTimestamp = SystemClock.elapsedRealtime()
                viewModel.seekTo(progressSeconds)
            }
        })
    }

    private fun showMenu(view: android.view.View) {
        val popup = PopupMenu(this, view)
        popup.menu.add("Add Song")
        if (viewModel.uiState.value.memberId != null) {
            popup.menu.add("Leave Room")
        }
        if (viewModel.uiState.value.canDeleteRoom) {
            popup.menu.add("Delete Room")
        }
        
        popup.setOnMenuItemClickListener { item ->
            when (item.title) {
                "Add Song" -> promptAddSong()
                "Leave Room" -> leaveRoomAndFinish()
                "Delete Room" -> promptDeleteRoom()
            }
            true
        }
        popup.show()
    }

    private fun setupCarousel() {
        binding.queueCarousel.apply {
            adapter = carouselAdapter
            clipToPadding = false
            clipChildren = false
            offscreenPageLimit = 3
            // Keep centered while allowing a slight peek on both sides
            setPadding(80, 0, 80, 0)

            val transformer = CompositePageTransformer()
            transformer.addTransformer(MarginPageTransformer(16))
            transformer.addTransformer { page, position ->
                val r = 1 - abs(position)
                page.scaleY = 0.75f + r * 0.25f
                page.scaleX = 0.75f + r * 0.25f
                page.alpha = 0.5f + r * 0.5f
                page.translationX = position * -80f
            }
            setPageTransformer(transformer)

            carouselPageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    super.onPageSelected(position)
                    updateDisplayedTrackFromCarousel(position)
                }

                override fun onPageScrollStateChanged(state: Int) {
                    super.onPageScrollStateChanged(state)
                    if (!isAnimatingToNextTrack) {
                        when (state) {
                            ViewPager2.SCROLL_STATE_DRAGGING -> isCarouselBeingDragged = true
                            ViewPager2.SCROLL_STATE_IDLE -> isCarouselBeingDragged = false
                        }
                    } else if (state == ViewPager2.SCROLL_STATE_IDLE) {
                        finalizeNextTrackAnimation()
                    }
                }
            }.also { callback ->
                registerOnPageChangeCallback(callback)
            }
        }

        binding.nextTrackTapTarget.setOnClickListener {
            viewModel.skipTrack()
        }
    }

    private fun updateDisplayedTrackFromCarousel(position: Int) {
        val track = carouselAdapter.getItem(position)
        updateDisplayedTrack(track)
    }

    private fun updateDisplayedTrack(track: TrackDto?) {
        val title = track?.title?.takeIf { it.isNotBlank() } ?: getString(R.string.no_track_playing)
        val artist = track?.artist?.takeIf { it.isNotBlank() } ?: getString(R.string.no_track_artist)
        binding.currentSongTitle.text = title
        binding.currentSongArtist.text = artist
    }

    private fun setupLyricsUi() {
        binding.lyricsRecycler.apply {
            layoutManager = LinearLayoutManager(this@RoomDetailActivity)
            adapter = lyricsAdapter
            itemAnimator = null
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    if (!hasAppliedMemberState || state.memberId != lastObservedMemberId) {
                        handleMemberStateChange(state.memberId)
                    }

                    if (state.memberId == null && !leaveRequested) {
                        joinRoomIfNeeded()
                    }

                    val playbackState = state.playbackState
                    playbackState?.let { applyPlaybackState(it) }

                    // Update Carousel with Current + Queue
                    val items = mutableListOf<TrackDto>()
                    val currentTrack = playbackState?.currentTrack
                    currentTrack?.let { items.add(it) }
                    items.addAll(state.queue)

                    val previousTrackId = lastSyncedTrackId
                    val currentTrackId = currentTrack?.id
                    val shouldRecentre = shouldRecentreCarousel(previousTrackId, currentTrackId)
                    val trackChanged = shouldRecentre
                    val shouldAnimateToNext = trackChanged &&
                        shouldAnimateToNextTrack(lastCarouselItems, currentTrack) &&
                        !isCarouselBeingDragged &&
                        !isAnimatingToNextTrack &&
                        binding.queueCarousel.currentItem == 0 &&
                        carouselAdapter.itemCount > 1

                    if (shouldAnimateToNext) {
                        startNextTrackAnimation(items)
                    } else if (isAnimatingToNextTrack) {
                        pendingNextTrackItems = items
                        lastCarouselItems = items
                    } else {
                        updateCarouselItems(items, shouldRecentre)
                    }

                    lastSyncedTrackId = currentTrackId

                    if (state.error != null) {
                        Toast.makeText(this@RoomDetailActivity, state.error, Toast.LENGTH_SHORT).show()
                        viewModel.clearError()
                    }
                }
            }
        }
    }

    private fun shouldRecentreCarousel(previousTrackId: String?, currentTrackId: String?): Boolean {
        return when {
            currentTrackId == null && previousTrackId != null -> true
            currentTrackId != null && currentTrackId != previousTrackId -> true
            else -> false
        }
    }

    private fun shouldAnimateToNextTrack(previousItems: List<TrackDto>, newCurrent: TrackDto?): Boolean {
        if (newCurrent == null || previousItems.size < 2) return false
        val upcomingItem = previousItems.getOrNull(1) ?: return false
        val currentId = newCurrent.id
        return if (!currentId.isNullOrBlank()) {
            currentId == upcomingItem.id
        } else {
            newCurrent == upcomingItem
        }
    }

    private fun startNextTrackAnimation(nextItems: List<TrackDto>) {
        if (carouselAdapter.itemCount < 2) {
            updateCarouselItems(nextItems, shouldRecentre = true)
            return
        }

        pendingNextTrackItems = nextItems
        lastCarouselItems = nextItems
        isAnimatingToNextTrack = true
        binding.queueCarousel.post {
            binding.queueCarousel.setCurrentItem(1, true)
        }
    }

    private fun updateCarouselItems(items: List<TrackDto>, shouldRecentre: Boolean) {
        lastCarouselItems = items
        carouselAdapter.submitList(items)

        if (items.isEmpty()) {
            binding.queueCarousel.post {
                binding.queueCarousel.setCurrentItem(0, false)
            }
            updateDisplayedTrack(null)
            return
        }

        if (shouldRecentre) {
            binding.queueCarousel.post {
                binding.queueCarousel.setCurrentItem(0, false)
                updateDisplayedTrackFromCarousel(0)
            }
        } else {
            val targetPosition = binding.queueCarousel.currentItem.coerceIn(0, items.lastIndex)
            if (binding.queueCarousel.currentItem != targetPosition) {
                binding.queueCarousel.post {
                    binding.queueCarousel.setCurrentItem(targetPosition, false)
                }
            }
            updateDisplayedTrackFromCarousel(targetPosition)
        }
    }

    private fun finalizeNextTrackAnimation() {
        val items = pendingNextTrackItems
        pendingNextTrackItems = null
        isAnimatingToNextTrack = false
        isCarouselBeingDragged = false

        if (items != null) {
            carouselAdapter.submitList(items)
            lastCarouselItems = items
            binding.queueCarousel.post {
                binding.queueCarousel.setCurrentItem(0, false)
                if (items.isNotEmpty()) {
                    updateDisplayedTrackFromCarousel(0)
                } else {
                    updateDisplayedTrack(null)
                }
            }
        } else {
            val currentPosition = binding.queueCarousel.currentItem
            updateDisplayedTrackFromCarousel(currentPosition)
        }
    }

    private fun applyPlaybackState(state: PlaybackStateDto) {
        viewModel.sliderBasePositionSeconds = state.positionSeconds.toFloat()
        viewModel.sliderBaseTimestamp = SystemClock.elapsedRealtime()
        
        // Update high-precision timing for lyrics sync
        lastServerPositionMs = (state.positionSeconds * 1000).toLong()
        lastServerTimestamp = System.currentTimeMillis()
        
        val track = state.currentTrack
        val displayArtist = track?.artist.orEmpty()

        if (binding.queueCarousel.currentItem == 0) {
            binding.currentSongTitle.text = track?.title ?: getString(R.string.no_track_playing)
            binding.currentSongArtist.text = displayArtist.takeIf { it.isNotBlank() }
                ?: getString(R.string.no_track_artist)
        }
        
        val fallbackDuration = state.positionSeconds.toInt().coerceAtLeast(1)
        val duration = track?.durationSeconds?.takeIf { it > 0 } ?: fallbackDuration
        binding.playbackDuration.text = formatTime(duration)
        binding.playbackSlider.isEnabled = track != null
        
        if (!sliderBeingDragged) {
            val durationSeconds = duration.coerceAtLeast(1)
            binding.playbackSlider.max = durationSeconds * 1000

            val actualSeconds = state.positionSeconds.toFloat()
            val displaySeconds = computeAnimatedDisplaySeconds(
                actualSeconds = actualSeconds,
                durationSeconds = durationSeconds
            )

            val sliderProgress = (displaySeconds * 1000).toInt().coerceIn(0, binding.playbackSlider.max)
            binding.playbackSlider.progress = sliderProgress
            binding.playbackElapsed.text = formatTime(actualSeconds.toInt())
            updateProgressThumbVisuals(
                displaySeconds = displaySeconds,
                actualSeconds = actualSeconds,
                durationSeconds = durationSeconds
            )
        }

    val normalizedArtist = displayArtist.takeIf { it.isNotBlank() }
        val trackKey = track?.let { "${it.id}|${it.title}|${normalizedArtist ?: it.artist}" }
        if (track != null && track.title.isNotBlank() && !normalizedArtist.isNullOrBlank()) {
            lyricsManager.fetchLyrics(track.title, normalizedArtist, track.durationSeconds, track.id)
            // Apply color theme from album artwork
            applyColorTheme(track.thumbnailUrl)
        } else {
            // No track or invalid metadata
            // resetLyricsContent() // Removed
        }

        if (state.isPlaying) {
            schedulePlaybackTicker()
        } else {
            stopPlaybackTicker()
        }
        
        carouselAdapter.setPlayingState(state.isPlaying)

        updateLyricsForPosition(viewModel.sliderBasePositionSeconds, immediate = false)

        prefetchUpcomingAssets(state)
    }

    /**
     * Apply color theme extracted from album artwork to lyrics and progress bar
     */
    private fun applyColorTheme(albumArtUrl: String?) {
        PaletteThemeHelper.applyFromAlbumArt(this, albumArtUrl) { _, accentColor ->
            // Apply color to lyrics and use the accent for the capsule thumb
            lyricsAdapter.setTextColor(accentColor)
            
            // Set up custom progress pill drawable
            if (binding.playbackSlider.thumb !is com.example.music_room.ui.ProgressPillDrawable) {
                binding.playbackSlider.thumb = com.example.music_room.ui.ProgressPillDrawable(this)
                // Initial offset will be set in tickPlaybackProgress
            }
            
            (binding.playbackSlider.thumb as? com.example.music_room.ui.ProgressPillDrawable)?.setPillColor(accentColor)
            
            // Set wave loading color
            binding.lyricsLoading.setDotColor(accentColor)
            
            // Don't tint the progress layer - keep the background black
            // Only the pill thumb should be colored
        }
    }

    /**
     * Fetch lyrics using Better Lyrics approach
     * - Minimal name cleaning (handled in repository)
     * - Direct pass-through to backend with provider fallback
     */
    // Old fetchLyrics, updateLyricsForPosition, and prefetchLyricsForTrack methods removed (replaced by LyricsManager)
    private fun handleLyricsUpdate(response: LyricsResponseDto) {
        lyricsManager.handleSocketUpdate(response)
    }
    private fun prefetchUpcomingAssets(state: PlaybackStateDto) {
        val nextTrack = state.queue.firstOrNull()
        android.util.Log.d("RoomDetailActivity", "prefetchUpcomingAssets: queue size=${state.queue.size}, nextTrack=${nextTrack?.title}")
        if (nextTrack == null) {
            lastPrefetchedNextTrackId = null
            android.util.Log.d("RoomDetailActivity", "No next track to prefetch")
            return
        }
        if (nextTrack.id == lastPrefetchedNextTrackId) {
            android.util.Log.d("RoomDetailActivity", "Already prefetched ${nextTrack.id}")
            return
        }
        android.util.Log.d("RoomDetailActivity", "Prefetching track: ${nextTrack.title} (${nextTrack.id})")
        lastPrefetchedNextTrackId = nextTrack.id
        // prefetchLyricsForTrack(nextTrack) // Lyrics prefetch disabled in refactor
        prefetchAudioForTrack(nextTrack)
    }

    private fun prefetchAudioForTrack(track: TrackDto) {
        val trackId = track.id.takeIf { it.isNotBlank() } ?: return
        android.util.Log.d("RoomDetailActivity", "prefetchAudioForTrack: ${track.title} ($trackId)")
        if (!prefetchedAudioIds.add(trackId)) return
        audioPrefetchJob?.cancel()
        audioPrefetchJob = lifecycleScope.launch {
            android.util.Log.d("RoomDetailActivity", "Fetching stream URL for prefetch...")
            StreamPrefetcher.prefetch(trackId)
            android.util.Log.d("RoomDetailActivity", "Audio prefetch completed for $trackId")
        }
        trimAudioPrefetchCache()
    }

    private fun trimAudioPrefetchCache() {
        if (prefetchedAudioIds.size <= 12) return
        val iterator = prefetchedAudioIds.iterator()
        if (iterator.hasNext()) {
            iterator.next()
            iterator.remove()
        }
    }

    private fun updateLyricsForPosition(positionSeconds: Float, immediate: Boolean) {
        val positionMs = (positionSeconds * 1000L).toLong()
        updateLyricsForPositionMs(positionMs, immediate)
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

    private fun schedulePlaybackTicker() {
        playbackTickerHandler.removeCallbacks(playbackTicker)
        if (viewModel.uiState.value.playbackState?.isPlaying == true) {
            // Use faster updates (100ms) for rich-sync word-by-word animation
            // Fallback to line-sync (1s) if rich-sync not available
            val lyrics = lyricsManager.syncedLyrics
            val hasRichSync = lyrics?.syncType == com.example.music_room.data.repository.SyncType.RICH_SYNC &&
                    lyrics.lines.any { it.parts.isNotEmpty() }
            
            val updateInterval = if (hasRichSync) {
                100L  // 100ms for word-level karaoke
            } else {
                1000L  // 1s for line-level or no lyrics
            }
            playbackTickerHandler.postDelayed(playbackTicker, updateInterval)
        }
    }

    private fun stopPlaybackTicker() {
        playbackTickerHandler.removeCallbacks(playbackTicker)
    }

    private fun tickPlaybackProgress() {
        val state = viewModel.uiState.value.playbackState ?: return
        if (!state.isPlaying || sliderBeingDragged) return
        
        val duration = state.currentTrack?.durationSeconds?.takeIf { it > 0 }
            ?: binding.playbackSlider.max.coerceAtLeast(1)
        
        // Calculate position with high precision for lyrics
        val currentTimeMs = System.currentTimeMillis()
        val elapsedSinceServerUpdate = currentTimeMs - lastServerTimestamp
        val estimatedPositionMs = lastServerPositionMs + elapsedSinceServerUpdate - audioLatencyMs
        
        // Use for slider (seconds, rounded)
        val elapsedMillis = SystemClock.elapsedRealtime() - viewModel.sliderBaseTimestamp
        val elapsed = viewModel.sliderBasePositionSeconds + (elapsedMillis / 1000f)
        val clamped = elapsed.coerceIn(0f, duration.toFloat())
        
        val displaySeconds = computeAnimatedDisplaySeconds(
            actualSeconds = clamped,
            durationSeconds = duration
        )
        val roundedDisplayMs = (displaySeconds * 1000).toInt()
        val roundedActual = kotlin.math.round(clamped).toInt()

        updateProgressThumbVisuals(
            displaySeconds = displaySeconds,
            actualSeconds = clamped,
            durationSeconds = duration
        )

        // Let SeekBar handle positioning purely via progress; keep thumbOffset neutral
        binding.playbackSlider.thumbOffset = 0
        binding.playbackSlider.progress = roundedDisplayMs
        binding.playbackElapsed.text = formatTime(roundedActual)

        viewModel.sliderBasePositionSeconds = clamped
        viewModel.sliderBaseTimestamp = SystemClock.elapsedRealtime()

        // Use high-precision milliseconds for lyrics sync
        val precisePositionMs = estimatedPositionMs.coerceAtLeast(0L)
        updateLyricsForPositionMs(precisePositionMs, immediate = false)
    }
    
    private fun updateLyricsForPositionMs(positionMs: Long, immediate: Boolean) {
        val lyrics = lyricsManager.syncedLyrics ?: return
        if (lyrics.lines.isEmpty()) return

        val index = lyricsAdapter.updatePlaybackPosition(positionMs)
        if (index != -1) {
            scrollLyricsTo(index, immediate)
        }
    }

    private fun updateProgressThumbVisuals(
        displaySeconds: Float,
        actualSeconds: Float,
        durationSeconds: Int
    ) {
        val duration = durationSeconds.coerceAtLeast(1)
        val thumb = binding.playbackSlider.thumb as? com.example.music_room.ui.ProgressPillDrawable ?: return

        val durationFloat = duration.toFloat()
        val clampedDisplay = displaySeconds.coerceIn(0f, durationFloat)
        val clampedActual = actualSeconds.coerceIn(0f, durationFloat)

        val animationDuration = min(PROGRESS_ANIMATION_DURATION_SECONDS, durationFloat / 2f)
        val scale = when {
            animationDuration <= 0f -> 1f
            clampedActual < animationDuration -> (clampedActual / animationDuration).coerceIn(0f, 1f)
            clampedActual > (durationFloat - animationDuration) -> ((durationFloat - clampedActual) / animationDuration).coerceIn(0f, 1f)
            else -> 1f
        }
        thumb.setWidthScale(scale)

        val progressRatio = if (durationFloat > 0f) clampedDisplay / durationFloat else 0f
        thumb.setProgressFraction(progressRatio.coerceIn(0f, 1f))
    }

    private fun computeAnimatedDisplaySeconds(actualSeconds: Float, durationSeconds: Int): Float {
        val duration = durationSeconds.coerceAtLeast(1)
        val durationFloat = duration.toFloat()
        val clampedActual = actualSeconds.coerceIn(0f, durationFloat)

        val animationDuration = min(PROGRESS_ANIMATION_DURATION_SECONDS, durationFloat / 2f)
        val hasDistinctPhases = animationDuration > 0f && durationFloat > animationDuration * 2f
        if (!hasDistinctPhases) {
            return clampedActual
        }

        val growthEnd = animationDuration
        val shrinkStart = durationFloat - animationDuration

        return when {
            clampedActual <= growthEnd -> 0f
            clampedActual >= shrinkStart -> durationFloat
            else -> {
                val travelSpan = shrinkStart - growthEnd
                val travelSeconds = clampedActual - growthEnd
                val travelRatio = (travelSeconds / travelSpan).coerceIn(0f, 1f)
                travelRatio * durationFloat
            }
        }
    }

    private fun formatTime(seconds: Int): String {
        val safeSeconds = seconds.coerceAtLeast(0)
        val minutes = safeSeconds / 60
        val remaining = safeSeconds % 60
        return String.format("%d:%02d", minutes, remaining)
    }

    // ... (Join/Leave/Delete logic same as before, simplified for brevity) ...
    // I will keep the helper methods for Join/Leave/Delete but they are standard.
    
    private fun promptDeleteRoom() {
         AlertDialog.Builder(this)
            .setTitle(R.string.delete_room_dialog_title)
            .setMessage(R.string.delete_room_dialog_message)
            .setPositiveButton(R.string.delete_room_confirm) { _, _ -> performDeleteRoom() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun performDeleteRoom() {
        viewModel.deleteRoom(roomId) { success, error ->
            if (success) {
                RoomSessionStore.clearMemberId(this, roomId)
                finish()
            } else {
                Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun leaveRoomAndFinish() {
        if (leaveRequested) return
        leaveRequested = true
        val memberId = viewModel.uiState.value.memberId ?: return
        lifecycleScope.launch {
            viewModel.leaveRoom(roomId, memberId)
            stopPlaybackServiceIfRunning(force = true)
            RoomSessionStore.clearMemberId(this@RoomDetailActivity, roomId)
            viewModel.disconnectSocket()
            finish()
        }
    }

    private fun promptAddSong() {
        val sheet = com.example.music_room.ui.AddSongBottomSheet(
            this,
            onSearch = { query, onSuccess, onFailure ->
                viewModel.search(query, onSuccess, onFailure)
            },
            onPlayNow = { track ->
                viewModel.playTrack(track.id, track.provider)
                Toast.makeText(this, "Playing ${track.title}", Toast.LENGTH_SHORT).show()
            },
            onAddToQueue = { track ->
                viewModel.addToQueue(track.id, track.provider, UserIdentity.getDisplayName(this))
                Toast.makeText(this, getString(R.string.track_added), Toast.LENGTH_SHORT).show()
            }
        )
        sheet.show()
    }

    private fun joinRoomIfNeeded() {
        if (roomId.isEmpty()) { finish(); return }
        
        // Check if already a member
        val existingMemberId = RoomSessionStore.getSession(this, roomId)?.memberId
        if (existingMemberId != null) {
            return
        }

        if (joinRoomBottomSheet?.isShowing == true) {
            return
        }

        if (isLocked) {
            stopPlaybackServiceIfRunning(force = true)
            val sheet = joinRoomBottomSheet ?: createJoinRoomBottomSheet()
            if (!sheet.isShowing) {
                sheet.show()
            }
        } else {
            viewModel.joinRoom(
                roomId = roomId,
                displayName = UserIdentity.getDisplayName(this),
                passcode = null,
                inviteCode = null,
                onSuccess = { memberId ->
                    RoomSessionStore.saveMemberId(this, roomId, memberId)
                },
                onFailure = { _ ->
                    Toast.makeText(this, R.string.room_join_failed, Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    private fun handleMemberStateChange(memberId: String?) {
        ensurePlaybackServiceState(memberId)
        lastObservedMemberId = memberId
        hasAppliedMemberState = true
    }

    private fun ensurePlaybackServiceState(memberId: String?) {
        if (memberId.isNullOrBlank()) {
            stopPlaybackServiceIfRunning(force = true)
            return
        }

        if (playbackServiceRunning || mediaServiceManager.isServiceRunning()) {
            playbackServiceRunning = true
            return
        }

        if (PermissionUtils.requestNotificationPermissionIfNeeded(this)) {
            mediaServiceManager.startPlaybackService(roomId)
            playbackServiceRunning = true
        }
    }

    private fun stopPlaybackServiceIfRunning(force: Boolean = false) {
        if (playbackServiceRunning || force || mediaServiceManager.isServiceRunning()) {
            mediaServiceManager.stopPlaybackService()
            playbackServiceRunning = false
        }
    }

    private fun createJoinRoomBottomSheet(): JoinRoomBottomSheet {
        val displayName = UserIdentity.getDisplayName(this)
        val sheet = JoinRoomBottomSheet(
            context = this,
            roomName = roomName,
            onJoinRequested = { passcode, onSuccess, onError ->
                viewModel.joinRoom(
                    roomId = roomId,
                    displayName = displayName,
                    passcode = passcode,
                    inviteCode = null,
                    onSuccess = { memberId ->
                        RoomSessionStore.saveMemberId(this, roomId, memberId)
                        onSuccess(
                            com.example.music_room.data.remote.model.JoinRoomResponseDto(
                                com.example.music_room.data.remote.model.RoomMemberDto(
                                    memberId,
                                    displayName,
                                    0,
                                    0,
                                    false
                                )
                            )
                        )
                    },
                    onFailure = { error ->
                        onError(error)
                    }
                )
            },
            onCancelled = { finish() }
        )

        sheet.setOnDismissListener {
            if (joinRoomBottomSheet === sheet) {
                joinRoomBottomSheet = null
            }
        }

        joinRoomBottomSheet = sheet
        return sheet
    }

    override fun onStart() {
        super.onStart()
        viewModel.connectSocket()
        handleMemberStateChange(viewModel.uiState.value.memberId)
    }

    override fun onStop() {
        viewModel.disconnectSocket()
        stopPlaybackTicker()
        super.onStop()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        PermissionUtils.handlePermissionResult(
            requestCode,
            permissions,
            grantResults,
            onGranted = { handleMemberStateChange(viewModel.uiState.value.memberId) },
            onDenied = {
                Toast.makeText(this, R.string.notification_permission_required, Toast.LENGTH_LONG).show()
            }
        )
    }

    override fun onDestroy() {
        audioPrefetchJob?.cancel()
        prefetchedAudioIds.clear()
        carouselPageChangeCallback?.let { binding.queueCarousel.unregisterOnPageChangeCallback(it) }
        carouselPageChangeCallback = null
        joinRoomBottomSheet?.dismiss()
        joinRoomBottomSheet = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ROOM_ID = "extra_room_id"
        const val EXTRA_ROOM_NAME = "extra_room_name"
        const val EXTRA_ROOM_LOCKED = "extra_room_locked"
        const val EXTRA_ROOM_HOST_ID = "extra_room_host_id"

        /**
         * Small fractional offset you can tweak (+/-) to nudge the pill's alignment.
         * Positive values push the pill slightly to the right, negative to the left.
         */
        private const val EDGE_ALIGNMENT_TWEAK = -1f

        /** Duration (seconds) for the grow/shrink animation at both ends. */
        private const val PROGRESS_ANIMATION_DURATION_SECONDS = 10f
    }

    // Inner adapter removed. Using com.example.music_room.ui.adapter.TrackCarouselAdapter instead.
}
