package com.codecapture

import com.codecapture.domain.model.CaptureConfig
import com.codecapture.domain.model.OperatingMode
import com.codecapture.vision.AdaptiveScrollController
import com.codecapture.vision.ScrollDecisionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveScrollControllerTest {

    @Test
    fun acceptsFrameWhenOverlapIsWithinTargetBand() {
        val controller = AdaptiveScrollController(initialTicksPerScreenHeight = 12f)
        val config = CaptureConfig(operatingMode = OperatingMode.FIXED, targetOverlapRatio = 0.20f)

        // 0.78 displacement -> 22% overlap (right inside 14%..32% band)
        val decision = controller.evaluateAndCorrect(
            observedDisplacementRatio = 0.78f,
            matchConfidence = 0.88f,
            ticksAlreadySent = 9,
            correctionAttempt = 1,
            config = config
        )
        assertEquals(ScrollDecisionType.ACCEPT_FRAME, decision.type)
        assertEquals(0, decision.ticksToSend)
    }

    @Test
    fun scrollsBackUpWhenOvershootDetected() {
        val controller = AdaptiveScrollController(initialTicksPerScreenHeight = 12f)
        val config = CaptureConfig(operatingMode = OperatingMode.FIXED, targetOverlapRatio = 0.22f)

        // 0.94 displacement -> only 6% overlap (unsafe overshoot!)
        val decision = controller.evaluateAndCorrect(
            observedDisplacementRatio = 0.94f,
            matchConfidence = 0.70f,
            ticksAlreadySent = 11,
            correctionAttempt = 1,
            config = config
        )
        assertEquals(ScrollDecisionType.SCROLL_BACK_UP_OVERSHOOT, decision.type)
        assertTrue(decision.ticksToSend < 0)
    }

    @Test
    fun advancesIncrementallyWhenUnderScrolled() {
        val controller = AdaptiveScrollController(initialTicksPerScreenHeight = 12f)
        val config = CaptureConfig(operatingMode = OperatingMode.FIXED, targetOverlapRatio = 0.20f)

        // Only moved 35% -> 65% overlap (needs more scrolling)
        val decision = controller.evaluateAndCorrect(
            observedDisplacementRatio = 0.35f,
            matchConfidence = 0.90f,
            ticksAlreadySent = 4,
            correctionAttempt = 1,
            config = config
        )
        assertEquals(ScrollDecisionType.SCROLL_MORE_DOWN, decision.type)
        assertTrue(decision.ticksToSend > 0)
    }

    @Test
    fun handheldModeEnforcesHigherOverlapAndConservativeInitialBurst() {
        val controller = AdaptiveScrollController(initialTicksPerScreenHeight = 15f)
        val fixedConfig = CaptureConfig(operatingMode = OperatingMode.FIXED, targetOverlapRatio = 0.20f)
        val handheldConfig = CaptureConfig(operatingMode = OperatingMode.HANDHELD, targetOverlapRatio = 0.20f)

        val fixedTicks = controller.computeInitialScrollTicks(fixedConfig, 0.9f)
        val handheldTicks = controller.computeInitialScrollTicks(handheldConfig, 0.9f)
        assertTrue(handheldTicks < fixedTicks)
        assertTrue(controller.effectiveTargetOverlap(handheldConfig, 0.9f) >= 0.28f)
    }
}
