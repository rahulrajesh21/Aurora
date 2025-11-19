package com.example.music_room.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.graphics.ColorUtils

class GradientView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var time = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 10000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            time += 0.005f
            invalidate()
        }
    }

    // Aurora Colors
    private val colorPurple = Color.parseColor("#DEBDFF")
    private val colorGreen = Color.parseColor("#E1FFBA")
    private val colorBlue = Color.parseColor("#B0E0E6") // Powder Blue
    private val colorDark = Color.parseColor("#0D0F23")

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()

        // Base dark background
        canvas.drawColor(colorDark)

        // Blob 1: Purple (Moving circular)
        val x1 = w * (0.5f + 0.3f * kotlin.math.sin(time))
        val y1 = h * (0.4f + 0.2f * kotlin.math.cos(time * 0.7f))
        drawBlob(canvas, x1, y1, w * 0.8f, colorPurple, 0.4f)

        // Blob 2: Green (Moving elliptical)
        val x2 = w * (0.2f + 0.3f * kotlin.math.cos(time * 0.5f))
        val y2 = h * (0.7f + 0.3f * kotlin.math.sin(time * 0.8f))
        drawBlob(canvas, x2, y2, w * 0.7f, colorGreen, 0.3f)

        // Blob 3: Blue (Moving diagonal)
        val x3 = w * (0.8f + 0.2f * kotlin.math.sin(time * 1.2f))
        val y3 = h * (0.2f + 0.2f * kotlin.math.cos(time))
        drawBlob(canvas, x3, y3, w * 0.6f, colorBlue, 0.3f)
    }

    private fun drawBlob(canvas: Canvas, x: Float, y: Float, radius: Float, color: Int, alpha: Float) {
        val shader = RadialGradient(
            x, y, radius,
            intArrayOf(ColorUtils.setAlphaComponent(color, (255 * alpha).toInt()), Color.TRANSPARENT),
            null,
            Shader.TileMode.CLAMP
        )
        paint.shader = shader
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }
}
