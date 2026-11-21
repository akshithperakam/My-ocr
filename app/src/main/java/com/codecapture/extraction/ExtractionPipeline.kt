package com.codecapture.extraction

import com.codecapture.camera.CameraCaptureManager
import com.codecapture.domain.model.ExtractionProgress
import com.codecapture.domain.model.ExtractionReport
import com.codecapture.domain.model.ExtractionStage
import com.codecapture.domain.model.ExtractionStatus
import com.codecapture.domain.model.PerImageOcrResult
import com.codecapture.storage.CodeCaptureDatabase
import com.codecapture.storage.ExtractionCheckpointEntity
import com.codecapture.storage.SessionRepository
import com.codecapture.vision.ScreenEditorDetector
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Executes the 11-stage Revision 1.1 Extraction Pipeline (Section 15.1):
 * 1. Session intake
 * 2. Image quality & normalization (crop editor region + orientation)
 * 3. Text region detection
 * 4. Offline OCR (with checkpoint persistence per frame for resume after interruption)
 * 5. Per-image layout reconstruction
 * 6. Cross-image alignment
 * 7. Deduplication & sequencing
 * 8. Language & syntax analysis
 * 9. Confidence & gap analysis
 * 10. Review package preparation
 * 11. Dual-output export & persistence (never deleting or modifying original photographs)
 */
class ExtractionPipeline(
    private val sessionRepository: SessionRepository,
    private val database: CodeCaptureDatabase,
    private val cameraManager: CameraCaptureManager,
    private val editorDetector: ScreenEditorDetector,
    private val ocrEngine: CodeOcrEngine,
    private val layoutReconstructor: CodeLayoutReconstructor,
    private val multiImageAssembler: MultiImageCodeAssembler,
    private val languageValidator: LanguageAndSyntaxValidator
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun run(
        sessionId: String,
        languageOverrideExtension: String? = null,
        forceRestart: Boolean = false,
        onProgress: suspend (ExtractionProgress) -> Unit = {}
    ): ExtractionReport {
        val frames = sessionRepository.getFramesForSession(sessionId)
        require(frames.isNotEmpty()) { "Session has no accepted photographs to extract." }

        sessionRepository.updateExtractionStatus(sessionId, ExtractionStatus.RUNNING)
        val checkpointDao = database.extractionCheckpointDao()

        // Stage 1: Session intake & resume check
        val existingCheckpoint = if (forceRestart) null else checkpointDao.getCheckpoint(sessionId)
        val cachedOcrResults: MutableMap<String, PerImageOcrResult> = existingCheckpoint
            ?.takeIf { it.engineId == ocrEngine.engineId && it.intermediateOcrJson.isNotBlank() }
            ?.let { cp ->
                runCatching {
                    json.decodeFromString<List<PerImageOcrResult>>(cp.intermediateOcrJson)
                        .associateBy { it.frameId }
                        .toMutableMap()
                }.getOrElse { mutableMapOf() }
            } ?: mutableMapOf()

        onProgress(
            ExtractionProgress(
                sessionId = sessionId,
                status = ExtractionStatus.RUNNING,
                currentStage = ExtractionStage.SESSION_INTAKE,
                processedFrames = cachedOcrResults.size,
                totalFrames = frames.size,
                message = "Loaded ${frames.size} accepted photographs."
            )
        )

        // Stages 2–4: Normalize, crop editor region, and run offline OCR with per-frame checkpointing
        val orderedOcrResults = mutableListOf<PerImageOcrResult>()
        frames.forEachIndexed { idx, frame ->
            val cached = cachedOcrResults[frame.frameId]
            val ocrResult = if (cached != null) {
                cached
            } else {
                onProgress(
                    ExtractionProgress(
                        sessionId = sessionId,
                        status = ExtractionStatus.RUNNING,
                        currentStage = ExtractionStage.OCR_RECOGNITION,
                        processedFrames = idx,
                        totalFrames = frames.size,
                        message = "Running offline OCR on photo ${idx + 1}/${frames.size}..."
                    )
                )
                val photoFile = File(frame.imagePath)
                val fullBmp = cameraManager.decodeOrientedBitmap(photoFile, maxDimension = 2048)
                val result = if (fullBmp != null) {
                    val crop = editorDetector.cropEditorRegion(fullBmp, frame.editorRegion)
                    fullBmp.recycle()
                    val recognized = ocrEngine.recognize(
                        frameId = frame.frameId,
                        imagePath = frame.imagePath,
                        sequenceIndex = frame.sequenceIndex,
                        editorBitmap = crop
                    )
                    crop.recycle()
                    recognized
                } else {
                    PerImageOcrResult(
                        frameId = frame.frameId,
                        imagePath = frame.imagePath,
                        sequenceIndex = frame.sequenceIndex,
                        engineId = ocrEngine.engineId,
                        rows = emptyList(),
                        medianRowHeightNorm = 0.025f,
                        medianCharWidthNorm = 0.01f,
                        imageConfidence = 0f
                    )
                }
                cachedOcrResults[frame.frameId] = result
                // Persist checkpoint so background extraction resumes cleanly if OS terminates process
                checkpointDao.upsertCheckpoint(
                    ExtractionCheckpointEntity(
                        sessionId = sessionId,
                        status = ExtractionStatus.RUNNING.name,
                        currentStage = ExtractionStage.OCR_RECOGNITION.name,
                        processedFrameCount = idx + 1,
                        totalFrameCount = frames.size,
                        intermediateOcrJson = json.encodeToString(cachedOcrResults.values.toList()),
                        engineId = ocrEngine.engineId,
                        languageOverride = languageOverrideExtension,
                        errorMessage = null,
                        updatedAtEpochMs = System.currentTimeMillis()
                    )
                )
                result
            }
            orderedOcrResults += ocrResult
        }

        // Stage 5: Per-image layout reconstruction
        onProgress(
            ExtractionProgress(
                sessionId = sessionId,
                status = ExtractionStatus.RUNNING,
                currentStage = ExtractionStage.PER_IMAGE_LAYOUT,
                processedFrames = frames.size,
                totalFrames = frames.size,
                message = "Reconstructing indentation, blank lines, and wrapped rows..."
            )
        )
        val perFrameLogical = orderedOcrResults.mapIndexed { idx, ocr ->
            FrameLogicalLines(
                frameId = ocr.frameId,
                imagePath = ocr.imagePath,
                sequenceIndex = ocr.sequenceIndex,
                estimatedVisualOverlapRatio = frames[idx].estimatedOverlapRatio,
                lines = layoutReconstructor.reconstructImageLines(ocr)
            )
        }

        // Stages 6–7: Cross-image hybrid alignment & cautious deduplication
        onProgress(
            ExtractionProgress(
                sessionId = sessionId,
                status = ExtractionStatus.RUNNING,
                currentStage = ExtractionStage.DEDUPLICATION_SEQUENCING,
                processedFrames = frames.size,
                totalFrames = frames.size,
                message = "Aligning overlapping regions and deduplicating..."
            )
        )
        val assembly = multiImageAssembler.assemble(perFrameLogical)
        val reconstructedText = assembly.lines.joinToString("\n") { it.text }

        // Stage 8: Language identification & syntax validation
        onProgress(
            ExtractionProgress(
                sessionId = sessionId,
                status = ExtractionStatus.RUNNING,
                currentStage = ExtractionStage.LANGUAGE_AND_SYNTAX,
                processedFrames = frames.size,
                totalFrames = frames.size,
                message = "Detecting programming language and running syntax checks..."
            )
        )
        val detectedLang = languageValidator.detectLanguage(reconstructedText, languageOverrideExtension)
        val diagnostics = languageValidator.validateSyntax(assembly.lines, detectedLang)

        // Stages 9–10: Confidence & gap analysis
        val nonEmptyLines = assembly.lines.filter { it.text.isNotBlank() && !it.isGapMarker }
        val overallConfidence = if (nonEmptyLines.isNotEmpty()) {
            nonEmptyLines.map { it.confidence }.average().toFloat()
        } else {
            0f
        }
        val uncertainCount = assembly.lines.count {
            it.isGapMarker || it.confidence < 0.78f || it.ambiguities.isNotEmpty()
        }
        val requiresReview = uncertainCount > 0 || assembly.flaggedGapCount > 0 || diagnostics.isNotEmpty() || overallConfidence < 0.85f
        val outputFileName = "source_001${detectedLang.suggestedExtension}"

        val report = ExtractionReport(
            sessionId = sessionId,
            engineId = ocrEngine.engineId,
            generatedAtEpochMs = System.currentTimeMillis(),
            detectedLanguage = detectedLang,
            overallConfidence = overallConfidence,
            requiresReview = requiresReview,
            totalInputFrames = frames.size,
            totalReconstructedLines = assembly.lines.size,
            uncertainLineCount = uncertainCount,
            flaggedGapCount = assembly.flaggedGapCount,
            diagnostics = diagnostics,
            lines = assembly.lines,
            outputFileName = outputFileName
        )

        // Stage 11: Dual-output local persistence
        val finalStatus = if (requiresReview) {
            ExtractionStatus.COMPLETED_WITH_WARNINGS
        } else {
            ExtractionStatus.COMPLETED
        }
        sessionRepository.saveExtractionOutput(
            sessionId = sessionId,
            sourceCodeText = reconstructedText,
            report = report,
            status = finalStatus
        )
        checkpointDao.clearCheckpoint(sessionId)

        onProgress(
            ExtractionProgress(
                sessionId = sessionId,
                status = finalStatus,
                currentStage = ExtractionStage.EXPORT_AND_PERSISTENCE,
                processedFrames = frames.size,
                totalFrames = frames.size,
                message = "Saved $outputFileName (${assembly.lines.size} lines, ${(overallConfidence * 100).toInt()}% confidence)."
            )
        )
        return report
    }
}
