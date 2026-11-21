package com.codecapture

import com.codecapture.domain.model.BoundingBoxNorm
import com.codecapture.extraction.FrameLogicalLines
import com.codecapture.extraction.LogicalCodeLine
import com.codecapture.extraction.MultiImageCodeAssembler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiImageCodeAssemblerTest {

    private fun line(text: String, num: Int? = null, conf: Float = 0.95f) = LogicalCodeLine(
        text = text,
        lineNumberHint = num,
        confidence = conf,
        boundingBox = BoundingBoxNorm(0f, 0f, 1f, 0.05f),
        rawOcrText = text,
        wrappedRowCount = 1,
        ambiguities = emptyList()
    )

    @Test
    fun deduplicatesVerifiedMultiLineOverlapWithoutLineNumbers() {
        val assembler = MultiImageCodeAssembler()
        val f1 = FrameLogicalLines(
            frameId = "f1",
            imagePath = "image_001.jpg",
            sequenceIndex = 1,
            estimatedVisualOverlapRatio = 0.40f,
            lines = listOf(
                line("fun calculateTotal(items: List<Int>): Int {"),
                line("    var sum = 0"),
                line("    for (item in items) {"),
                line("        sum += item")
            )
        )
        val f2 = FrameLogicalLines(
            frameId = "f2",
            imagePath = "image_002.jpg",
            sequenceIndex = 2,
            estimatedVisualOverlapRatio = 0.40f,
            lines = listOf(
                line("    for (item in items) {"),
                line("        sum += item"),
                line("    }"),
                line("    return sum"),
                line("}")
            )
        )

        val result = assembler.assemble(listOf(f1, f2))
        assertEquals(0, result.flaggedGapCount)
        assertEquals(1, result.verifiedOverlapTransitions)
        assertEquals(7, result.lines.size)
        assertEquals("fun calculateTotal(items: List<Int>): Int {", result.lines.first().text)
        assertEquals("}", result.lines.last().text)
        // Overlapped line carries provenance from both photos
        assertEquals(2, result.lines[2].provenance.size)
    }

    @Test
    fun flagsGapWhenNoVerifiedOverlapExistsInsteadOfSilentlyGuessing() {
        val assembler = MultiImageCodeAssembler()
        val f1 = FrameLogicalLines(
            frameId = "f1",
            imagePath = "image_001.jpg",
            sequenceIndex = 1,
            estimatedVisualOverlapRatio = 0.0f,
            lines = listOf(line("val alpha = 1"), line("val beta = 2"))
        )
        val f2 = FrameLogicalLines(
            frameId = "f2",
            imagePath = "image_002.jpg",
            sequenceIndex = 2,
            estimatedVisualOverlapRatio = 0.0f,
            lines = listOf(line("val omega = 99"), line("println(omega)"))
        )

        val result = assembler.assemble(listOf(f1, f2))
        assertEquals(1, result.flaggedGapCount)
        assertTrue(result.lines.any { it.isGapMarker })
    }

    @Test
    fun usesLineNumbersAsAnchorWhenPresent() {
        val assembler = MultiImageCodeAssembler()
        val f1 = FrameLogicalLines(
            frameId = "f1",
            imagePath = "image_001.jpg",
            sequenceIndex = 1,
            estimatedVisualOverlapRatio = 0.33f,
            lines = listOf(line("val a = 1", 10), line("val b = 2", 11), line("val c = 3", 12))
        )
        val f2 = FrameLogicalLines(
            frameId = "f2",
            imagePath = "image_002.jpg",
            sequenceIndex = 2,
            estimatedVisualOverlapRatio = 0.33f,
            lines = listOf(line("val c = 3", 12), line("val d = 4", 13), line("val e = 5", 14))
        )

        val result = assembler.assemble(listOf(f1, f2))
        assertEquals(0, result.flaggedGapCount)
        assertEquals(5, result.lines.size)
    }
}
