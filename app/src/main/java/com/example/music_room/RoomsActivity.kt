package com.example.music_room

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.annotation.RawRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.caverock.androidsvg.SVG
import com.example.music_room.R
import com.example.music_room.data.AuroraServiceLocator
import com.example.music_room.databinding.ActivityRoomsBinding
import com.example.music_room.ui.RoomsAdapter
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

class RoomsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRoomsBinding
    private val repository = AuroraServiceLocator.repository
    private var lastClickTime = 0L
    private val roomsAdapter = RoomsAdapter { snapshot ->
        if (System.currentTimeMillis() - lastClickTime < 1000) return@RoomsAdapter
        lastClickTime = System.currentTimeMillis()
        
        val intent = Intent(this, RoomDetailActivity::class.java).apply {
            putExtra(RoomDetailActivity.EXTRA_ROOM_ID, snapshot.room.id)
            putExtra(RoomDetailActivity.EXTRA_ROOM_NAME, snapshot.room.name)
            putExtra(RoomDetailActivity.EXTRA_ROOM_LOCKED, snapshot.isLocked)
            putExtra(RoomDetailActivity.EXTRA_ROOM_HOST_ID, snapshot.room.hostId)
        }
        startActivity(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        binding = ActivityRoomsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.roomsRecyclerView.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, 2)
        binding.roomsRecyclerView.adapter = roomsAdapter
        loadCreateRoomTicketIcon()

        binding.createRoomButton.setOnClickListener {
            startActivity(Intent(this, CreateRoomActivity::class.java))
        }
        binding.backButton.setOnClickListener { finish() }
        binding.roomsRefreshLayout.setOnRefreshListener {
            lifecycleScope.launch { loadRooms(showSkeleton = false) }
        }

        lifecycleScope.launch { loadRooms() }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { loadRooms() }
    }

    private suspend fun loadRooms(showSkeleton: Boolean = true) {
        if (showSkeleton) {
            binding.roomsSkeleton.isVisible = true
            binding.roomsRecyclerView.isVisible = false
        }
        updateEmptyStateVisibility(false)
        repository.getRooms()
            .onSuccess { rooms ->
                roomsAdapter.submitList(rooms)
                updateEmptyStateVisibility(rooms.isEmpty())
                binding.roomsRecyclerView.isVisible = true
            }
            .onFailure { error ->
                updateEmptyStateVisibility(true, error.message)
                Toast.makeText(this, error.message ?: getString(R.string.rooms_empty), Toast.LENGTH_LONG).show()
            }
        binding.roomsSkeleton.isVisible = false
        binding.roomsRefreshLayout.isRefreshing = false
    }

    private fun updateEmptyStateVisibility(show: Boolean, customMessage: String? = null) {
        binding.roomsEmptyState.apply {
            isVisible = show
            text = customMessage ?: getString(R.string.rooms_empty)
        }
        binding.emptyStateAnimationContainer.isVisible = show
        binding.emptyStateAnimationOverlay.isVisible = show
        val animationView = binding.root.findViewById<com.airbnb.lottie.LottieAnimationView>(R.id.emptyStateAnimation)
        if (show) {
            if (!animationView.isAnimating) {
                animationView.progress = 0f
                animationView.playAnimation()
            }
        } else {
            animationView.pauseAnimation()
        }
    }

    private fun loadCreateRoomTicketIcon() {
        val iconDrawable = svgToBitmapDrawable(R.raw.create_room_ticket)
        if (iconDrawable == null) {
            Log.w("RoomsActivity", "Unable to render ticket SVG, keeping default icon")
            return
        }
        binding.createRoomIcon.setImageDrawable(iconDrawable)
    }

    private fun svgToBitmapDrawable(@RawRes svgResId: Int): BitmapDrawable? {
        return runCatching {
            val svg = SVG.getFromResource(resources, svgResId)
            val viewBox = svg.documentViewBox
            val documentWidth = if (svg.documentWidth != -1f) svg.documentWidth else viewBox?.width() ?: 100f
            val documentHeight = if (svg.documentHeight != -1f) svg.documentHeight else viewBox?.height() ?: 100f
            val targetSize = resources.getDimension(R.dimen.create_room_ticket_icon_size)
            val scale = targetSize / max(documentWidth, documentHeight)
            val bitmapWidth = max(1, (documentWidth * scale).roundToInt())
            val bitmapHeight = max(1, (documentHeight * scale).roundToInt())
            val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.scale(scale, scale)
            svg.renderToCanvas(canvas)
            BitmapDrawable(resources, bitmap)
        }.getOrElse {
            Log.e("RoomsActivity", "Failed to load SVG", it)
            null
        }
    }
}
