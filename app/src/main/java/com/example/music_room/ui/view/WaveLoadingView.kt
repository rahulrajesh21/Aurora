package com.example.music_room.ui.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sin

class WaveLoadingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private var dotColor: Int = Color.WHITE
    private val dotCount = 3
    private val dotRadius = 6f.dpToPx()
    private val dotSpacing = 8f.dpToPx()
    private val waveAmplitude = 6f.dpToPx()
    
    private var animator: ValueAnimator? = null
    private var animationValue = 0f

    init {
        startAnimation()
    }

    fun setDotColor(color: Int) {
        this.dotColor = color
        paint.color = color
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = (dotCount * dotRadius * 2) + ((dotCount - 1) * dotSpacing) + paddingLeft + paddingRight
        val height = (waveAmplitude * 2) + (dotRadius * 2) + paddingTop + paddingBottom
        setMeasuredDimension(
            resolveSize(width.toInt(), widthMeasureSpec),
            resolveSize(height.toInt(), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val totalWidth = (dotCount * dotRadius * 2) + ((dotCount - 1) * dotSpacing)
        var startX = (width - totalWidth) / 2f + dotRadius

        val centerY = height / 2f

        for (i in 0 until dotCount) {
            // Calculate wave offset based on animation value and dot index
            // Each dot is offset by a phase shift
            val phase = i * (Math.PI / 2) // 90 degrees phase shift per dot
            val yOffset = sin(animationValue + phase).toFloat() * waveAmplitude
            
            canvas.drawCircle(startX, centerY + yOffset, dotRadius, paint)
            
            startX += (dotRadius * 2) + dotSpacing
        }
    }

    private fun startAnimation() {
        animator = ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
            duration = 1000 // 1 second for full cycle
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                animationValue = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (animator?.isStarted == false) {
            animator?.start()
        }
    }
    
    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        if (visibility == VISIBLE) {
            if (animator?.isStarted == false) animator?.start()
        } else {
            animator?.cancel()
        }
    }

    private fun Float.dpToPx(): Float {
        return this * resources.displayMetrics.density
    }
}
