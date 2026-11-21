package com.codecapture.vision

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

data class OverlapMatchResult(
    val displacementRatio: Float,    // Positive = scrolled down (0.0 = identical, 0.80 = 20% overlap)
    val overlapRatio: Float,         // 1.0 - displacementRatio when scrolling down
    val visualCorrelation: Float,    // Normalized cross-correlation [-1..1]
    val textMatchScore: Float?,      // Optional OCR line-sequence overlap score [0..1]
    val combinedConfidence: Float,   // Weighted hybrid confidence [0..1]
    val isIdenticalFrame: Boolean
)

/**
 * Hybrid visual + text overlap estimator (FR-08, FR-09).
 * Computes vertical scroll displacement using row-wise 2D normalized cross-correlation (NCC)
 * on downsampled grayscale editor crops, optionally fused with OCR line-sequence verification.
 */
class VisualOverlapMatcher {

    fun estimateVerticalOverlap(
        previousEditorCrop: Bitmap,
        currentEditorCrop: Bitmap,
        previousLines: List<String> = emptyList(),
        currentLines: List<String> = emptyList()
    ): OverlapMatchResult {
        val targetW = 128
        val targetH = 192

        val prevGray = downsampleToGray(previousEditorCrop, targetW, targetH)
        val currGray = downsampleToGray(currentEditorCrop, targetW, targetH)

        // Compare identical-frame difference first (shift = 0)
        val zeroShiftNcc = computeStripNcc(prevGray, currGray, targetW, targetH, shiftY = 0)
        var bestShift = 0
        var bestNcc = zeroShiftNcc

        // Search vertical scroll shifts from -15% (scrolled up) to +92% (scrolled down)
        val minShift = -(targetH * 0.15f).toInt()
        val maxShift = (targetH * 0.92f).toInt()

        // Coarse search (step 3) then fine refinement (step 1) for Pixel 8a speed
        var coarseBestShift = 0
        var coarseBestNcc = -1f
        var shift = minShift
        while (shift <= maxShift) {
            val ncc = computeStripNcc(prevGray, currGray, targetW, targetH, shift)
            if (ncc > coarseBestNcc) {
                coarseBestNcc = ncc
                coarseBestShift = shift
            }
            shift += 3
        }

        for (fineShift in (coarseBestShift - 3).coerceAtLeast(minShift)..(coarseBestShift + 3).coerceAtMost(maxShift)) {
            val ncc = computeStripNcc(prevGray, currGray, targetW, targetH, fineShift)
            if (ncc > bestNcc) {
                bestNcc = ncc
                bestShift = fineShift
            }
        }

        val displacementRatio = bestShift.toFloat() / targetH
        val overlapRatio = (1f - abs(displacementRatio)).coerceIn(0f, 1f)
        val isIdentical = abs(displacementRatio) < 0.018f && zeroShiftNcc > 0.94f

        val textScore = if (previousLines.isNotEmpty() && currentLines.isNotEmpty()) {
            computeTextOverlapScore(previousLines, currentLines)
        } else {
            null
        }

        val visualConfidence = bestNcc.coerceIn(0f, 1f)
        val combinedConfidence = if (textScore != null) {
            (0.60f * visualConfidence + 0.40f * textScore).coerceIn(0f, 1f)
        } else {
            visualConfidence
        }

        return OverlapMatchResult(
            displacementRatio = displacementRatio,
            overlapRatio = overlapRatio,
            visualCorrelation = bestNcc,
            textMatchScore = textScore,
            combinedConfidence = combinedConfidence,
            isIdenticalFrame = isIdentical
        )
    }

    /**
     * Computes NCC between the overlapping region of prev (shifted down by shiftY) and curr.
     * Positive shiftY means content moved UP on screen (user scrolled DOWN).
     */
    private fun computeStripNcc(
        prev: FloatArray,
        curr: FloatArray,
        w: Int,
        h: Int,
        shiftY: Int
    ): Float {
        val prevStart = shiftY.coerceAtLeast(0)
        val currStart = (-shiftY).coerceAtLeast(0)
        val overlapRows = h - abs(shiftY)
        if (overlapRows < 14) return -1f

        var sumP = 0.0
        var sumC = 0.0
        var count = 0
        // Sample every 2nd pixel horizontally for 2x speedup with negligible accuracy loss
        for (r in 0 until overlapRows step 2) {
            val pRow = (prevStart + r) * w
            val cRow = (currStart + r) * w
            for (x in 0 until w step 2) {
                sumP += prev[pRow + x]
                sumC += curr[cRow + x]
                count++
            }
        }
        if (count == 0) return -1f
        val meanP = sumP / count
        val meanC = sumC / count

        var num = 0.0
        var denP = 0.0
        var denC = 0.0
        for (r in 0 until overlapRows step 2) {
            val pRow = (prevStart + r) * w
            val cRow = (currStart + r) * w
            for (x in 0 until w step 2) {
                val dp = prev[pRow + x] - meanP
                val dc = curr[cRow + x] - meanC
                num += dp * dc
                denP += dp * dp
                denC += dc * dc
            }
        }
        val denom = sqrt(denP * denC)
        return if (denom < 1e-6) 0f else (num / denom).toFloat()
    }

    private fun downsampleToGray(bitmap: Bitmap, w: Int, h: Int): FloatArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        if (scaled !== bitmap) scaled.recycle()

        return FloatArray(pixels.size) { i ->
            val c = pixels[i]
            0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)
        }
    }

    /**
     * Checks whether the suffix of previousLines matches the prefix of currentLines in order.
     */
    fun computeTextOverlapScore(previousLines: List<String>, currentLines: List<String>): Float {
        val normPrev = previousLines.map { it.trim() }.filter { it.isNotEmpty() }
        val normCurr = currentLines.map { it.trim() }.filter { it.isNotEmpty() }
        if (normPrev.isEmpty() || normCurr.isEmpty()) return 0f

        val maxK = min(normPrev.size, normCurr.size)
        var bestRatio = 0f
        for (k in 1..maxK) {
            var matches = 0f
            for (i in 0 until k) {
                val a = normPrev[normPrev.size - k + i]
                val b = normCurr[i]
                matches += normalizedSimilarity(a, b)
            }
            val avg = matches / k
            if (avg > bestRatio && k >= 2) {
                bestRatio = avg
            }
        }
        return bestRatio.coerceIn(0f, 1f)
    }

    companion object {
        fun normalizedSimilarity(a: String, b: String): Float {
            if (a == b) return 1f
            if (a.isEmpty() || b.isEmpty()) return 0f
            val dist = levenshteinDistance(a, b)
            val maxLen = maxOf(a.length, b.length)
            return (1f - dist.toFloat() / maxLen).coerceIn(0f, 1f)
        }

        fun levenshteinDistance(a: String, b: String): Int {
            if (a == b) return 0
            if (a.isEmpty()) return b.length
            if (b.isEmpty()) return a.length
            var prev = IntArray(b.length + 1) { it }
            var curr = IntArray(b.length + 1)
            for (i in 1..a.length) {
                curr[0] = i
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    curr[j] = minOf(
                        prev[j] + 1,
                        curr[j - 1] + 1,
                        prev[j - 1] + cost
                    )
                }
                val tmp = prev
                prev = curr
                curr = tmp
            }
            return prev[b.length]
        }
    }
}
