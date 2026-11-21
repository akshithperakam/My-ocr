package com.codecapture

import com.codecapture.domain.model.ReconstructedLine
import com.codecapture.extraction.LanguageAndSyntaxValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageAndSyntaxValidatorTest {

    private val validator = LanguageAndSyntaxValidator()

    @Test
    fun detectsPythonAndSuggestsPyExtension() {
        val code = """
            from pathlib import Path

            def load_items(self, path):
                if path.exists():
                    return self.parse(path)
                elif path.name == "default":
                    return []
        """.trimIndent()
        val lang = validator.detectLanguage(code)
        assertEquals("python", lang.languageId)
        assertEquals(".py", lang.suggestedExtension)
    }

    @Test
    fun fallsBackToPlainTextOnAmbiguousOrLowConfidenceContent() {
        val lang = validator.detectLanguage("hello world\nsome random notes\n12345")
        assertEquals(".txt", lang.suggestedExtension)
    }

    @Test
    fun detectsUnmatchedBracesWithoutModifyingCode() {
        val lines = listOf(
            ReconstructedLine(0, "fun demo() {", confidence = 0.95f),
            ReconstructedLine(1, "    val x = listOf(1, 2, 3)", confidence = 0.95f),
            ReconstructedLine(2, "    println(x)", confidence = 0.95f)
        )
        val lang = validator.detectLanguage(lines.joinToString("\n") { it.text })
        val diagnostics = validator.validateSyntax(lines, lang)
        assertTrue(diagnostics.any { it.message.contains("Unclosed '{'") })
    }
}
