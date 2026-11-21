package com.codecapture.vision

import com.codecapture.domain.model.CaptureConfig
import com.codecapture.domain.model.OperatingMode
import com.codecapture.domain.model.ScrollCalibration
import kotlin.math.abs
import kotlin.math.roundToInt

enum class ScrollDecisionType {
    ACCEPT_FRAME,
    SCROLL_MORE_DOWN,
    SCROLL_BACK_UP_OVERSHOOT,
    NEED_MANUAL_INTERVENTION
}

data class ScrollControlDecision(
    val type: ScrollDecisionType,
    val ticksToSend: Int, // Positive = down, negative = up
    val updatedTicksPerScreenHeight: Float,
    val reason: String
)

/**
 * Closed-loop proportional-adaptive feedback controller (FR-07, Section 5.3–5.4).
 * Computes initial scroll from visible viewport height * (1 - desiredOverlap),
 * then iteratively measures actual displacement and applies damped corrections until
 * the target overlap band is met without skipping lines.
 */
class AdaptiveScrollController(
    initialTicksPerScreenHeight: Float = 12.0f
) {
    var estimatedTicksPerScreenHeight: Float = initialTicksPerScreenHeight.coerceIn(2.0f, 120.0f)
        private set

    fun effectiveTargetOverlap(config: CaptureConfig, detectionConfidence: Float): Float {
        var target = config.targetOverlapRatio.coerceIn(0.12f, 0.50f)
        if (config.operatingMode == OperatingMode.HANDHELD) {
            target = maxOf(target, 0.28f)
        }
        if (detectionConfidence < 0.65f) {
            target = (target + 0.10f).coerceAtMost(0.45f)
        }
        return target
    }

    /**
     * Initial scroll command before first verification frame.
     * Uses conservative damping (0.82x in fixed mode, 0.68x in handheld mode) to prevent overshoot.
     */
    fun computeInitialScrollTicks(
        config: CaptureConfig,
        detectionConfidence: Float,
        deviceCalibration: ScrollCalibration? = null
    ): Int {
        val targetOverlap = effectiveTargetOverlap(config, detectionConfidence)
        val desiredDisplacement = (1f - targetOverlap).coerceIn(0.40f, 0.88f)
        val safetyFactor = if (config.operatingMode == OperatingMode.HANDHELD) 0.68f else 0.82f
        val sensitivity = config.scrollSensitivity.coerceIn(0.25f, 3.0f)

        val baseTicks = estimatedTicksPerScreenHeight * desiredDisplacement * safetyFactor * sensitivity
        val maxSingleBurst = if (config.operatingMode == OperatingMode.HANDHELD) 8 else 18
        return baseTicks.roundToInt().coerceIn(1, maxSingleBurst)
    }

    /**
     * Closed-loop feedback update after observing actual visual displacement.
     */
    fun evaluateAndCorrect(
        observedDisplacementRatio: Float,
        matchConfidence: Float,
        ticksAlreadySent: Int,
        correctionAttempt: Int,
        config: CaptureConfig
    ): ScrollControlDecision {
        val targetOverlap = effectiveTargetOverlap(config, matchConfidence)
        val targetDisplacement = (1f - targetOverlap).coerceIn(0.45f, 0.88f)
        val minAcceptableOverlap = (targetOverlap - 0.06f).coerceAtLeast(0.10f)
        val maxAcceptableOverlap = (targetOverlap + 0.12f).coerceAtMost(0.65f)

        // Update online gain estimate (EMA) when a meaningful movement was observed
        if (ticksAlreadySent > 0 && observedDisplacementRatio > 0.08f && matchConfidence >= 0.55f) {
            val observedTicksPerScreen = ticksAlreadySent / observedDisplacementRatio
            val clampedObserved = observedTicksPerScreen.coerceIn(2.0f, 120.0f)
            estimatedTicksPerScreenHeight =
                (0.65f * estimatedTicksPerScreenHeight + 0.35f * clampedObserved)
        }

        val observedOverlap = (1f - observedDisplacementRatio).coerceIn(0f, 1f)

        // Case 1: Overshoot! Overlap fell below safe floor (or match confidence collapsed due to excessive jump)
        if (observedOverlap < minAcceptableOverlap || observedDisplacementRatio > (1f - minAcceptableOverlap)) {
            if (correctionAttempt >= config.maxCorrectionSteps) {
                return ScrollControlDecision(
                    type = ScrollDecisionType.NEED_MANUAL_INTERVENTION,
                    ticksToSend = 0,
                    updatedTicksPerScreenHeight = estimatedTicksPerScreenHeight,
                    reason = "Exceeded max correction attempts after scroll overshoot."
                )
            }
            val overshootRatio = (observedDisplacementRatio - targetDisplacement).coerceAtLeast(0.12f)
            val backTicks = (overshootRatio * estimatedTicksPerScreenHeight * 0.85f)
                .roundToInt()
                .coerceIn(1, 10)
            return ScrollControlDecision(
                type = ScrollDecisionType.SCROLL_BACK_UP_OVERSHOOT,
                ticksToSend = -backTicks,
                updatedTicksPerScreenHeight = estimatedTicksPerScreenHeight,
                reason = "Overlap ${(observedOverlap * 100).roundToInt()}% below safe threshold; scrolling back $backTicks ticks."
            )
        }

        // Case 2: Within acceptable overlap band AND enough new content seen
        if (observedOverlap in minAcceptableOverlap..maxAcceptableOverlap &&
            observedDisplacementRatio >= 0.25f &&
            matchConfidence >= 0.50f
        ) {
            return ScrollControlDecision(
                type = ScrollDecisionType.ACCEPT_FRAME,
                ticksToSend = 0,
                updatedTicksPerScreenHeight = estimatedTicksPerScreenHeight,
                reason = "Target overlap achieved (${(observedOverlap * 100).roundToInt()}%, confidence ${(matchConfidence * 100).roundToInt()}%)."
            )
        }

        // Case 3: Under-scrolled (too much overlap, not enough new content) -> apply proportional micro-correction
        if (correctionAttempt >= config.maxCorrectionSteps) {
            return if (observedDisplacementRatio >= 0.15f && matchConfidence >= 0.50f) {
                ScrollControlDecision(
                    type = ScrollDecisionType.ACCEPT_FRAME,
                    ticksToSend = 0,
                    updatedTicksPerScreenHeight = estimatedTicksPerScreenHeight,
                    reason = "Accepted conservative overlap (${(observedOverlap * 100).roundToInt()}%) at correction limit."
                )
            } else {
                ScrollControlDecision(
                    type = ScrollDecisionType.NEED_MANUAL_INTERVENTION,
                    ticksToSend = 0,
                    updatedTicksPerScreenHeight = estimatedTicksPerScreenHeight,
                    reason = "Unable to achieve verified overlap after $correctionAttempt steps."
                )
            }
        }

        val remainingDisplacement = (targetDisplacement - observedDisplacementRatio).coerceAtLeast(0.05f)
        val gainDamping = if (config.operatingMode == OperatingMode.HANDHELD) 0.70f else 0.85f
        val deltaTicks = (remainingDisplacement * estimatedTicksPerScreenHeight * gainDamping)
            .roundToInt()
            .coerceIn(1, 8)

        return ScrollControlDecision(
            type = ScrollDecisionType.SCROLL_MORE_DOWN,
            ticksToSend = deltaTicks,
            updatedTicksPerScreenHeight = estimatedTicksPerScreenHeight,
            reason = "Overlap ${(observedOverlap * 100).roundToInt()}% > target ${(targetOverlap * 100).roundToInt()}%; advancing $deltaTicks ticks."
        )
    }

    fun syncFromDeviceCalibration(calibration: ScrollCalibration, viewportHeightPx: Float) {
        if (calibration.pixelsPerWheelTick > 1f && viewportHeightPx > 50f) {
            estimatedTicksPerScreenHeight =
                (viewportHeightPx / calibration.pixelsPerWheelTick).coerceIn(2.0f, 120.0f)
        }
    }

    fun isWithinTolerance(observedOverlap: Float, targetOverlap: Float, tolerance: Float = 0.08f): Boolean =
        abs(observedOverlap - targetOverlap) <= tolerance
}
