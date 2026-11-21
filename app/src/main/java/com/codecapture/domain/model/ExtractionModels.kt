package com.codecapture.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class ExtractionStatus {
    NOT_STARTED,
    QUEUED,
    RUNNING,
    PAUSED_RESUMABLE,
    CANCELLED,
    COMPLETED,
    COMPLETED_WITH_WARNINGS,
    FAILED
}

@Serializable
enum class ExtractionStage {
    SESSION_INTAKE,
    IMAGE_NORMALIZATION,
    TEXT_REGION_DETECTION,
    OCR_RECOGNITION,
    PER_IMAGE_LAYOUT,
    CROSS_IMAGE_ALIGNMENT,
    DEDUPLICATION_SEQUENCING,
    LANGUAGE_AND_SYNTAX,
    CONFIDENCE_AND_GAP_ANALYSIS,
    REVIEW_PACKAGE,
    EXPORT_AND_PERSISTENCE
}

@Serializable
data class BoundingBoxNorm(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

@Serializable
data class AmbiguityFlag(
    val charIndex: Int,
    val recognizedChar: String,
    val candidateAlternatives: List<String>,
    val confidence: Float,
    val reason: String
)

@Serializable
data class OcrToken(
    val text: String,
    val confidence: Float,
    val box: BoundingBoxNorm
)

@Serializable
data class OcrVisualRow(
    val text: String,
    val confidence: Float,
    val box: BoundingBoxNorm,
    val tokens: List<OcrToken>,
    val detectedLineNumber: Int? = null,
    val estimatedIndentSpaces: Int = 0
)

@Serializable
data class PerImageOcrResult(
    val frameId: String,
    val imagePath: String,
    val sequenceIndex: Int,
    val engineId: String,
    val rows: List<OcrVisualRow>,
    val medianRowHeightNorm: Float,
    val medianCharWidthNorm: Float,
    val imageConfidence: Float
)

@Serializable
data class LineProvenance(
    val frameId: String,
    val imagePath: String,
    val sequenceIndex: Int,
    val boundingBox: BoundingBoxNorm,
    val rawOcrText: String
)

@Serializable
data class ReconstructedLine(
    val lineIndex: Int,
    val text: String,
    val logicalLineNumberHint: Int? = null,
    val confidence: Float,
    val isUserCorrected: Boolean = false,
    val isGapMarker: Boolean = false,
    val gapReason: String? = null,
    val wrappedRowCount: Int = 1,
    val ambiguities: List<AmbiguityFlag> = emptyList(),
    val provenance: List<LineProvenance> = emptyList()
)

@Serializable
data class DetectedLanguage(
    val languageId: String,
    val displayName: String,
    val suggestedExtension: String,
    val confidence: Float,
    val isUserOverridden: Boolean = false
)

@Serializable
enum class DiagnosticSeverity {
    INFO,
    WARNING,
    ERROR
}

@Serializable
data class SyntaxDiagnostic(
    val lineIndex: Int,
    val severity: DiagnosticSeverity,
    val message: String,
    val suggestedFix: String? = null
)

@Serializable
data class ExtractionReport(
    val sessionId: String,
    val engineId: String,
    val generatedAtEpochMs: Long,
    val detectedLanguage: DetectedLanguage,
    val overallConfidence: Float,
    val requiresReview: Boolean,
    val totalInputFrames: Int,
    val totalReconstructedLines: Int,
    val uncertainLineCount: Int,
    val flaggedGapCount: Int,
    val diagnostics: List<SyntaxDiagnostic>,
    val lines: List<ReconstructedLine>,
    val outputFileName: String,
    val limitationNotice: String =
        "Photograph-based OCR cannot guarantee exact recovery of source code; verify uncertain regions against original photographs."
)

@Serializable
data class ExtractionProgress(
    val sessionId: String,
    val status: ExtractionStatus,
    val currentStage: ExtractionStage,
    val processedFrames: Int,
    val totalFrames: Int,
    val message: String,
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)
