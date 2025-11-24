package com.example.music_room.utils

import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.CompositePageTransformer
import androidx.viewpager2.widget.MarginPageTransformer
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.abs

/**
 * CarouselHelper - ViewPager2 carousel configuration utilities
 * 
 * Extracted from PlayerActivity and RoomDetailActivity to eliminate duplication.
 * Provides standard carousel setup with peek, scale, and alpha effects.
 */
object CarouselHelper {
    
    private const val DEFAULT_OFFSCREEN_LIMIT = 3
    private const val DEFAULT_MIN_SCALE = 0.75f
    private const val DEFAULT_MIN_ALPHA = 0.35f
    
    /**
     * Apply standard carousel configuration to a ViewPager2.
     * 
     * @param viewPager The ViewPager2 to configure
     * @param marginDp Margin between pages in dp
     * @param paddingPx Horizontal padding for peek effect
     * @param offscreenLimit Number of pages to keep offscreen (default: 3)
     */
    fun setupCarousel(
        viewPager: ViewPager2,
        marginDp: Int,
        paddingPx: Int,
        offscreenLimit: Int = DEFAULT_OFFSCREEN_LIMIT
    ) {
        viewPager.apply {
            clipToPadding = false
            clipChildren = false
            this.offscreenPageLimit = offscreenLimit
            
            // Get RecyclerView child for padding
            val recyclerView = getChildAt(0) as? RecyclerView
            recyclerView?.apply {
                clipToPadding = false
                clipChildren = false
                overScrollMode = RecyclerView.OVER_SCROLL_NEVER
                setPadding(paddingPx, 0, paddingPx, 0)
            }
        }
    }
    
    /**
     * Create a standard carousel page transformer with scale and alpha effects.
     * 
     * @param marginDp Margin between pages in dp
     * @param minScale Minimum scale for non-centered pages (default: 0.75)
     * @param minAlpha Minimum alpha for non-centered pages (default: 0.35)
     * @param translationFactor Overlap factor for side pages (default: 0.25)
     */
    fun createStandardTransformer(
        marginDp: Int,
        minScale: Float = DEFAULT_MIN_SCALE,
        minAlpha: Float = DEFAULT_MIN_ALPHA,
        translationFactor: Float = 0.25f
    ): CompositePageTransformer {
        return CompositePageTransformer().apply {
            addTransformer(MarginPageTransformer(marginDp))
            addTransformer { page, position ->
                val clampedPosition = position.coerceIn(-1f, 1f)
                val focusProgress = 1 - abs(clampedPosition)
                
                // Scale effect
                val scale = minScale + focusProgress * (1f - minScale)
                page.scaleY = scale
                page.scaleX = scale
                
                // Alpha effect
                val alpha = minAlpha + focusProgress * (1f - minAlpha)
                page.alpha = alpha
                
                // Translation for overlap
                page.translationX = -clampedPosition * page.width * translationFactor
            }
        }
    }
    
    /**
     * Create a simple carousel transformer with just scale effect.
     */
    fun createSimpleTransformer(
        marginDp: Int,
        minScale: Float = DEFAULT_MIN_SCALE
    ): CompositePageTransformer {
        return CompositePageTransformer().apply {
            addTransformer(MarginPageTransformer(marginDp))
            addTransformer { page, position ->
                val r = 1 - abs(position)
                val scale = minScale + r * (1f - minScale)
                page.scaleY = scale
                page.scaleX = scale
            }
        }
    }
}
