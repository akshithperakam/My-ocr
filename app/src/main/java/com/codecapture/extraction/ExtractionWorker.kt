package com.codecapture.extraction

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.codecapture.CodeCaptureApplication
import com.codecapture.R
import com.codecapture.domain.model.ExtractionStatus
import kotlinx.coroutines.CancellationException

/**
 * Resilient WorkManager background extraction worker (FR-16, Section 15.5, AC-12, AC-22).
 * - Decoupled from camera and Bluetooth lifecycle (never holds camera or BT open).
 * - Persists per-frame checkpoints so extraction resumes safely after process death.
 * - Cancellation or failure NEVER deletes or corrupts original photographs.
 */
class ExtractionWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val sessionId = inputData.getString(KEY_SESSION_ID) ?: return Result.failure()
        val languageOverride = inputData.getString(KEY_LANGUAGE_OVERRIDE)
        val forceRestart = inputData.getBoolean(KEY_FORCE_RESTART, false)

        val container = (applicationContext as CodeCaptureApplication).container
        ensureNotificationChannel()
        setForeground(createForegroundInfo(sessionId, "Starting offline code extraction...", 0, 1))

        return try {
            val report = container.extractionPipeline.run(
                sessionId = sessionId,
                languageOverrideExtension = languageOverride,
                forceRestart = forceRestart
            ) { progress ->
                setProgress(
                    workDataOf(
                        KEY_PROGRESS_STAGE to progress.currentStage.name,
                        KEY_PROGRESS_MESSAGE to progress.message,
                        KEY_PROGRESS_DONE to progress.processedFrames,
                        KEY_PROGRESS_TOTAL to progress.totalFrames
                    )
                )
                setForeground(
                    createForegroundInfo(
                        sessionId = sessionId,
                        message = progress.message,
                        done = progress.processedFrames,
                        total = progress.totalFrames.coerceAtLeast(1)
                    )
                )
            }
            notifyCompletion(
                sessionId = sessionId,
                title = "Code extraction finished",
                body = "${report.outputFileName} (${report.totalReconstructedLines} lines, ${(report.overallConfidence * 100).toInt()}% confidence)"
            )
            Result.success(workDataOf(KEY_OUTPUT_FILE to report.outputFileName))
        } catch (ce: CancellationException) {
            container.sessionRepository.updateExtractionStatus(sessionId, ExtractionStatus.CANCELLED)
            throw ce
        } catch (e: Exception) {
            container.sessionRepository.updateExtractionStatus(sessionId, ExtractionStatus.FAILED)
            notifyCompletion(
                sessionId = sessionId,
                title = "Code extraction failed",
                body = "Original photographs remain safely stored. Error: ${e.message}"
            )
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Unknown extraction error")))
        }
    }

    private fun createForegroundInfo(
        sessionId: String,
        message: String,
        done: Int,
        total: Int
    ): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("CodeCapture — Extracting Code")
            .setContentText(message)
            .setOngoing(true)
            .setProgress(total, done, total <= 1)
            .build()

        val notifId = sessionId.hashCode()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notifId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notifId, notification)
        }
    }

    private fun notifyCompletion(sessionId: String, title: String, body: String) {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .build()
        nm.notify(sessionId.hashCode() + 1, notification)
    }

    private fun ensureNotificationChannel() {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.extraction_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = applicationContext.getString(R.string.extraction_channel_desc)
            }
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "codecapture_extraction"
        const val KEY_SESSION_ID = "session_id"
        const val KEY_LANGUAGE_OVERRIDE = "language_override"
        const val KEY_FORCE_RESTART = "force_restart"
        const val KEY_PROGRESS_STAGE = "progress_stage"
        const val KEY_PROGRESS_MESSAGE = "progress_message"
        const val KEY_PROGRESS_DONE = "progress_done"
        const val KEY_PROGRESS_TOTAL = "progress_total"
        const val KEY_OUTPUT_FILE = "output_file"
        const val KEY_ERROR = "error"

        fun uniqueWorkName(sessionId: String): String = "extract_session_$sessionId"

        fun enqueue(
            context: Context,
            sessionId: String,
            languageOverride: String? = null,
            forceRestart: Boolean = false
        ) {
            val request = OneTimeWorkRequestBuilder<ExtractionWorker>()
                .setInputData(
                    workDataOf(
                        KEY_SESSION_ID to sessionId,
                        KEY_LANGUAGE_OVERRIDE to languageOverride,
                        KEY_FORCE_RESTART to forceRestart
                    )
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueWorkName(sessionId),
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        fun cancel(context: Context, sessionId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(uniqueWorkName(sessionId))
        }
    }
}
