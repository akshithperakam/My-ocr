package com.codecapture.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class OperatingMode {
    FIXED,
    HANDHELD
}

@Serializable
enum class CodeDensityPreset(val defaultTargetOverlap: Float, val minOverlap: Float, val maxOverlap: Float) {
    NORMAL(defaultTargetOverlap = 0.20f, minOverlap = 0.15f, maxOverlap = 0.25f),
    DENSE(defaultTargetOverlap = 0.30f, minOverlap = 0.25f, maxOverlap = 0.35f),
    CONSERVATIVE(defaultTargetOverlap = 0.38f, minOverlap = 0.30f, maxOverlap = 0.45f)
}

@Serializable
data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)

    fun isValid(): Boolean = left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f &&
        right > left + 0.05f && bottom > top + 0.05f

    companion object {
        val FULL_FRAME = NormalizedRect(0f, 0f, 1f, 1f)
    }
}

@Serializable
data class ImageQualityMetrics(
    val sharpnessScore: Float,        // Normalized Laplacian variance (0..100+)
    val glareRatio: Float,            // Fraction of near-saturated pixels (0..1)
    val meanLuminance: Float,         // 0..255
    val widthPx: Int,
    val heightPx: Int,
    val isAcceptable: Boolean,
    val warnings: List<String> = emptyList()
)

@Serializable
data class CalibrationProfile(
    val editorRegion: NormalizedRect,
    val estimatedLineHeightPx: Float,
    val estimatedVisibleLines: Int,
    val pixelsPerWheelTick: Float,
    val cameraOrientationDegrees: Int,
    val operatingMode: OperatingMode,
    val calibratedAtEpochMs: Long,
    val isManualRegion: Boolean = false
)

@Serializable
data class CapturedFrame(
    val frameId: String,
    val sessionId: String,
    val sequenceIndex: Int,
    val imagePath: String,
    val capturedAtEpochMs: Long,
    val quality: ImageQualityMetrics,
    val editorRegion: NormalizedRect,
    val estimatedOverlapRatio: Float?,
    val transitionConfidence: Float,
    val scrollTicksApplied: Int,
    val operatingMode: OperatingMode
)

@Serializable
data class CaptureConfig(
    val operatingMode: OperatingMode = OperatingMode.FIXED,
    val densityPreset: CodeDensityPreset = CodeDensityPreset.NORMAL,
    val targetOverlapRatio: Float = 0.20f,
    val captureDelayMs: Long = 350L,
    val movementSettleDelayMs: Long = 450L,
    val scrollSensitivity: Float = 1.0f,
    val jpegQuality: Int = 94,
    val autoExtractOnComplete: Boolean = true,
    val maxCorrectionSteps: Int = 6,
    val maxFailedScrollRetries: Int = 3
)

@Serializable
enum class CapturePhase {
    IDLE,
    CONNECTING,
    CALIBRATING,
    CAPTURING,
    ANALYSING,
    SCROLLING,
    VERIFYING,
    PAUSED,
    END_OF_CONTENT,
    COMPLETED,
    ERROR
}

@Serializable
data class CaptureSessionMetadata(
    val sessionId: String,
    val sessionName: String,
    val dateFolder: String,
    val createdAtEpochMs: Long,
    val completedAtEpochMs: Long? = null,
    val deviceProfileId: String? = null,
    val deviceDisplayName: String? = null,
    val operatingMode: OperatingMode = OperatingMode.FIXED,
    val frameCount: Int = 0,
    val extractionStatus: ExtractionStatus = ExtractionStatus.NOT_STARTED,
    val notes: String = ""
)

data class CaptureErrorEvent(
    val code: CaptureErrorCode,
    val message: String,
    val recoverable: Boolean,
    val timestampMs: Long = System.currentTimeMillis()
)

enum class CaptureErrorCode {
    BLUETOOTH_DISCONNECTED,
    CAMERA_FOCUS_LOST,
    EXCESSIVE_GLARE,
    CAMERA_MOVED_RECALIBRATE,
    SCREEN_NOT_DETECTED,
    LOW_OCR_CONFIDENCE,
    SCROLL_NO_EFFECT,
    INSUFFICIENT_OVERLAP,
    IMAGE_BLURRY,
    STORAGE_UNAVAILABLE,
    DUPLICATE_FRAME_DETECTED
}
