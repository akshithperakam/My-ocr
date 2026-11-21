package com.codecapture.di

import android.content.Context
import com.codecapture.bluetooth.BluetoothHidController
import com.codecapture.camera.CameraCaptureManager
import com.codecapture.camera.ImageQualityAnalyzer
import com.codecapture.extraction.CodeLayoutReconstructor
import com.codecapture.extraction.CodeOcrEngine
import com.codecapture.extraction.ExtractionPipeline
import com.codecapture.extraction.ExtractionWorker
import com.codecapture.extraction.LanguageAndSyntaxValidator
import com.codecapture.extraction.MlKitCodeOcrEngine
import com.codecapture.extraction.MultiImageCodeAssembler
import com.codecapture.storage.CodeCaptureDatabase
import com.codecapture.storage.SessionExporter
import com.codecapture.storage.SessionRepository
import com.codecapture.vision.AdaptiveScrollController
import com.codecapture.vision.CaptureSessionOrchestrator
import com.codecapture.vision.EndOfContentDetector
import com.codecapture.vision.ScreenEditorDetector
import com.codecapture.vision.VisualOverlapMatcher

/**
 * Lightweight manual dependency container keeping every module (Bluetooth, Camera, CV, Extraction, Storage)
 * independently testable with zero reflection/annotation-processor overhead (NFR-07).
 */
class AppContainer(private val appContext: Context) {

    val database: CodeCaptureDatabase by lazy { CodeCaptureDatabase.build(appContext) }
    val sessionRepository: SessionRepository by lazy { SessionRepository(appContext, database) }
    val sessionExporter: SessionExporter by lazy { SessionExporter(appContext) }

    val bluetoothHidController: BluetoothHidController by lazy { BluetoothHidController(appContext) }
    val cameraCaptureManager: CameraCaptureManager by lazy { CameraCaptureManager(appContext) }
    val imageQualityAnalyzer: ImageQualityAnalyzer by lazy { ImageQualityAnalyzer() }
    val screenEditorDetector: ScreenEditorDetector by lazy { ScreenEditorDetector() }
    val visualOverlapMatcher: VisualOverlapMatcher by lazy { VisualOverlapMatcher() }
    val adaptiveScrollController: AdaptiveScrollController by lazy { AdaptiveScrollController() }
    val endOfContentDetector: EndOfContentDetector by lazy { EndOfContentDetector() }

    val ocrEngine: CodeOcrEngine by lazy { MlKitCodeOcrEngine() }
    val layoutReconstructor: CodeLayoutReconstructor by lazy { CodeLayoutReconstructor() }
    val multiImageAssembler: MultiImageCodeAssembler by lazy { MultiImageCodeAssembler() }
    val languageValidator: LanguageAndSyntaxValidator by lazy { LanguageAndSyntaxValidator() }

    val extractionPipeline: ExtractionPipeline by lazy {
        ExtractionPipeline(
            sessionRepository = sessionRepository,
            database = database,
            cameraManager = cameraCaptureManager,
            editorDetector = screenEditorDetector,
            ocrEngine = ocrEngine,
            layoutReconstructor = layoutReconstructor,
            multiImageAssembler = multiImageAssembler,
            languageValidator = languageValidator
        )
    }

    val captureOrchestrator: CaptureSessionOrchestrator by lazy {
        CaptureSessionOrchestrator(
            hidTransport = bluetoothHidController,
            cameraManager = cameraCaptureManager,
            qualityAnalyzer = imageQualityAnalyzer,
            editorDetector = screenEditorDetector,
            overlapMatcher = visualOverlapMatcher,
            scrollController = adaptiveScrollController,
            eofDetector = endOfContentDetector,
            sessionRepository = sessionRepository,
            onTriggerAutoExtraction = { sessionId ->
                ExtractionWorker.enqueue(appContext, sessionId)
            }
        )
    }

    fun enqueueExtraction(sessionId: String, languageOverride: String? = null, forceRestart: Boolean = false) {
        ExtractionWorker.enqueue(appContext, sessionId, languageOverride, forceRestart)
    }

    fun cancelExtraction(sessionId: String) {
        ExtractionWorker.cancel(appContext, sessionId)
    }
}
