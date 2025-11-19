package com.example.music_room.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

class ShimmerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var translateX = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1500L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            val fraction = it.animatedValue as Float
            translateX = width * 2 * fraction - width
            invalidate()
        }
    }

    private val shimmerColor = Color.parseColor("#40FFFFFF")
    private val baseColor = Color.parseColor("#20FFFFFF")

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator.cancel()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateShader(w.toFloat())
    }

    private fun updateShader(width: Float) {
        val shader = LinearGradient(
            0f, 0f, width, 0f,
            intArrayOf(baseColor, shimmerColor, baseColor),
            floatArrayOf(0.2f, 0.5f, 0.8f),
            Shader.TileMode.CLAMP
        )
        paint.shader = shader
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.translate(translateX, 0f)
        canvas.drawRect(-width.toFloat(), 0f, width * 2f, height.toFloat(), paint)
        canvas.restore()
    }
}
