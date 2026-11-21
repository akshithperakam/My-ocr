package com.codecapture.extraction

import com.codecapture.domain.model.LineProvenance
import com.codecapture.domain.model.ReconstructedLine
import com.codecapture.vision.VisualOverlapMatcher
import kotlin.math.min

data class FrameLogicalLines(
    val frameId: String,
    val imagePath: String,
    val sequenceIndex: Int,
    val estimatedVisualOverlapRatio: Float?,
    val lines: List<LogicalCodeLine>
)

data class AssemblyResult(
    val lines: List<ReconstructedLine>,
    val flaggedGapCount: Int,
    val verifiedOverlapTransitions: Int
)

/**
 * Hybrid multi-image code reconstruction and cautious deduplication (FR-17, Section 15.2, AC-15).
 * Combines:
 *   1. Capture sequence order
 *   2. Optional line-number anchors
 *   3. Multi-line OCR suffix-prefix alignment
 *   4. Visual overlap ratio prior from the capture controller
 *
 * Repeated code blocks are handled cautiously: single-line similarity is NEVER enough to delete
 * lines unless supported by line-number alignment or multi-line contextual overlap.
 * Unverified/broken transitions insert an explicit gap marker rather than silently guessing.
 */
class MultiImageCodeAssembler {

    fun assemble(frames: List<FrameLogicalLines>): AssemblyResult {
        if (frames.isEmpty()) return AssemblyResult(emptyList(), 0, 0)

        val assembled = mutableListOf<ReconstructedLine>()
        var flaggedGaps = 0
        var verifiedTransitions = 0

        // Seed with Frame 0
        val first = frames.first()
        first.lines.forEach { line ->
            assembled += toReconstructedLine(assembled.size, line, first)
        }

        // Sequentially align each subsequent frame
        for (idx in 1 until frames.size) {
            val curr = frames[idx]
            if (curr.lines.isEmpty()) {
                flaggedGaps++
                assembled += createGapMarker(
                    index = assembled.size,
                    reason = "Frame #${curr.sequenceIndex} produced no readable text (possible blur or glare)."
                )
                continue
            }

            val overlapK = findVerifiedOverlapCount(
                existingLines = assembled,
                nextFrame = curr
            )

            if (overlapK > 0) {
                verifiedTransitions++
                // Merge provenance for overlapped lines and keep higher-confidence text
                val startIdx = assembled.size - overlapK
                for (i in 0 until overlapK) {
                    val existing = assembled[startIdx + i]
                    val incoming = curr.lines[i]
                    val newProv = LineProvenance(
                        frameId = curr.frameId,
                        imagePath = curr.imagePath,
                        sequenceIndex = curr.sequenceIndex,
                        boundingBox = incoming.boundingBox,
                        rawOcrText = incoming.rawOcrText
                    )
                    val bestText = if (incoming.confidence > existing.confidence + 0.04f) {
                        incoming.text
                    } else {
                        existing.text
                    }
                    assembled[startIdx + i] = existing.copy(
                        text = bestText,
                        confidence = maxOf(existing.confidence, incoming.confidence),
                        logicalLineNumberHint = existing.logicalLineNumberHint ?: incoming.logicalLineNumberHint,
                        provenance = existing.provenance + newProv
                    )
                }

                // Append newly uncovered lines after the verified overlap
                for (i in overlapK until curr.lines.size) {
                    assembled += toReconstructedLine(assembled.size, curr.lines[i], curr)
                }
            } else {
                // No verified overlap -> do NOT silently drop lines; flag a potential gap
                flaggedGaps++
                assembled += createGapMarker(
                    index = assembled.size,
                    reason = "Unverified overlap between frame #${frames[idx - 1].sequenceIndex} and frame #${curr.sequenceIndex}; review original photos for possible missing or duplicate lines."
                )
                curr.lines.forEach { line ->
                    assembled += toReconstructedLine(assembled.size, line, curr)
                }
            }
        }

        // Re-index cleanly
        val finalLines = assembled.mapIndexed { i, line -> line.copy(lineIndex = i) }
        return AssemblyResult(
            lines = finalLines,
            flaggedGapCount = flaggedGaps,
            verifiedOverlapTransitions = verifiedTransitions
        )
    }

    /**
     * Finds the best verified overlap length k (number of lines shared between suffix of existingLines
     * and prefix of nextFrame.lines). Returns 0 if no overlap is supported by strong evidence.
     */
    private fun findVerifiedOverlapCount(
        existingLines: List<ReconstructedLine>,
        nextFrame: FrameLogicalLines
    ): Int {
        val nextLines = nextFrame.lines
        val maxWindow = min(existingLines.size, nextLines.size).coerceAtMost(40)
        if (maxWindow == 0) return 0

        // Priority 1: Strong optional line-number anchor check
        val lastNumberedExisting = existingLines.lastOrNull { it.logicalLineNumberHint != null }
        val firstNumberedNext = nextLines.firstOrNull { it.lineNumberHint != null }
        if (lastNumberedExisting?.logicalLineNumberHint != null && firstNumberedNext?.lineNumberHint != null) {
            val lastNum = lastNumberedExisting.logicalLineNumberHint
            val firstNum = firstNumberedNext.lineNumberHint
            if (firstNum <= lastNum) {
                val expectedOverlap = (lastNum - firstNum + 1).coerceIn(1, maxWindow)
                val sim = scoreOverlapWindow(existingLines, nextLines, expectedOverlap)
                if (sim >= 0.62f) {
                    return expectedOverlap
                }
            }
        }

        // Priority 2: Multi-line contextual suffix-prefix matching with visual overlap prior
        val expectedFromVisual = nextFrame.estimatedVisualOverlapRatio?.let {
            (it * nextLines.size).toInt()
        }

        var bestK = 0
        var bestScore = 0f

        for (k in 1..maxWindow) {
            val textSim = scoreOverlapWindow(existingLines, nextLines, k)

            // Guardrail against repeated single lines (e.g. closing braces "}" or blank lines):
            // Require >= 2 lines OR a long distinctive single line (>= 18 non-space chars)
            if (k == 1) {
                val singleText = nextLines.first().text.trim()
                if (singleText.length < 18 || textSim < 0.90f) continue
            } else {
                // Require at least one non-trivial line in the overlap window so repeated "}" blocks don't falsely merge
                val nonTrivialCount = (0 until k).count { nextLines[it].text.trim().length >= 6 }
                if (nonTrivialCount == 0 && k < 3) continue
            }

            if (textSim < 0.72f) continue

            // Combine text similarity + slight preference for matching visual prior + multi-line length bonus
            val visualBonus = if (expectedFromVisual != null) {
                val dist = kotlin.math.abs(k - expectedFromVisual).toFloat() / nextLines.size.coerceAtLeast(1)
                (1f - dist).coerceIn(0f, 1f) * 0.12f
            } else {
                0f
            }
            val lengthBonus = min(k, 8) * 0.015f
            val totalScore = textSim + visualBonus + lengthBonus

            if (totalScore > bestScore) {
                bestScore = totalScore
                bestK = k
            }
        }

        return bestK
    }

    private fun scoreOverlapWindow(
        existingLines: List<ReconstructedLine>,
        nextLines: List<LogicalCodeLine>,
        k: Int
    ): Float {
        var sum = 0f
        var weightSum = 0f
        for (i in 0 until k) {
            val a = existingLines[existingLines.size - k + i].text.trim()
            val b = nextLines[i].text.trim()
            val weight = if (a.isEmpty() && b.isEmpty()) 0.35f else 1.0f
            val sim = if (a.isEmpty() && b.isEmpty()) 1.0f else VisualOverlapMatcher.normalizedSimilarity(a, b)
            sum += sim * weight
            weightSum += weight
        }
        return if (weightSum > 0f) sum / weightSum else 0f
    }

    private fun toReconstructedLine(
        index: Int,
        line: LogicalCodeLine,
        frame: FrameLogicalLines
    ): ReconstructedLine = ReconstructedLine(
        lineIndex = index,
        text = line.text,
        logicalLineNumberHint = line.lineNumberHint,
        confidence = line.confidence,
        isUserCorrected = false,
        isGapMarker = false,
        wrappedRowCount = line.wrappedRowCount,
        ambiguities = line.ambiguities,
        provenance = listOf(
            LineProvenance(
                frameId = frame.frameId,
                imagePath = frame.imagePath,
                sequenceIndex = frame.sequenceIndex,
                boundingBox = line.boundingBox,
                rawOcrText = line.rawOcrText
            )
        )
    )

    private fun createGapMarker(index: Int, reason: String): ReconstructedLine =
        ReconstructedLine(
            lineIndex = index,
            text = "// [CodeCapture GAP — $reason]",
            confidence = 0.0f,
            isGapMarker = true,
            gapReason = reason
        )
}
