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
    private val lyricsRepository = AuroraServiceLocator.lyricsRepository
    private var currentLyricsJob: Job? = null
    private var lastLyricsTrackKey: String? = null
    private val lyricsAdapter = LyricsAdapter()
    private var syncedLyrics: SyncedLyrics? = null
    private val prefetchedLyrics = LinkedHashMap<String, SyncedLyrics>()
    private var upcomingLyricsJob: Job? = null
    private var audioPrefetchJob: Job? = null
    private val prefetchedAudioIds = LinkedHashSet<String>()
    private var lastPrefetchedNextTrackId: String? = null
    
    private val carouselAdapter = CarouselAdapter()
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
        val track = carouselAdapter.getTrack(position)
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
            if (trackKey != null && trackKey != lastLyricsTrackKey) {
                lastLyricsTrackKey = trackKey
                val targetDuration = track.durationSeconds.takeIf { it > 0 } ?: duration
                fetchLyrics(track.title, normalizedArtist, targetDuration, track.id)
                // Apply color theme from album artwork
                applyColorTheme(track.thumbnailUrl)
            }
        } else {
            lastLyricsTrackKey = null
            resetLyricsContent()
        }

        if (state.isPlaying) {
            schedulePlaybackTicker()
        } else {
            stopPlaybackTicker()
        }
        
        carouselAdapter.setPlayingState(state.isPlaying)

        syncedLyrics?.let {
            updateLyricsForPosition(viewModel.sliderBasePositionSeconds, immediate = false)
        }

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
            
            // Don't tint the progress layer - keep the background black
            // Only the pill thumb should be colored
        }
    }

    /**
     * Fetch lyrics using Better Lyrics approach
     * - Minimal name cleaning (handled in repository)
     * - Direct pass-through to backend with provider fallback
     */
    private fun fetchLyrics(title: String, artist: String, durationSeconds: Int, videoId: String?) {
        currentLyricsJob?.cancel()
        binding.lyricsLoading.isVisible = true
        binding.lyricsMessage.isVisible = false
        binding.lyricsRecycler.isVisible = false
        syncedLyrics = null
        lyricsAdapter.clear()

        val key = buildTrackKey(videoId, title, artist)
        prefetchedLyrics.remove(key)?.let { cached ->
            binding.lyricsLoading.isVisible = false
            displaySyncedLyrics(cached, immediate = true)
            return
        }

        currentLyricsJob = lifecycleScope.launch {
            val result = lyricsRepository.getLyrics(title, artist, durationSeconds, videoId)
            binding.lyricsLoading.isVisible = false

            result.onSuccess { response ->
                if (response.lines.isEmpty()) {
                    showLyricsMessage(R.string.lyrics_not_found)
                } else {
                    rememberPrefetchedLyrics(key, response)
                    displaySyncedLyrics(response, immediate = true)
                }
            }.onFailure { error ->
                android.util.Log.w("RoomDetailActivity", "Lyrics fetch failed: ${error.message}")
                showLyricsError()
            }
        }
    }

    private fun showLyricsMessage(@androidx.annotation.StringRes messageRes: Int) {
        binding.lyricsMessage.text = getString(messageRes)
        binding.lyricsMessage.isVisible = true
        binding.lyricsRecycler.isVisible = false
        syncedLyrics = null
        lyricsAdapter.clear()
    }

    private fun displaySyncedLyrics(response: SyncedLyrics, immediate: Boolean) {
        syncedLyrics = response
        lyricsAdapter.submitLines(response.lines, response.syncType)
        binding.lyricsRecycler.isVisible = true
        binding.lyricsMessage.isVisible = false
        applyNormalizedMetadata(response)
        updateLyricsForPosition(viewModel.sliderBasePositionSeconds, immediate)
    }

    private fun applyNormalizedMetadata(response: SyncedLyrics) {
        if (binding.queueCarousel.currentItem != 0) {
            return
        }
        response.normalizedSong?.takeIf { it.isNotBlank() }?.let {
            binding.currentSongTitle.text = it
        }
        response.normalizedArtist?.takeIf { it.isNotBlank() }?.let {
            binding.currentSongArtist.text = it
        }
    }

    private fun showLyricsError() {
        showLyricsMessage(R.string.lyrics_not_found)
    }

    private fun resetLyricsContent() {
        currentLyricsJob?.cancel()
        binding.lyricsLoading.isVisible = false
        binding.lyricsMessage.isVisible = false
        binding.lyricsRecycler.isVisible = false
        syncedLyrics = null
        lyricsAdapter.clear()
    }

    private fun buildTrackKey(videoId: String?, title: String?, artist: String?): String {
        return listOf(videoId.orEmpty(), title.orEmpty(), artist.orEmpty())
            .joinToString("|")
            .lowercase()
    }

    private fun rememberPrefetchedLyrics(key: String, lyrics: SyncedLyrics) {
        prefetchedLyrics[key] = lyrics
        if (prefetchedLyrics.size > 8) {
            val firstKey = prefetchedLyrics.keys.firstOrNull()
            if (firstKey != null) {
                prefetchedLyrics.remove(firstKey)
            }
        }
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
        prefetchLyricsForTrack(nextTrack)
        prefetchAudioForTrack(nextTrack)
    }

    private fun prefetchLyricsForTrack(track: TrackDto) {
        val artist = track.artist.takeIf { it.isNotBlank() } ?: return
        if (track.title.isBlank()) return
        val key = buildTrackKey(track.id, track.title, artist)
        android.util.Log.d("RoomDetailActivity", "prefetchLyricsForTrack: ${track.title} - $artist, key=$key")
        if (prefetchedLyrics.containsKey(key)) return
        upcomingLyricsJob?.cancel()
        val duration = track.durationSeconds.takeIf { it > 0 } ?: 0
        upcomingLyricsJob = lifecycleScope.launch {
            android.util.Log.d("RoomDetailActivity", "Fetching lyrics for prefetch...")
            val result = lyricsRepository.getLyrics(track.title, artist, duration, track.id)
            result.onSuccess { 
                android.util.Log.d("RoomDetailActivity", "Prefetch lyrics SUCCESS for ${track.title}")
                rememberPrefetchedLyrics(key, it) 
            }
            result.onFailure {
                android.util.Log.w("RoomDetailActivity", "Prefetch lyrics FAILED: ${it.message}")
            }
        }
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
        val lyrics = syncedLyrics ?: return
        if (lyrics.lines.isEmpty()) return

        val index = lyricsAdapter.updatePlaybackPosition((positionSeconds * 1000L).toLong())
        if (index != -1) {
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
        if (!state.isPlaying || sliderBeingDragged) return
        
        val duration = state.currentTrack?.durationSeconds?.takeIf { it > 0 }
            ?: (binding.playbackSlider.max / 1000).coerceAtLeast(1)
            
        val elapsed = viewModel.sliderBasePositionSeconds +
            ((SystemClock.elapsedRealtime() - viewModel.sliderBaseTimestamp) / 1000f)
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

        updateLyricsForPosition(clamped, immediate = false)
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
        currentLyricsJob?.cancel()
        upcomingLyricsJob?.cancel()
        audioPrefetchJob?.cancel()
        prefetchedLyrics.clear()
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

    inner class CarouselAdapter : RecyclerView.Adapter<CarouselAdapter.ViewHolder>() {
        private var items: List<TrackDto> = emptyList()
        private var isPlaying: Boolean = false

        fun submitList(newItems: List<TrackDto>) {
            items = newItems
            notifyDataSetChanged()
        }

        fun setPlayingState(playing: Boolean) {
            if (isPlaying != playing) {
                isPlaying = playing
                notifyItemChanged(0)
            }
        }

        fun getTrack(position: Int): TrackDto? = items.getOrNull(position)

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemQueueCarouselBinding.inflate(android.view.LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position], position)
        }

        override fun getItemCount(): Int = items.size

        inner class ViewHolder(private val binding: ItemQueueCarouselBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(track: TrackDto, position: Int) {
                binding.albumArt.load(track.thumbnailUrl) {
                    placeholder(R.drawable.album_placeholder)
                    error(R.drawable.album_placeholder)
                }
                
                if (position == 0) {
                    binding.albumArt.alpha = if (isPlaying) 1.0f else 0.5f
                } else {
                    binding.albumArt.alpha = 1.0f
                }

                binding.root.setOnClickListener {
                    if (position == 0) {
                        viewModel.togglePlayPause()
                    }
                }
            }
        }
    }
}
