package com.codecapture.camera

import android.graphics.Bitmap
import android.graphics.Color
import com.codecapture.domain.model.ImageQualityMetrics
import kotlin.math.min

/**
 * Fast on-device image quality assessment for Pixel 8a:
 * - Downsamples to <= 480px wide for low-latency analysis (NFR-02).
 * - Computes Laplacian variance for focus/blur detection.
 * - Computes specular glare ratio for monitor reflections.
 * - Evaluates luminance for under/over-exposure warnings.
 */
class ImageQualityAnalyzer(
    private val minSharpnessThreshold: Float = 18.0f,
    private val maxGlareRatio: Float = 0.085f,
    private val minLuminance: Float = 22.0f,
    private val maxLuminance: Float = 235.0f
) {

    fun analyze(bitmap: Bitmap): ImageQualityMetrics {
        val sampleWidth = min(bitmap.width, 480)
        val scale = sampleWidth.toFloat() / bitmap.width.coerceAtLeast(1)
        val sampleHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val scaled = if (sampleWidth < bitmap.width) {
            Bitmap.createScaledBitmap(bitmap, sampleWidth, sampleHeight, true)
        } else {
            bitmap
        }

        val pixels = IntArray(sampleWidth * sampleHeight)
        scaled.getPixels(pixels, 0, sampleWidth, 0, 0, sampleWidth, sampleHeight)
        if (scaled !== bitmap) {
            scaled.recycle()
        }

        val gray = FloatArray(pixels.size)
        var lumSum = 0.0
        var glareCount = 0

        for (i in pixels.indices) {
            val c = pixels[i]
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            val l = 0.299f * r + 0.587f * g + 0.114f * b
            gray[i] = l
            lumSum += l
            if (r >= 248 && g >= 248 && b >= 248) {
                glareCount++
            }
        }

        val meanLum = (lumSum / pixels.size.coerceAtLeast(1)).toFloat()
        val glareRatio = glareCount.toFloat() / pixels.size.coerceAtLeast(1)

        // 4-neighbor discrete Laplacian variance on interior pixels
        var lapSum = 0.0
        var lapSqSum = 0.0
        var count = 0
        for (y in 1 until sampleHeight - 1) {
            val rowOffset = y * sampleWidth
            for (x in 1 until sampleWidth - 1) {
                val idx = rowOffset + x
                val lap = (-4.0 * gray[idx]) +
                    gray[idx - 1] + gray[idx + 1] +
                    gray[idx - sampleWidth] + gray[idx + sampleWidth]
                lapSum += lap
                lapSqSum += lap * lap
                count++
            }
        }

        val meanLap = if (count > 0) lapSum / count else 0.0
        val variance = if (count > 0) (lapSqSum / count) - (meanLap * meanLap) else 0.0
        // Scale down to intuitive 0..100+ sharpness score
        val sharpnessScore = (variance / 12.0).toFloat().coerceAtLeast(0f)

        val warnings = mutableListOf<String>()
        if (sharpnessScore < minSharpnessThreshold) {
            warnings += "Image appears blurry (sharpness=${"%.1f".format(sharpnessScore)}). Refocusing recommended."
        }
        if (glareRatio > maxGlareRatio) {
            warnings += "Excessive screen glare detected (${"%.1f".format(glareRatio * 100f)}%). Adjust angle or lighting."
        }
        if (meanLum < minLuminance) {
            warnings += "Image is too dark; increase monitor brightness or camera exposure."
        } else if (meanLum > maxLuminance) {
            warnings += "Image is overexposed; reduce exposure compensation."
        }

        val acceptable = sharpnessScore >= minSharpnessThreshold && glareRatio <= maxGlareRatio * 1.35f
        return ImageQualityMetrics(
            sharpnessScore = sharpnessScore,
            glareRatio = glareRatio,
            meanLuminance = meanLum,
            widthPx = bitmap.width,
            heightPx = bitmap.height,
            isAcceptable = acceptable,
            warnings = warnings
        )
    }
}
