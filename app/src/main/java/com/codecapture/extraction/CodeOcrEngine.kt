package com.codecapture.extraction

import android.graphics.Bitmap
import com.codecapture.domain.model.BoundingBoxNorm
import com.codecapture.domain.model.OcrToken
import com.codecapture.domain.model.OcrVisualRow
import com.codecapture.domain.model.PerImageOcrResult
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

/**
 * Pluggable on-device OCR abstraction (Section 15.3, AC-20, AC-21).
 * Allows re-running extraction with alternative offline engines (e.g., ML Kit vs PaddleOCR ONNX)
 * without re-capturing photographs.
 */
interface CodeOcrEngine {
    val engineId: String
    suspend fun recognize(
        frameId: String,
        imagePath: String,
        sequenceIndex: Int,
        editorBitmap: Bitmap
    ): PerImageOcrResult
    fun close() {}
}

/**
 * Primary offline OCR engine using bundled ML Kit Text Recognition v2 (Latin).
 * Runs 100% locally on Pixel 8a with zero network calls and preserves per-element bounding boxes
 * and confidence scores for indentation and ambiguity analysis.
 */
class MlKitCodeOcrEngine : CodeOcrEngine {
    override val engineId: String = "mlkit-latin-bundled-v2"

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    override suspend fun recognize(
        frameId: String,
        imagePath: String,
        sequenceIndex: Int,
        editorBitmap: Bitmap
    ): PerImageOcrResult {
        val width = editorBitmap.width.coerceAtLeast(1).toFloat()
        val height = editorBitmap.height.coerceAtLeast(1).toFloat()

        val inputImage = InputImage.fromBitmap(editorBitmap, 0)
        val visionText = recognizer.process(inputImage).await()

        val rawRows = mutableListOf<OcrVisualRow>()
        val rowHeights = mutableListOf<Float>()
        val charWidths = mutableListOf<Float>()

        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val rect = line.boundingBox ?: continue
                val normBox = BoundingBoxNorm(
                    left = (rect.left / width).coerceIn(0f, 1f),
                    top = (rect.top / height).coerceIn(0f, 1f),
                    right = (rect.right / width).coerceIn(0f, 1f),
                    bottom = (rect.bottom / height).coerceIn(0f, 1f)
                )
                rowHeights += (normBox.bottom - normBox.top).coerceAtLeast(0.005f)

                val tokens = line.elements.mapNotNull { element ->
                    val eRect = element.boundingBox ?: return@mapNotNull null
                    val eBox = BoundingBoxNorm(
                        left = (eRect.left / width).coerceIn(0f, 1f),
                        top = (eRect.top / height).coerceIn(0f, 1f),
                        right = (eRect.right / width).coerceIn(0f, 1f),
                        bottom = (eRect.bottom / height).coerceIn(0f, 1f)
                    )
                    if (element.text.isNotEmpty()) {
                        charWidths += ((eBox.right - eBox.left) / element.text.length).coerceAtLeast(0.002f)
                    }
                    OcrToken(
                        text = element.text,
                        confidence = element.confidence.coerceIn(0f, 1f),
                        box = eBox
                    )
                }

                val lineConf = if (tokens.isNotEmpty()) {
                    tokens.map { it.confidence }.average().toFloat()
                } else {
                    line.confidence.coerceIn(0f, 1f)
                }

                rawRows += OcrVisualRow(
                    text = line.text,
                    confidence = lineConf,
                    box = normBox,
                    tokens = tokens
                )
            }
        }

        val sortedRows = rawRows.sortedWith(compareBy<OcrVisualRow> { it.box.top }.thenBy { it.box.left })
        val medianHeight = medianOf(rowHeights, default = 0.025f)
        val medianCharWidth = medianOf(charWidths, default = 0.010f)
        val imageConf = if (sortedRows.isNotEmpty()) {
            sortedRows.map { it.confidence }.average().toFloat()
        } else {
            0f
        }

        return PerImageOcrResult(
            frameId = frameId,
            imagePath = imagePath,
            sequenceIndex = sequenceIndex,
            engineId = engineId,
            rows = sortedRows,
            medianRowHeightNorm = medianHeight,
            medianCharWidthNorm = medianCharWidth,
            imageConfidence = imageConf
        )
    }

    override fun close() {
        runCatching { recognizer.close() }
    }

    private fun medianOf(values: List<Float>, default: Float): Float {
        if (values.isEmpty()) return default
        val s = values.sorted()
        return s[s.size / 2]
    }
}
