package com.codecapture.extraction

import com.codecapture.domain.model.AmbiguityFlag
import com.codecapture.domain.model.BoundingBoxNorm
import com.codecapture.domain.model.OcrVisualRow
import com.codecapture.domain.model.PerImageOcrResult
import kotlin.math.abs
import kotlin.math.roundToInt

data class LogicalCodeLine(
    val text: String,
    val lineNumberHint: Int?,
    val confidence: Float,
    val boundingBox: BoundingBoxNorm,
    val rawOcrText: String,
    val wrappedRowCount: Int,
    val ambiguities: List<AmbiguityFlag>
)

/**
 * Reconstructs per-image code layout (FR-05, FR-18, Section 15.1 Stage 5):
 * - Groups horizontally split OCR fragments on the same visual baseline.
 * - Detects optional left-gutter line numbers without assuming they exist.
 * - Preserves relative indentation (spaces) and blank lines.
 * - Distinguishes wrapped visual rows from logical source-code lines.
 * - Flags common programming ambiguity pairs (0/O, 1/l/I, {/(, }/), ;/:, =/-, ==/=, &&/&).
 */
class CodeLayoutReconstructor {

    private val gutterNumberRegex = Regex("^\\d{1,5}$")

    fun reconstructImageLines(ocrResult: PerImageOcrResult): List<LogicalCodeLine> {
        if (ocrResult.rows.isEmpty()) return emptyList()

        val rowH = ocrResult.medianRowHeightNorm.coerceAtLeast(0.01f)
        val charW = ocrResult.medianCharWidthNorm.coerceAtLeast(0.004f)

        // Step 1: Merge OCR rows that share the same visual Y baseline (e.g., gutter number + code text)
        val mergedVisualRows = mergeSameBaselineRows(ocrResult.rows, rowH)

        // Step 2: Detect whether a consistent left-gutter line number column exists
        val hasGutter = detectLineNumberGutter(mergedVisualRows)

        // Step 3: Extract optional line numbers and find left margin of code body
        val parsed = mergedVisualRows.map { row ->
            var lineNumber: Int? = null
            var codeTokens = row.tokens
            if (hasGutter && codeTokens.isNotEmpty() &&
                codeTokens.first().box.left < 0.14f &&
                gutterNumberRegex.matches(codeTokens.first().text.trim())
            ) {
                lineNumber = codeTokens.first().text.trim().toIntOrNull()
                codeTokens = codeTokens.drop(1)
            }
            val codeLeft = codeTokens.firstOrNull()?.box?.left ?: row.box.left
            val codeText = if (codeTokens.isNotEmpty()) {
                reconstructSpacingFromTokens(codeTokens, charW)
            } else {
                row.text
            }
            Triple(lineNumber, codeLeft, row.copy(text = codeText, tokens = codeTokens))
        }

        val baseLeftMargin = parsed.mapNotNull { (_, left, row) ->
            if (row.text.isNotBlank()) left else null
        }.minOrNull() ?: 0f

        // Step 4: Build logical lines, preserving blank lines and joining wrapped visual rows
        val output = mutableListOf<LogicalCodeLine>()
        var prevBottom = -1f
        var prevLineNumber: Int? = null

        for ((lineNumber, codeLeft, row) in parsed) {
            // Insert blank line(s) when vertical gap exceeds 1.75x typical row pitch
            if (prevBottom > 0f) {
                val verticalGap = row.box.top - prevBottom
                val blankCount = ((verticalGap / (rowH * 1.35f)) - 0.85f).toInt().coerceIn(0, 3)
                repeat(blankCount) {
                    output += LogicalCodeLine(
                        text = "",
                        lineNumberHint = null,
                        confidence = 0.90f,
                        boundingBox = BoundingBoxNorm(baseLeftMargin, prevBottom, baseLeftMargin + 0.01f, row.box.top),
                        rawOcrText = "",
                        wrappedRowCount = 1,
                        ambiguities = emptyList()
                    )
                }
            }

            val indentSpaces = (((codeLeft - baseLeftMargin) / charW).roundToInt()).coerceIn(0, 48)
            // Snap indentation to nearest 2-space grid for cleaner code fidelity when close
            val normalizedIndent = if (indentSpaces >= 2) ((indentSpaces + 1) / 2) * 2 else indentSpaces
            val indentedText = " ".repeat(normalizedIndent) + row.text.trimEnd()

            val isWrappedContinuation = hasGutter &&
                lineNumber == null &&
                prevLineNumber != null &&
                output.isNotEmpty() &&
                output.last().text.isNotBlank() &&
                (prevBottom > 0f && (row.box.top - prevBottom) < rowH * 0.65f)

            if (isWrappedContinuation) {
                val prev = output.removeAt(output.lastIndex)
                val joinedText = prev.text + " " + row.text.trim()
                output += prev.copy(
                    text = joinedText,
                    confidence = minOf(prev.confidence, row.confidence),
                    boundingBox = BoundingBoxNorm(
                        left = minOf(prev.boundingBox.left, row.box.left),
                        top = prev.boundingBox.top,
                        right = maxOf(prev.boundingBox.right, row.box.right),
                        bottom = row.box.bottom
                    ),
                    rawOcrText = prev.rawOcrText + "\n" + row.text,
                    wrappedRowCount = prev.wrappedRowCount + 1,
                    ambiguities = detectAmbiguities(joinedText, minOf(prev.confidence, row.confidence))
                )
            } else {
                output += LogicalCodeLine(
                    text = indentedText,
                    lineNumberHint = lineNumber,
                    confidence = row.confidence,
                    boundingBox = row.box,
                    rawOcrText = row.text,
                    wrappedRowCount = 1,
                    ambiguities = detectAmbiguities(indentedText, row.confidence)
                )
                if (lineNumber != null) {
                    prevLineNumber = lineNumber
                }
            }
            prevBottom = row.box.bottom
        }

        return output
    }

    private fun mergeSameBaselineRows(rows: List<OcrVisualRow>, rowH: Float): List<OcrVisualRow> {
        val grouped = mutableListOf<MutableList<OcrVisualRow>>()
        for (row in rows) {
            val centerY = (row.box.top + row.box.bottom) * 0.5f
            val match = grouped.lastOrNull { group ->
                val refY = group.map { (it.box.top + it.box.bottom) * 0.5f }.average().toFloat()
                abs(centerY - refY) <= rowH * 0.48f
            }
            if (match != null) {
                match += row
            } else {
                grouped += mutableListOf(row)
            }
        }

        return grouped.map { group ->
            val ordered = group.sortedBy { it.box.left }
            val allTokens = ordered.flatMap { it.tokens }.sortedBy { it.box.left }
            val mergedBox = BoundingBoxNorm(
                left = ordered.minOf { it.box.left },
                top = ordered.minOf { it.box.top },
                right = ordered.maxOf { it.box.right },
                bottom = ordered.maxOf { it.box.bottom }
            )
            val conf = ordered.map { it.confidence }.average().toFloat()
            val text = ordered.joinToString(" ") { it.text }
            OcrVisualRow(text = text, confidence = conf, box = mergedBox, tokens = allTokens)
        }
    }

    private fun detectLineNumberGutter(rows: List<OcrVisualRow>): Boolean {
        val leftNumbers = rows.mapNotNull { row ->
            val first = row.tokens.firstOrNull() ?: return@mapNotNull null
            if (first.box.left < 0.14f && gutterNumberRegex.matches(first.text.trim())) {
                first.text.trim().toIntOrNull()
            } else {
                null
            }
        }
        if (leftNumbers.size < 3) return false
        var monotonicIncreases = 0
        for (i in 1 until leftNumbers.size) {
            if (leftNumbers[i] in (leftNumbers[i - 1] + 1)..(leftNumbers[i - 1] + 4)) {
                monotonicIncreases++
            }
        }
        return monotonicIncreases >= (leftNumbers.size - 1) * 0.6f
    }

    private fun reconstructSpacingFromTokens(
        tokens: List<com.codecapture.domain.model.OcrToken>,
        charW: Float
    ): String {
        if (tokens.isEmpty()) return ""
        val sb = StringBuilder(tokens.first().text)
        for (i in 1 until tokens.size) {
            val prev = tokens[i - 1]
            val curr = tokens[i]
            val gap = (curr.box.left - prev.box.right).coerceAtLeast(0f)
            val spaces = (gap / charW).roundToInt().coerceIn(0, 8)
            sb.append(" ".repeat(if (spaces == 0 && gap > charW * 0.35f) 1 else spaces))
            sb.append(curr.text)
        }
        return sb.toString()
    }

    /**
     * Identifies common programming ambiguity characters (FR-18) without silently overwriting them.
     */
    fun detectAmbiguities(lineText: String, lineConfidence: Float): List<AmbiguityFlag> {
        if (lineConfidence >= 0.93f) return emptyList()
        val flags = mutableListOf<AmbiguityFlag>()
        val pairs = mapOf(
            '0' to listOf("O"),
            'O' to listOf("0"),
            '1' to listOf("l", "I"),
            'l' to listOf("1", "I"),
            'I' to listOf("1", "l"),
            '{' to listOf("("),
            '(' to listOf("{"),
            '}' to listOf(")"),
            ')' to listOf("}"),
            ';' to listOf(":"),
            ':' to listOf(";"),
            '=' to listOf("-", "=="),
            '&' to listOf("&&")
        )
        lineText.forEachIndexed { idx, ch ->
            val alts = pairs[ch] ?: return@forEachIndexed
            // Flag when confidence is moderate/low and character sits in an ambiguous context
            if (lineConfidence < 0.82f) {
                flags += AmbiguityFlag(
                    charIndex = idx,
                    recognizedChar = ch.toString(),
                    candidateAlternatives = alts,
                    confidence = lineConfidence,
                    reason = "Ambiguous programming symbol under low/moderate OCR confidence"
                )
            }
        }
        return flags.take(8)
    }
}
