package com.codecapture.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.codecapture.di.AppContainer
import com.codecapture.domain.model.ExtractionReport
import com.codecapture.domain.model.ExtractionStatus
import com.codecapture.domain.model.ReconstructedLine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReviewUiState(
    val sessionId: String = "",
    val report: ExtractionReport? = null,
    val selectedLineIndex: Int = 0,
    val saveBanner: String? = null,
    val isLoading: Boolean = true
)

class ReviewViewModel(
    private val container: AppContainer,
    private val sessionId: String
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReviewUiState(sessionId = sessionId))
    val uiState: StateFlow<ReviewUiState> = _uiState.asStateFlow()

    init {
        loadReport()
    }

    fun loadReport() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val report = container.sessionRepository.loadExtractionReport(sessionId)
            val firstUncertain = report?.lines?.indexOfFirst {
                it.isGapMarker || it.confidence < 0.78f || it.ambiguities.isNotEmpty()
            }?.takeIf { it >= 0 } ?: 0
            _uiState.update {
                it.copy(
                    report = report,
                    selectedLineIndex = firstUncertain,
                    isLoading = false
                )
            }
        }
    }

    fun selectLine(index: Int) {
        _uiState.update { it.copy(selectedLineIndex = index) }
    }

    fun editLineText(lineIndex: Int, newText: String) {
        val current = _uiState.value.report ?: return
        val updatedLines = current.lines.map { line ->
            if (line.lineIndex == lineIndex) {
                line.copy(
                    text = newText,
                    isUserCorrected = true,
                    isGapMarker = false,
                    confidence = 1.0f,
                    ambiguities = emptyList()
                )
            } else {
                line
            }
        }
        val revalidatedDiagnostics = container.languageValidator.validateSyntax(updatedLines, current.detectedLanguage)
        _uiState.update {
            it.copy(
                report = current.copy(
                    lines = updatedLines,
                    diagnostics = revalidatedDiagnostics,
                    uncertainLineCount = updatedLines.count { l -> !l.isUserCorrected && (l.confidence < 0.78f || l.isGapMarker) }
                ),
                saveBanner = null
            )
        }
    }

    fun overrideExtension(extension: String) {
        val current = _uiState.value.report ?: return
        val newLang = container.languageValidator.detectLanguage(
            codeText = current.lines.joinToString("\n") { it.text },
            userOverrideExtension = extension
        )
        val newDiagnostics = container.languageValidator.validateSyntax(current.lines, newLang)
        _uiState.update {
            it.copy(
                report = current.copy(
                    detectedLanguage = newLang,
                    outputFileName = "source_001${newLang.suggestedExtension}",
                    diagnostics = newDiagnostics
                ),
                saveBanner = null
            )
        }
    }

    fun saveAndAccept(markFinal: Boolean) {
        val current = _uiState.value.report ?: return
        viewModelScope.launch {
            val codeText = current.lines.joinToString("\n") { it.text }
            val updatedReport = current.copy(requiresReview = !markFinal)
            val status = if (markFinal) ExtractionStatus.COMPLETED else ExtractionStatus.COMPLETED_WITH_WARNINGS
            container.sessionRepository.saveExtractionOutput(
                sessionId = sessionId,
                sourceCodeText = codeText,
                report = updatedReport,
                status = status
            )
            _uiState.update {
                it.copy(
                    report = updatedReport,
                    saveBanner = if (markFinal) {
                        "Accepted & saved ${updatedReport.outputFileName} (original photos preserved)."
                    } else {
                        "Draft saved to ${updatedReport.outputFileName}."
                    }
                )
            }
        }
    }

    fun rerunExtraction() {
        val ext = _uiState.value.report?.detectedLanguage
            ?.takeIf { it.isUserOverridden }
            ?.suggestedExtension
        container.enqueueExtraction(sessionId, languageOverride = ext, forceRestart = true)
        _uiState.update { it.copy(saveBanner = "Re-extraction queued in background.") }
    }

    fun selectedLine(): ReconstructedLine? =
        _uiState.value.report?.lines?.getOrNull(_uiState.value.selectedLineIndex)

    companion object {
        fun factory(container: AppContainer, sessionId: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ReviewViewModel(container, sessionId) as T
            }
    }
}
