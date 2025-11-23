package com.example.music_room.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import androidx.annotation.ColorInt
import com.example.music_room.R
import kotlin.math.roundToInt

class ProgressPillDrawable(context: Context) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val maxWidth = context.resources.getDimension(R.dimen.player_thumb_width)
    private val height = context.resources.getDimension(R.dimen.player_thumb_height)
    private val minWidth = height // Minimum width equals height, making it a circle at minimum
    
    // Scale factor for the width: 0.0 (min) to 1.0 (max)
    private var widthScale = 1.0f
    
    // The actual width to draw
    private var currentWidth = maxWidth

    // Progress fraction: 0.0 to 1.0
    private var progressFraction = 0f

    fun setPillColor(@ColorInt color: Int) {
        paint.color = color
        invalidateSelf()
    }

    fun setProgressFraction(fraction: Float) {
        val clamped = fraction.coerceIn(0f, 1f)
        if (progressFraction != clamped) {
            progressFraction = clamped
            invalidateSelf()
        }
    }

    fun setWidthScale(scale: Float) {
        val clamped = scale.coerceIn(0f, 1f)
        if (widthScale != clamped) {
            widthScale = clamped
            // Calculate new width based on scale
            // Scale from minWidth (circle) to maxWidth (full pill)
            currentWidth = minWidth + (maxWidth - minWidth) * widthScale
            invalidateSelf()
        }
    }
    
    fun getCurrentWidth(): Float = currentWidth

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        val centerY = bounds.exactCenterY()
        val centerX = bounds.exactCenterX()
        
        // Calculate horizontal offset to align pill edge with track
        // We interpolate between two behaviors:
        // 1. Start (p=0): Anchor Left Edge at -1.5*minWidth (relative to center). Grows Right.
        //    Offset = currentWidth/2 - 1.5*minWidth
        // 2. End (p=1): Anchor Right Edge at +0.5*minWidth (relative to center). Shrinks Left.
        //    Offset = minWidth/2 - currentWidth/2
        //
        // Calculate horizontal offset to align pill edge with track
        // We interpolate between a Start Offset and an End Offset.
        
        // Start (0%): Align left edge at -1.75 * minWidth
        val startOffset = (currentWidth / 2f) - (1.75f * minWidth)
        
        // End (100%): Align right edge with track end.
        // SeekBar stops thumb center at (ViewWidth - maxWidth/2).
        // We want Right Edge at ViewWidth.
        // Right Edge = Center + Offset + currentWidth/2
        // ViewWidth = (ViewWidth - maxWidth/2) + Offset + minWidth/2
        // Offset = (maxWidth - minWidth) / 2
        val endOffset = (maxWidth - minWidth) / 2f
        
        // Interpolate
        val offset = startOffset * (1f - progressFraction) + endOffset * progressFraction
        
        // Draw pill centered at drawCenterX
        val drawCenterX = centerX + offset
        val left = drawCenterX - (currentWidth / 2f)
        val right = drawCenterX + (currentWidth / 2f)
        
        val halfHeight = height / 2f
        val top = centerY - halfHeight
        val bottom = centerY + halfHeight

        // Draw a pill (rounded rect with large radius)
        val radius = height / 2f // Fully rounded ends
        
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = maxWidth.roundToInt()

    override fun getIntrinsicHeight(): Int = height.roundToInt()
}
