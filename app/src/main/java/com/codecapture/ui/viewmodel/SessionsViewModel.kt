package com.codecapture.ui.viewmodel

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.codecapture.di.AppContainer
import com.codecapture.domain.model.CaptureSessionMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SessionsViewModel(
    private val container: AppContainer
) : ViewModel() {

    val sessions: StateFlow<List<CaptureSessionMetadata>> =
        container.sessionRepository.observeSessions()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun rerunExtraction(sessionId: String, languageOverride: String? = null) {
        container.enqueueExtraction(
            sessionId = sessionId,
            languageOverride = languageOverride,
            forceRestart = true
        )
    }

    fun cancelExtraction(sessionId: String) {
        container.cancelExtraction(sessionId)
    }

    fun exportDualOutputZip(sessionId: String, onShareReady: (Intent) -> Unit) {
        viewModelScope.launch {
            val entity = container.sessionRepository.getSessionEntity(sessionId) ?: return@launch
            val zipFile = withContext(Dispatchers.IO) {
                container.sessionExporter.exportDualOutputZip(
                    sessionDir = File(entity.directoryPath),
                    sessionName = entity.sessionName
                )
            }
            val intent = container.sessionExporter.buildShareIntent(
                file = zipFile,
                mimeType = "application/zip",
                title = "CodeCapture Dual Export — ${entity.sessionName}"
            )
            onShareReady(intent)
        }
    }

    fun exportPdfReport(sessionId: String, onShareReady: (Intent) -> Unit) {
        viewModelScope.launch {
            val entity = container.sessionRepository.getSessionEntity(sessionId) ?: return@launch
            val frames = container.sessionRepository.getFramesForSession(sessionId)
            val report = container.sessionRepository.loadExtractionReport(sessionId)
            val pdfFile = withContext(Dispatchers.IO) {
                container.sessionExporter.generateSessionPdfReport(
                    sessionName = entity.sessionName,
                    photoFiles = frames.map { File(it.imagePath) },
                    report = report
                )
            }
            val intent = container.sessionExporter.buildShareIntent(
                file = pdfFile,
                mimeType = "application/pdf",
                title = "CodeCapture Report — ${entity.sessionName}"
            )
            onShareReady(intent)
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SessionsViewModel(container) as T
            }
    }
}
