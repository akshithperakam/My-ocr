package com.codecapture.extraction

import com.codecapture.domain.model.DetectedLanguage
import com.codecapture.domain.model.DiagnosticSeverity
import com.codecapture.domain.model.ReconstructedLine
import com.codecapture.domain.model.SyntaxDiagnostic

/**
 * Programming language identifier & non-destructive syntax validator (FR-19, FR-20, Section 15.4).
 * Uses token/keyword heuristics for language suggestion (falling back safely to .txt on low confidence)
 * and stack-based delimiter/quote/brace balance checking to flag likely OCR transcription errors
 * WITHOUT ever silently rewriting the user's code.
 */
class LanguageAndSyntaxValidator {

    private data class LanguageSignature(
        val id: String,
        val displayName: String,
        val extension: String,
        val patterns: List<Pair<Regex, Float>>
    )

    private val signatures = listOf(
        LanguageSignature(
            id = "kotlin",
            displayName = "Kotlin",
            extension = ".kt",
            patterns = listOf(
                Regex("\\bfun\\s+[A-Za-z0-9_<>]+\\s*\\(") to 3.0f,
                Regex("\\b(val|var)\\s+[A-Za-z_][A-Za-z0-9_]*\\s*(:|=)") to 2.0f,
                Regex("\\bdata\\s+class\\b") to 3.5f,
                Regex("\\bcompanion\\s+object\\b") to 3.0f,
                Regex("\\bsuspend\\s+fun\\b") to 3.0f
            )
        ),
        LanguageSignature(
            id = "java",
            displayName = "Java",
            extension = ".java",
            patterns = listOf(
                Regex("\\bpublic\\s+(static\\s+)?(class|interface|void|int|String)\\b") to 3.0f,
                Regex("\\bSystem\\.out\\.println\\b") to 3.5f,
                Regex("\\bimport\\s+java\\.") to 3.0f,
                Regex("@Override\\b") to 2.0f
            )
        ),
        LanguageSignature(
            id = "python",
            displayName = "Python",
            extension = ".py",
            patterns = listOf(
                Regex("^\\s*def\\s+[A-Za-z_][A-Za-z0-9_]*\\s*\\(.*\\)\\s*:", RegexOption.MULTILINE) to 3.5f,
                Regex("^\\s*from\\s+[A-Za-z0-9_.]+\\s+import\\s+", RegexOption.MULTILINE) to 3.0f,
                Regex("^\\s*elif\\b.*:", RegexOption.MULTILINE) to 2.5f,
                Regex("\\bself\\.[A-Za-z_]") to 2.5f
            )
        ),
        LanguageSignature(
            id = "typescript",
            displayName = "TypeScript",
            extension = ".ts",
            patterns = listOf(
                Regex("\\binterface\\s+[A-Z][A-Za-z0-9_]*\\s*\\{") to 3.0f,
                Regex(":\\s*(string|number|boolean|unknown|Record<|Promise<)") to 2.8f,
                Regex("\\bexport\\s+(default\\s+)?(function|const|class|type|interface)\\b") to 2.5f
            )
        ),
        LanguageSignature(
            id = "javascript",
            displayName = "JavaScript",
            extension = ".js",
            patterns = listOf(
                Regex("\\b(const|let|var)\\s+[A-Za-z_$][A-Za-z0-9_$]*\\s*=") to 2.0f,
                Regex("\\bconsole\\.log\\(") to 2.5f,
                Regex("=>\\s*\\{") to 2.0f,
                Regex("\\bmodule\\.exports\\b|\\brequire\\(") to 3.0f
            )
        ),
        LanguageSignature(
            id = "sql",
            displayName = "SQL",
            extension = ".sql",
            patterns = listOf(
                Regex("\\bSELECT\\b.+\\bFROM\\b", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)) to 3.5f,
                Regex("\\b(CREATE TABLE|INSERT INTO|GROUP BY|LEFT JOIN)\\b", RegexOption.IGNORE_CASE) to 3.0f
            )
        ),
        LanguageSignature(
            id = "json",
            displayName = "JSON",
            extension = ".json",
            patterns = listOf(
                Regex("^\\s*[\\{\\[]") to 1.2f,
                Regex("\"[^\"]+\"\\s*:\\s*(\"[^\"]*\"|\\d+|true|false|null|\\{|\\[)") to 3.0f
            )
        ),
        LanguageSignature(
            id = "html",
            displayName = "HTML",
            extension = ".html",
            patterns = listOf(
                Regex("<!DOCTYPE\\s+html>", RegexOption.IGNORE_CASE) to 4.0f,
                Regex("<(div|span|section|head|body|script|style)\\b", RegexOption.IGNORE_CASE) to 2.5f
            )
        ),
        LanguageSignature(
            id = "css",
            displayName = "CSS",
            extension = ".css",
            patterns = listOf(
                Regex("[.#][A-Za-z0-9_-]+\\s*\\{") to 2.0f,
                Regex("\\b(display|background-color|margin|padding|font-size)\\s*:") to 3.0f
            )
        )
    )

    fun detectLanguage(codeText: String, userOverrideExtension: String? = null): DetectedLanguage {
        if (!userOverrideExtension.isNullOrBlank()) {
            val ext = if (userOverrideExtension.startsWith(".")) userOverrideExtension else ".$userOverrideExtension"
            val matched = signatures.firstOrNull { it.extension.equals(ext, ignoreCase = true) }
            return DetectedLanguage(
                languageId = matched?.id ?: "custom",
                displayName = matched?.displayName ?: ext.removePrefix(".").uppercase(),
                suggestedExtension = ext,
                confidence = 1.0f,
                isUserOverridden = true
            )
        }

        if (codeText.isBlank()) {
            return DetectedLanguage("plaintext", "Plain Text", ".txt", 0.0f)
        }

        val scores = signatures.map { sig ->
            val score = sig.patterns.sumOf { (regex, weight) ->
                if (regex.containsMatchIn(codeText)) weight.toDouble() else 0.0
            }.toFloat()
            sig to score
        }.sortedByDescending { it.second }

        val top = scores.firstOrNull()
        val runnerUp = scores.getOrNull(1)
        if (top == null || top.second < 2.5f) {
            return DetectedLanguage("plaintext", "Plain Text", ".txt", 0.35f)
        }

        // Check for React JSX/TSX cues
        val isJsx = Regex("<[A-Z][A-Za-z0-9]*\\b").containsMatchIn(codeText)
        val ext = when {
            top.first.id == "typescript" && isJsx -> ".tsx"
            top.first.id == "javascript" && isJsx -> ".jsx"
            else -> top.first.extension
        }

        val margin = if (runnerUp != null && runnerUp.second > 0f) {
            (top.second - runnerUp.second) / top.second
        } else {
            1.0f
        }
        val confidence = ((top.second / 8.0f).coerceIn(0.45f, 0.96f) * (0.65f + 0.35f * margin))
            .coerceIn(0.40f, 0.98f)

        // Safe fallback to plain text when confidence is too low (FR-19)
        if (confidence < 0.50f) {
            return DetectedLanguage("plaintext", "Plain Text", ".txt", confidence)
        }

        return DetectedLanguage(
            languageId = top.first.id,
            displayName = top.first.displayName,
            suggestedExtension = ext,
            confidence = confidence
        )
    }

    /**
     * Performs non-destructive delimiter, quote, and gap checks (FR-20, Section 15.4).
     */
    fun validateSyntax(lines: List<ReconstructedLine>, language: DetectedLanguage): List<SyntaxDiagnostic> {
        val diagnostics = mutableListOf<SyntaxDiagnostic>()
        val openStack = ArrayDeque<Pair<Char, Int>>() // (opening char, lineIndex)
        val matchingClose = mapOf('{' to '}', '(' to ')', '[' to ']')
        val matchingOpen = mapOf('}' to '{', ')' to '(', ']' to '[')

        for (line in lines) {
            if (line.isGapMarker) {
                diagnostics += SyntaxDiagnostic(
                    lineIndex = line.lineIndex,
                    severity = DiagnosticSeverity.WARNING,
                    message = line.gapReason ?: "Flagged extraction gap."
                )
                continue
            }

            val text = line.text
            var inSingleQuote = false
            var inDoubleQuote = false
            var escaped = false

            for (i in text.indices) {
                val ch = text[i]
                if (escaped) {
                    escaped = false
                    continue
                }
                if (ch == '\\') {
                    escaped = true
                    continue
                }
                if (ch == '/' && i + 1 < text.length && text[i + 1] == '/' && !inSingleQuote && !inDoubleQuote) {
                    break // Line comment
                }
                if (ch == '#' && language.languageId == "python" && !inSingleQuote && !inDoubleQuote) {
                    break
                }
                if (ch == '"' && !inSingleQuote) {
                    inDoubleQuote = !inDoubleQuote
                    continue
                }
                if (ch == '\'' && !inDoubleQuote) {
                    inSingleQuote = !inSingleQuote
                    continue
                }
                if (inSingleQuote || inDoubleQuote) continue

                if (ch in matchingClose.keys) {
                    openStack.addLast(ch to line.lineIndex)
                } else if (ch in matchingOpen.keys) {
                    val expectedOpen = matchingOpen[ch]
                    if (openStack.isEmpty()) {
                        val confusedWith = if (ch == '}') ")" else "}"
                        diagnostics += SyntaxDiagnostic(
                            lineIndex = line.lineIndex,
                            severity = DiagnosticSeverity.ERROR,
                            message = "Unmatched closing '$ch' (possible OCR confusion with '$confusedWith')."
                        )
                    } else if (openStack.last().first != expectedOpen) {
                        val (opened, openLine) = openStack.last()
                        diagnostics += SyntaxDiagnostic(
                            lineIndex = line.lineIndex,
                            severity = DiagnosticSeverity.ERROR,
                            message = "Delimiter mismatch: saw '$ch' while '$opened' from line ${openLine + 1} is still open."
                        )
                    } else {
                        openStack.removeLast()
                    }
                }
            }

            if (inDoubleQuote) {
                diagnostics += SyntaxDiagnostic(
                    lineIndex = line.lineIndex,
                    severity = DiagnosticSeverity.WARNING,
                    message = "Unclosed double-quoted string literal on line ${line.lineIndex + 1}."
                )
            }
        }

        while (openStack.isNotEmpty()) {
            val (opened, openLine) = openStack.removeLast()
            diagnostics += SyntaxDiagnostic(
                lineIndex = openLine,
                severity = DiagnosticSeverity.WARNING,
                message = "Unclosed '$opened' opened on line ${openLine + 1} (may indicate missing closing brace or truncated capture)."
            )
        }

        return diagnostics
    }
}
