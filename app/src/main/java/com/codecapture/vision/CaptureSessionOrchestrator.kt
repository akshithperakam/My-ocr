package com.codecapture.vision

import android.graphics.Bitmap
import com.codecapture.bluetooth.HidScrollTransport
import com.codecapture.camera.CameraCaptureManager
import com.codecapture.camera.ImageQualityAnalyzer
import com.codecapture.domain.model.CalibrationProfile
import com.codecapture.domain.model.CaptureConfig
import com.codecapture.domain.model.CaptureErrorCode
import com.codecapture.domain.model.CaptureErrorEvent
import com.codecapture.domain.model.CapturePhase
import com.codecapture.domain.model.CapturedFrame
import com.codecapture.domain.model.HostDeviceProfile
import com.codecapture.domain.model.ImageQualityMetrics
import com.codecapture.domain.model.NormalizedRect
import com.codecapture.domain.model.OperatingMode
import com.codecapture.storage.SessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

data class CaptureHudState(
    val phase: CapturePhase = CapturePhase.IDLE,
    val activeSessionId: String? = null,
    val sessionName: String = "",
    val capturedCount: Int = 0,
    val lastAcceptedImagePath: String? = null,
    val lastEstimatedOverlapRatio: Float? = null,
    val lastConfidence: Float = 0f,
    val lastQuality: ImageQualityMetrics? = null,
    val scrollStatusText: String = "Ready",
    val operatingMode: OperatingMode = OperatingMode.FIXED,
    val calibration: CalibrationProfile? = null,
    val manualRegion: NormalizedRect? = null,
    val activeError: CaptureErrorEvent? = null,
    val isPaused: Boolean = false
)

/**
 * Coordinates the end-to-end capture workflow (Section 1.3 & Revision 1.1 Section 14.2):
 * CONNECT -> CALIBRATE -> CAPTURE -> ANALYSE -> SCROLL -> VERIFY -> REPEAT -> SESSION COMPLETE
 * Triggers automatic background extraction upon session completion without blocking capture.
 */
class CaptureSessionOrchestrator(
    private val hidTransport: HidScrollTransport,
    private val cameraManager: CameraCaptureManager,
    private val qualityAnalyzer: ImageQualityAnalyzer,
    private val editorDetector: ScreenEditorDetector,
    private val overlapMatcher: VisualOverlapMatcher,
    private val scrollController: AdaptiveScrollController,
    private val eofDetector: EndOfContentDetector,
    private val sessionRepository: SessionRepository,
    private val onTriggerAutoExtraction: (String) -> Unit
) {
    private val _hudState = MutableStateFlow(CaptureHudState())
    val hudState: StateFlow<CaptureHudState> = _hudState.asStateFlow()

    private var autoLoopJob: Job? = null
    private var previousFullBitmap: Bitmap? = null
    private var previousEditorCrop: Bitmap? = null

    fun setManualRegion(rect: NormalizedRect?) {
        _hudState.update { it.copy(manualRegion = rect) }
    }

    fun setOperatingMode(mode: OperatingMode) {
        _hudState.update { it.copy(operatingMode = mode) }
    }

    suspend fun calibrate(config: CaptureConfig, deviceProfile: HostDeviceProfile?): CalibrationProfile? {
        _hudState.update { it.copy(phase = CapturePhase.CALIBRATING, scrollStatusText = "Calibrating screen & focus...", activeError = null) }
        cameraManager.triggerCenterAutofocus()
        delay(300L)

        val preview = cameraManager.getLatestVerificationBitmap()
        if (preview == null) {
            emitError(CaptureErrorCode.CAMERA_FOCUS_LOST, "Camera preview frame unavailable.", recoverable = true)
            return null
        }

        val quality = qualityAnalyzer.analyze(preview)
        val detection = editorDetector.detectEditorRegion(preview, _hudState.value.manualRegion)
        if (detection.isFallbackFullFrame && _hudState.value.manualRegion == null) {
            emitError(
                CaptureErrorCode.SCREEN_NOT_DETECTED,
                "Could not isolate editor boundaries reliably; using central region. You can drag to select manually.",
                recoverable = true
            )
        }
        if (!quality.isAcceptable) {
            emitError(
                CaptureErrorCode.IMAGE_BLURRY,
                quality.warnings.firstOrNull() ?: "Low image quality during calibration.",
                recoverable = true
            )
        }

        val viewportPx = preview.height * detection.editorRect.height
        deviceProfile?.scrollCalibration?.let {
            scrollController.syncFromDeviceCalibration(it, viewportPx)
        }

        val profile = CalibrationProfile(
            editorRegion = detection.editorRect,
            estimatedLineHeightPx = (viewportPx / 32f).coerceAtLeast(12f),
            estimatedVisibleLines = 32,
            pixelsPerWheelTick = viewportPx / scrollController.estimatedTicksPerScreenHeight,
            cameraOrientationDegrees = 0,
            operatingMode = config.operatingMode,
            calibratedAtEpochMs = System.currentTimeMillis(),
            isManualRegion = _hudState.value.manualRegion != null
        )

        preview.recycle()
        _hudState.update {
            it.copy(
                phase = CapturePhase.IDLE,
                calibration = profile,
                lastQuality = quality,
                scrollStatusText = "Calibrated (${(detection.confidence * 100).toInt()}% editor lock)"
            )
        }
        return profile
    }

    suspend fun captureSingleManualFrame(
        sessionName: String,
        config: CaptureConfig,
        deviceProfile: HostDeviceProfile?
    ): CapturedFrame? {
        val sessionId = ensureActiveSession(sessionName, config, deviceProfile)
        return captureAndAcceptFrame(
            sessionId = sessionId,
            config = config,
            estimatedOverlapRatio = null,
            transitionConfidence = 1.0f,
            scrollTicksApplied = 0
        )
    }

    fun startAutomaticCapture(
        scope: CoroutineScope,
        sessionName: String,
        config: CaptureConfig,
        deviceProfile: HostDeviceProfile?
    ) {
        if (autoLoopJob?.isActive == true) return
        eofDetector.reset()

        autoLoopJob = scope.launch {
            if (!hidTransport.isConnected) {
                emitError(
                    CaptureErrorCode.BLUETOOTH_DISCONNECTED,
                    "Connect to a paired computer via Bluetooth HID before starting automatic capture.",
                    recoverable = true
                )
                return@launch
            }

            val calibration = _hudState.value.calibration ?: calibrate(config, deviceProfile) ?: return@launch
            val sessionId = ensureActiveSession(sessionName, config, deviceProfile)

            // Step 1: Capture initial reference frame
            val firstFrame = captureAndAcceptFrame(
                sessionId = sessionId,
                config = config,
                estimatedOverlapRatio = null,
                transitionConfidence = 1.0f,
                scrollTicksApplied = 0
            ) ?: return@launch

            var activeRegion = calibration.editorRegion
            var failedRetries = 0

            while (isActive) {
                while (_hudState.value.isPaused && isActive) {
                    delay(200L)
                }
                if (!isActive) break

                if (!hidTransport.isConnected) {
                    emitError(
                        CaptureErrorCode.BLUETOOTH_DISCONNECTED,
                        "Bluetooth HID disconnected. Capture paused — reconnect to resume.",
                        recoverable = true
                    )
                    _hudState.update { it.copy(isPaused = true, phase = CapturePhase.PAUSED) }
                    continue
                }

                // Step 2: Calculate initial adaptive scroll
                _hudState.update { it.copy(phase = CapturePhase.SCROLLING, scrollStatusText = "Calculating adaptive scroll...") }
                val initialTicks = scrollController.computeInitialScrollTicks(
                    config = config,
                    detectionConfidence = _hudState.value.lastConfidence.coerceAtLeast(0.75f),
                    deviceCalibration = deviceProfile?.scrollCalibration
                )
                val invert = deviceProfile?.scrollCalibration?.invertScrollDirection ?: false
                var totalTicksSent = initialTicks
                val sentOk = hidTransport.scrollVertical(initialTicks, invert)
                delay(config.movementSettleDelayMs)

                // Step 3: Closed-loop verification & micro-corrections
                var acceptedMatch: OverlapMatchResult? = null
                var reachedEof = false

                for (attempt in 1..config.maxCorrectionSteps) {
                    if (!isActive) break
                    _hudState.update { it.copy(phase = CapturePhase.VERIFYING, scrollStatusText = "Verifying overlap (step $attempt)...") }

                    val verifyBitmap = cameraManager.getLatestVerificationBitmap()
                    if (verifyBitmap == null) {
                        delay(200L)
                        continue
                    }

                    val prevFull = previousFullBitmap
                    val prevCrop = previousEditorCrop
                    if (prevFull == null || prevCrop == null) {
                        verifyBitmap.recycle()
                        break
                    }

                    val motion = if (config.operatingMode == OperatingMode.HANDHELD) {
                        editorDetector.estimateCameraMotion(prevFull, verifyBitmap, activeRegion)
                    } else {
                        CameraMotionEstimate(0f, 0f, false)
                    }

                    if (motion.significantCameraMotionDetected) {
                        val reDetected = editorDetector.detectEditorRegion(verifyBitmap, _hudState.value.manualRegion)
                        activeRegion = reDetected.editorRect
                        emitError(
                            CaptureErrorCode.CAMERA_MOVED_RECALIBRATE,
                            "Camera movement detected; recalibrated editor region.",
                            recoverable = true
                        )
                    }

                    val currCrop = editorDetector.cropEditorRegion(verifyBitmap, activeRegion)
                    val match = overlapMatcher.estimateVerticalOverlap(prevCrop, currCrop)
                    currCrop.recycle()
                    verifyBitmap.recycle()

                    val eofAssessment = eofDetector.evaluate(
                        bluetoothConnected = hidTransport.isConnected,
                        cameraMovedSignificantly = motion.significantCameraMotionDetected,
                        scrollCommandSentSuccessfully = sentOk,
                        overlapMatch = match,
                        newUnseenLinesCount = null
                    )

                    when (eofAssessment.verdict) {
                        EndOfContentVerdict.END_OF_DOCUMENT_REACHED -> {
                            reachedEof = true
                            _hudState.update {
                                it.copy(
                                    phase = CapturePhase.END_OF_CONTENT,
                                    scrollStatusText = eofAssessment.message
                                )
                            }
                            break
                        }
                        EndOfContentVerdict.BLUETOOTH_DISCONNECTED -> {
                            emitError(CaptureErrorCode.BLUETOOTH_DISCONNECTED, eofAssessment.message, recoverable = true)
                            _hudState.update { it.copy(isPaused = true, phase = CapturePhase.PAUSED) }
                            break
                        }
                        EndOfContentVerdict.EDITOR_UNRESPONSIVE_RETRY -> {
                            failedRetries++
                            if (failedRetries > config.maxFailedScrollRetries) {
                                emitError(
                                    CaptureErrorCode.SCROLL_NO_EFFECT,
                                    "Editor did not scroll after $failedRetries retries. Ensure editor window is focused.",
                                    recoverable = true
                                )
                                _hudState.update { it.copy(isPaused = true, phase = CapturePhase.PAUSED) }
                                break
                            }
                            hidTransport.scrollVertical(2, invert)
                            totalTicksSent += 2
                            delay(config.movementSettleDelayMs)
                            continue
                        }
                        else -> failedRetries = 0
                    }

                    val decision = scrollController.evaluateAndCorrect(
                        observedDisplacementRatio = match.displacementRatio,
                        matchConfidence = match.combinedConfidence,
                        ticksAlreadySent = totalTicksSent,
                        correctionAttempt = attempt,
                        config = config
                    )

                    _hudState.update {
                        it.copy(
                            lastEstimatedOverlapRatio = match.overlapRatio,
                            lastConfidence = match.combinedConfidence,
                            scrollStatusText = decision.reason
                        )
                    }

                    when (decision.type) {
                        ScrollDecisionType.ACCEPT_FRAME -> {
                            acceptedMatch = match
                            break
                        }
                        ScrollDecisionType.SCROLL_MORE_DOWN,
                        ScrollDecisionType.SCROLL_BACK_UP_OVERSHOOT -> {
                            hidTransport.scrollVertical(decision.ticksToSend, invert)
                            totalTicksSent += decision.ticksToSend
                            delay(config.movementSettleDelayMs)
                        }
                        ScrollDecisionType.NEED_MANUAL_INTERVENTION -> {
                            emitError(CaptureErrorCode.INSUFFICIENT_OVERLAP, decision.reason, recoverable = true)
                            _hudState.update { it.copy(isPaused = true, phase = CapturePhase.PAUSED) }
                            break
                        }
                    }
                }

                if (reachedEof) {
                    finishSession(config.autoExtractOnComplete)
                    break
                }

                if (_hudState.value.isPaused) continue

                val verified = acceptedMatch ?: continue
                delay(config.captureDelayMs)
                captureAndAcceptFrame(
                    sessionId = sessionId,
                    config = config,
                    estimatedOverlapRatio = verified.overlapRatio,
                    transitionConfidence = verified.combinedConfidence,
                    scrollTicksApplied = totalTicksSent
                )
            }
        }
    }

    fun pauseAutomaticCapture() {
        _hudState.update { it.copy(isPaused = true, phase = CapturePhase.PAUSED, scrollStatusText = "Paused by user") }
    }

    fun resumeAutomaticCapture() {
        _hudState.update { it.copy(isPaused = false, activeError = null, scrollStatusText = "Resuming capture...") }
    }

    fun overrideEndOfContent() {
        eofDetector.overrideEndOfContent()
        _hudState.update { it.copy(isPaused = false, activeError = null, scrollStatusText = "EOF overridden; continuing...") }
    }

    suspend fun stopAndFinishSession(autoExtract: Boolean) {
        autoLoopJob?.cancel()
        autoLoopJob = null
        finishSession(autoExtract)
    }

    private suspend fun finishSession(autoExtract: Boolean) {
        val sessionId = _hudState.value.activeSessionId
        if (sessionId != null) {
            sessionRepository.markSessionCompleted(sessionId)
            _hudState.update {
                it.copy(
                    phase = CapturePhase.COMPLETED,
                    isPaused = false,
                    scrollStatusText = if (autoExtract) {
                        "Session saved. Starting automatic code extraction..."
                    } else {
                        "Session saved."
                    }
                )
            }
            if (autoExtract && _hudState.value.capturedCount > 0) {
                onTriggerAutoExtraction(sessionId)
            }
        }
        clearBitmaps()
    }

    private suspend fun ensureActiveSession(
        sessionName: String,
        config: CaptureConfig,
        deviceProfile: HostDeviceProfile?
    ): String {
        val existing = _hudState.value.activeSessionId
        if (existing != null) return existing
        val session = sessionRepository.createSession(
            sessionName = sessionName.ifBlank { "CodeSession" },
            deviceProfileId = deviceProfile?.id,
            deviceDisplayName = deviceProfile?.displayName,
            operatingMode = config.operatingMode
        )
        _hudState.update {
            it.copy(
                activeSessionId = session.sessionId,
                sessionName = session.sessionName,
                capturedCount = 0
            )
        }
        return session.sessionId
    }

    private suspend fun captureAndAcceptFrame(
        sessionId: String,
        config: CaptureConfig,
        estimatedOverlapRatio: Float?,
        transitionConfidence: Float,
        scrollTicksApplied: Int
    ): CapturedFrame? {
        _hudState.update { it.copy(phase = CapturePhase.CAPTURING, scrollStatusText = "Capturing high-res frame...") }
        val nextIndex = _hudState.value.capturedCount + 1
        val outputFile: File = try {
            sessionRepository.allocateNextPhotoFile(sessionId, nextIndex)
        } catch (e: Exception) {
            emitError(CaptureErrorCode.STORAGE_UNAVAILABLE, "Storage unavailable: ${e.message}", recoverable = false)
            return null
        }

        val savedFile = try {
            cameraManager.captureHighResPhoto(outputFile)
        } catch (e: Exception) {
            cameraManager.triggerCenterAutofocus()
            emitError(CaptureErrorCode.CAMERA_FOCUS_LOST, "Capture failed: ${e.message}", recoverable = true)
            return null
        }

        _hudState.update { it.copy(phase = CapturePhase.ANALYSING, scrollStatusText = "Checking sharpness & glare...") }
        val bitmap = cameraManager.decodeOrientedBitmap(savedFile, maxDimension = 1600) ?: return null
        val quality = qualityAnalyzer.analyze(bitmap)
        if (!quality.isAcceptable) {
            cameraManager.triggerCenterAutofocus()
            emitError(
                if (quality.glareRatio > 0.085f) CaptureErrorCode.EXCESSIVE_GLARE else CaptureErrorCode.IMAGE_BLURRY,
                quality.warnings.firstOrNull() ?: "Frame rejected due to quality.",
                recoverable = true
            )
            // Keep file only if user manually captured; for auto-capture retry
            if (autoLoopJob?.isActive == true) {
                savedFile.delete()
                bitmap.recycle()
                return null
            }
        }

        val region = _hudState.value.manualRegion
            ?: _hudState.value.calibration?.editorRegion
            ?: editorDetector.detectEditorRegion(bitmap).editorRect

        val frame = CapturedFrame(
            frameId = UUID.randomUUID().toString(),
            sessionId = sessionId,
            sequenceIndex = nextIndex,
            imagePath = savedFile.absolutePath,
            capturedAtEpochMs = System.currentTimeMillis(),
            quality = quality,
            editorRegion = region,
            estimatedOverlapRatio = estimatedOverlapRatio,
            transitionConfidence = transitionConfidence,
            scrollTicksApplied = scrollTicksApplied,
            operatingMode = config.operatingMode
        )
        sessionRepository.recordAcceptedFrame(frame)

        // Update reference bitmaps for next closed-loop transition
        val newCrop = editorDetector.cropEditorRegion(bitmap, region)
        clearBitmaps()
        previousFullBitmap = bitmap
        previousEditorCrop = newCrop

        _hudState.update {
            it.copy(
                capturedCount = nextIndex,
                lastAcceptedImagePath = savedFile.absolutePath,
                lastQuality = quality,
                lastEstimatedOverlapRatio = estimatedOverlapRatio,
                lastConfidence = transitionConfidence,
                activeError = null,
                scrollStatusText = "Accepted image #${"%03d".format(nextIndex)}"
            )
        }
        return frame
    }

    private fun emitError(code: CaptureErrorCode, message: String, recoverable: Boolean) {
        _hudState.update {
            it.copy(
                phase = if (recoverable) it.phase else CapturePhase.ERROR,
                activeError = CaptureErrorEvent(code, message, recoverable),
                scrollStatusText = message
            )
        }
    }

    private fun clearBitmaps() {
        previousFullBitmap?.recycle()
        previousFullBitmap = null
        previousEditorCrop?.recycle()
        previousEditorCrop = null
    }
}
