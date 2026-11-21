package com.codecapture.vision

import android.graphics.Bitmap
import android.graphics.Color
import com.codecapture.domain.model.NormalizedRect
import kotlin.math.abs
import kotlin.math.min

data class EditorDetectionResult(
    val editorRect: NormalizedRect,
    val confidence: Float,
    val isFallbackFullFrame: Boolean
)

data class CameraMotionEstimate(
    val horizontalShiftRatio: Float,
    val borderDiffScore: Float,
    val significantCameraMotionDetected: Boolean
)

/**
 * Detects the active code editor viewport within a photographed monitor frame (FR-04)
 * and discriminates handheld camera motion from true vertical editor scrolling (FR-08, Section 6).
 */
class ScreenEditorDetector {

    /**
     * Locates the primary code-editor text region by finding the largest dense horizontal-gradient
     * band bounded by IDE chrome (sidebars, tab bars, status bars, monitor bezels).
     * Never requires the entire monitor bezel to be visible.
     */
    fun detectEditorRegion(bitmap: Bitmap, manualOverride: NormalizedRect? = null): EditorDetectionResult {
        if (manualOverride != null && manualOverride.isValid()) {
            return EditorDetectionResult(
                editorRect = manualOverride,
                confidence = 1.0f,
                isFallbackFullFrame = false
            )
        }

        val w = min(bitmap.width, 320)
        val h = (bitmap.height * (w.toFloat() / bitmap.width.coerceAtLeast(1))).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        if (small !== bitmap) small.recycle()

        val gray = FloatArray(pixels.size) { i ->
            val c = pixels[i]
            0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)
        }

        // Compute horizontal text-edge density per column and per row
        val rowEnergy = FloatArray(h)
        val colEnergy = FloatArray(w)
        for (y in 1 until h - 1) {
            val offset = y * w
            for (x in 1 until w - 1) {
                val gx = abs(gray[offset + x + 1] - gray[offset + x - 1])
                val gy = abs(gray[offset + x + w] - gray[offset + x - w])
                val textEdge = if (gx > 16f && gy > 10f) gx else 0f
                rowEnergy[y] += textEdge
                colEnergy[x] += textEdge
            }
        }

        val maxRow = rowEnergy.maxOrNull()?.coerceAtLeast(1f) ?: 1f
        val maxCol = colEnergy.maxOrNull()?.coerceAtLeast(1f) ?: 1f

        val rowThreshold = maxRow * 0.14f
        val colThreshold = maxCol * 0.12f

        val topRow = (0 until h).firstOrNull { rowEnergy[it] >= rowThreshold } ?: (h * 0.08f).toInt()
        val bottomRow = (h - 1 downTo 0).firstOrNull { rowEnergy[it] >= rowThreshold } ?: (h * 0.92f).toInt()
        val leftCol = (0 until w).firstOrNull { colEnergy[it] >= colThreshold } ?: (w * 0.06f).toInt()
        val rightCol = (w - 1 downTo 0).firstOrNull { colEnergy[it] >= colThreshold } ?: (w * 0.95f).toInt()

        // Slight inward margin to exclude editor tab bar and bottom status bar if tight
        val candidate = NormalizedRect(
            left = (leftCol.toFloat() / w).coerceIn(0.02f, 0.45f),
            top = (topRow.toFloat() / h).coerceIn(0.03f, 0.40f),
            right = (rightCol.toFloat() / w).coerceIn(0.55f, 0.98f),
            bottom = (bottomRow.toFloat() / h).coerceIn(0.60f, 0.97f)
        )

        val area = candidate.width * candidate.height
        return if (candidate.isValid() && area >= 0.22f) {
            val confidence = (area.coerceIn(0.22f, 0.85f) / 0.85f).coerceIn(0.45f, 0.95f)
            EditorDetectionResult(candidate, confidence, isFallbackFullFrame = false)
        } else {
            EditorDetectionResult(
                editorRect = NormalizedRect(0.05f, 0.08f, 0.95f, 0.92f),
                confidence = 0.35f,
                isFallbackFullFrame = true
            )
        }
    }

    /**
     * Crops the bitmap to the detected or manually selected editor viewport.
     */
    fun cropEditorRegion(bitmap: Bitmap, rect: NormalizedRect): Bitmap {
        val left = (rect.left * bitmap.width).toInt().coerceIn(0, bitmap.width - 2)
        val top = (rect.top * bitmap.height).toInt().coerceIn(0, bitmap.height - 2)
        val right = (rect.right * bitmap.width).toInt().coerceIn(left + 1, bitmap.width)
        val bottom = (rect.bottom * bitmap.height).toInt().coerceIn(top + 1, bitmap.height)
        return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    }

    /**
     * Estimates whether the smartphone itself moved between two frames (handheld mode)
     * by comparing static outer chrome/bezel strips outside the scrolling editor region.
     */
    fun estimateCameraMotion(
        previousFrame: Bitmap,
        currentFrame: Bitmap,
        editorRect: NormalizedRect
    ): CameraMotionEstimate {
        val w = 160
        val h = 120
        val prevSmall = Bitmap.createScaledBitmap(previousFrame, w, h, true)
        val currSmall = Bitmap.createScaledBitmap(currentFrame, w, h, true)

        val prevPixels = IntArray(w * h)
        val currPixels = IntArray(w * h)
        prevSmall.getPixels(prevPixels, 0, w, 0, 0, w, h)
        currSmall.getPixels(currPixels, 0, w, 0, 0, w, h)
        prevSmall.recycle()
        currSmall.recycle()

        val eLeft = (editorRect.left * w).toInt()
        val eRight = (editorRect.right * w).toInt()
        val eTop = (editorRect.top * h).toInt()
        val eBottom = (editorRect.bottom * h).toInt()

        var borderDiffSum = 0.0
        var borderCount = 0

        for (y in 0 until h) {
            for (x in 0 until w) {
                val insideEditor = x in eLeft..eRight && y in eTop..eBottom
                if (!insideEditor) {
                    val p = prevPixels[y * w + x]
                    val c = currPixels[y * w + x]
                    val pl = 0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)
                    val cl = 0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)
                    borderDiffSum += abs(pl - cl)
                    borderCount++
                }
            }
        }

        val borderDiff = if (borderCount > 0) (borderDiffSum / borderCount / 255.0).toFloat() else 0f

        // Estimate horizontal shift via column luminance profile cross-correlation on top chrome strip
        val stripRows = maxOf(4, eTop.coerceAtMost(h / 4))
        val prevCols = FloatArray(w)
        val currCols = FloatArray(w)
        for (y in 0 until stripRows) {
            for (x in 0 until w) {
                val p = prevPixels[y * w + x]
                val c = currPixels[y * w + x]
                prevCols[x] += Color.green(p).toFloat()
                currCols[x] += Color.green(c).toFloat()
            }
        }

        var bestShift = 0
        var bestErr = Float.MAX_VALUE
        val maxShift = 16
        for (shift in -maxShift..maxShift) {
            var err = 0f
            var samples = 0
            for (x in maxShift until w - maxShift) {
                err += abs(prevCols[x] - currCols[x + shift])
                samples++
            }
            val meanErr = if (samples > 0) err / samples else Float.MAX_VALUE
            if (meanErr < bestErr) {
                bestErr = meanErr
                bestShift = shift
            }
        }

        val horizontalShiftRatio = abs(bestShift).toFloat() / w
        val significantMotion = borderDiff > 0.11f || horizontalShiftRatio > 0.045f

        return CameraMotionEstimate(
            horizontalShiftRatio = horizontalShiftRatio,
            borderDiffScore = borderDiff,
            significantCameraMotionDetected = significantMotion
        )
    }
}
