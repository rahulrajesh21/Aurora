package com.example.music_room.utils

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil.Coil
import coil.request.ImageRequest

object PaletteThemeHelper {
    private val DEFAULT_BACKGROUND = Color.parseColor("#101014")
    private const val DEFAULT_TEXT = Color.WHITE
    private const val MIN_CONTRAST = 2.6

    fun applyFromAlbumArt(
        context: Context,
        imageUrl: String?,
        onColors: (backgroundColor: Int, accentColor: Int) -> Unit
    ) {
        if (imageUrl.isNullOrBlank()) {
            onColors(DEFAULT_BACKGROUND, DEFAULT_TEXT)
            return
        }

        val request = ImageRequest.Builder(context)
            .data(imageUrl)
            .allowHardware(false)
            .target(
                onSuccess = { result ->
                    val bitmap = (result as BitmapDrawable).bitmap
                    Palette.from(bitmap)
                        .clearFilters()
                        .generate { palette ->
                            val background = palette?.getDarkMutedColor(DEFAULT_BACKGROUND)
                                ?: palette?.getDarkVibrantColor(DEFAULT_BACKGROUND)
                                ?: palette?.getMutedColor(DEFAULT_BACKGROUND)
                                ?: palette?.getDominantColor(DEFAULT_BACKGROUND)
                                ?: DEFAULT_BACKGROUND

                            val accentCandidate = pickAccentColor(palette, background)
                            val accent = ensureReadableAccent(accentCandidate, background)
                            onColors(background, accent)
                        }
                },
                onError = {
                    onColors(DEFAULT_BACKGROUND, DEFAULT_TEXT)
                }
            )
            .build()

        Coil.imageLoader(context).enqueue(request)
    }

        private fun pickAccentColor(palette: Palette?, background: Int): Int {
            val swatches = listOfNotNull(
                palette?.vibrantSwatch,
                palette?.lightVibrantSwatch,
                palette?.darkVibrantSwatch,
                palette?.mutedSwatch,
                palette?.lightMutedSwatch,
                palette?.dominantSwatch
            )

            val prioritized = swatches
                .sortedByDescending { it.population }
                .map { it.rgb }

            val firstSaturated = prioritized.firstOrNull { !isTooNeutral(it) }

            return firstSaturated ?: generateAccentFromBackground(background)
        }

        private fun ensureReadableAccent(color: Int, background: Int): Int {
        var attemptColor = color
        var contrast = ColorUtils.calculateContrast(attemptColor, background)
        val backgroundDark = isColorDark(background)
        var blend = 0.15f
        while (contrast < MIN_CONTRAST && blend <= 0.9f) {
            attemptColor = if (backgroundDark) {
                ColorUtils.blendARGB(attemptColor, Color.WHITE, blend)
            } else {
                ColorUtils.blendARGB(attemptColor, Color.BLACK, blend)
            }
            contrast = ColorUtils.calculateContrast(attemptColor, background)
            blend += 0.15f
        }

        if (contrast < MIN_CONTRAST) {
            attemptColor = if (backgroundDark) Color.WHITE else Color.BLACK
        }
        return attemptColor
    }

    private fun isColorDark(color: Int): Boolean {
        return ColorUtils.calculateLuminance(color) < 0.45
    }

    private fun isTooNeutral(color: Int): Boolean {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        return hsv[1] < 0.25f || hsv[2] < 0.2f
    }

    private fun generateAccentFromBackground(background: Int): Int {
        val backgroundDark = isColorDark(background)
        val blendTarget = if (backgroundDark) Color.WHITE else Color.BLACK
        return ColorUtils.blendARGB(background, blendTarget, 0.35f)
    }
}
