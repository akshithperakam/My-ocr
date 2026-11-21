package com.codecapture.storage

import android.content.Context
import com.codecapture.domain.model.CaptureSessionMetadata
import com.codecapture.domain.model.CapturedFrame
import com.codecapture.domain.model.ExtractionReport
import com.codecapture.domain.model.ExtractionStatus
import com.codecapture.domain.model.HostDeviceProfile
import com.codecapture.domain.model.ImageQualityMetrics
import com.codecapture.domain.model.NormalizedRect
import com.codecapture.domain.model.OperatingMode
import com.codecapture.domain.model.ScrollCalibration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Dual-output local storage repository (FR-13, FR-22, Section 14.10).
 * Directory layout:
 *   CodeCapture/Sessions/<SessionName>/<YYYY-MM-DD>/
 *     Photos/image_001.jpg, image_002.jpg, ...
 *     Extracted/source_001.<ext>, extraction_report.json
 *     session_metadata.json
 *
 * Original photographs are NEVER deleted by extraction or re-extraction (AC-14, AC-22).
 */
class SessionRepository(
    private val context: Context,
    private val database: CodeCaptureDatabase
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val rootDir: File
        get() = File(context.filesDir, "CodeCapture/Sessions").apply { mkdirs() }

    fun observeSessions(): Flow<List<CaptureSessionMetadata>> =
        database.sessionDao().observeAllSessions().map { list ->
            list.map { it.toMetadata() }
        }

    fun observeDeviceProfiles(): Flow<List<HostDeviceProfile>> =
        database.deviceProfileDao().observeAll().map { list ->
            list.map { it.toDomain() }
        }

    suspend fun upsertDeviceProfile(profile: HostDeviceProfile) {
        database.deviceProfileDao().upsert(
            DeviceProfileEntity(
                id = profile.id,
                bluetoothAddress = profile.bluetoothAddress,
                systemName = profile.systemName,
                displayName = profile.displayName,
                operatingSystemHint = profile.operatingSystemHint,
                scrollCalibrationJson = json.encodeToString(profile.scrollCalibration),
                lastConnectedEpochMs = profile.lastConnectedEpochMs
            )
        )
    }

    suspend fun deleteDeviceProfile(id: String) {
        database.deviceProfileDao().deleteById(id)
    }

    suspend fun createSession(
        sessionName: String,
        deviceProfileId: String?,
        deviceDisplayName: String?,
        operatingMode: OperatingMode
    ): CaptureSessionMetadata {
        val now = System.currentTimeMillis()
        val dateFolder = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))
        val safeName = sessionName.trim().replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "Session" }
        val sessionId = UUID.randomUUID().toString()
        val sessionDir = File(rootDir, "$safeName/$dateFolder/${sessionId.take(8)}").apply {
            File(this, "Photos").mkdirs()
            File(this, "Extracted").mkdirs()
        }

        val entity = SessionEntity(
            sessionId = sessionId,
            sessionName = sessionName.trim().ifBlank { safeName },
            dateFolder = dateFolder,
            directoryPath = sessionDir.absolutePath,
            createdAtEpochMs = now,
            completedAtEpochMs = null,
            deviceProfileId = deviceProfileId,
            deviceDisplayName = deviceDisplayName,
            operatingMode = operatingMode.name,
            frameCount = 0,
            extractionStatus = ExtractionStatus.NOT_STARTED.name,
            extractedFilePath = null,
            extractionReportPath = null
        )
        database.sessionDao().upsertSession(entity)
        val metadata = entity.toMetadata()
        writeSessionMetadataFile(sessionDir, metadata)
        return metadata
    }

    suspend fun allocateNextPhotoFile(sessionId: String, sequenceIndex: Int): File {
        val session = database.sessionDao().getSession(sessionId)
            ?: throw IllegalStateException("Session not found: $sessionId")
        val photosDir = File(session.directoryPath, "Photos").apply { mkdirs() }
        return File(photosDir, "image_${"%03d".format(sequenceIndex)}.jpg")
    }

    suspend fun recordAcceptedFrame(frame: CapturedFrame) {
        database.frameDao().upsertFrame(
            FrameEntity(
                frameId = frame.frameId,
                sessionId = frame.sessionId,
                sequenceIndex = frame.sequenceIndex,
                imagePath = frame.imagePath,
                capturedAtEpochMs = frame.capturedAtEpochMs,
                qualityJson = json.encodeToString(frame.quality),
                editorRegionJson = json.encodeToString(frame.editorRegion),
                estimatedOverlapRatio = frame.estimatedOverlapRatio,
                transitionConfidence = frame.transitionConfidence,
                scrollTicksApplied = frame.scrollTicksApplied,
                operatingMode = frame.operatingMode.name
            )
        )
        val frames = database.frameDao().getFramesForSession(frame.sessionId)
        database.sessionDao().updateFrameCount(frame.sessionId, frames.size)
        database.sessionDao().getSession(frame.sessionId)?.let { entity ->
            writeSessionMetadataFile(File(entity.directoryPath), entity.toMetadata())
        }
    }

    suspend fun getFramesForSession(sessionId: String): List<CapturedFrame> =
        database.frameDao().getFramesForSession(sessionId).map { it.toDomain() }

    fun observeFramesForSession(sessionId: String): Flow<List<CapturedFrame>> =
        database.frameDao().observeFramesForSession(sessionId).map { list -> list.map { it.toDomain() } }

    suspend fun getSessionEntity(sessionId: String): SessionEntity? =
        database.sessionDao().getSession(sessionId)

    suspend fun markSessionCompleted(sessionId: String) {
        val now = System.currentTimeMillis()
        database.sessionDao().markCompleted(sessionId, now)
        database.sessionDao().getSession(sessionId)?.let { entity ->
            writeSessionMetadataFile(File(entity.directoryPath), entity.toMetadata())
        }
    }

    suspend fun updateExtractionStatus(sessionId: String, status: ExtractionStatus) {
        database.sessionDao().updateExtractionStatus(sessionId, status.name)
    }

    /**
     * Persists both the reconstructed editable source file and the structured extraction_report.json
     * alongside the untouched original photographs (FR-22).
     */
    suspend fun saveExtractionOutput(
        sessionId: String,
        sourceCodeText: String,
        report: ExtractionReport,
        status: ExtractionStatus
    ): Pair<File, File> {
        val session = database.sessionDao().getSession(sessionId)
            ?: throw IllegalStateException("Session not found: $sessionId")
        val extractedDir = File(session.directoryPath, "Extracted").apply { mkdirs() }

        val sourceFile = File(extractedDir, report.outputFileName)
        sourceFile.writeText(sourceCodeText, Charsets.UTF_8)

        val reportFile = File(extractedDir, "extraction_report.json")
        reportFile.writeText(json.encodeToString(report), Charsets.UTF_8)

        database.sessionDao().updateExtractionResult(
            sessionId = sessionId,
            status = status.name,
            extractedPath = sourceFile.absolutePath,
            reportPath = reportFile.absolutePath
        )

        database.sessionDao().getSession(sessionId)?.let { updated ->
            writeSessionMetadataFile(File(updated.directoryPath), updated.toMetadata())
        }
        return sourceFile to reportFile
    }

    suspend fun loadExtractionReport(sessionId: String): ExtractionReport? {
        val session = database.sessionDao().getSession(sessionId) ?: return null
        val reportPath = session.extractionReportPath ?: return null
        val file = File(reportPath)
        if (!file.exists()) return null
        return runCatching { json.decodeFromString<ExtractionReport>(file.readText()) }.getOrNull()
    }

    suspend fun deleteSinglePhoto(frameId: String, sessionId: String) {
        val frames = database.frameDao().getFramesForSession(sessionId)
        val target = frames.firstOrNull { it.frameId == frameId } ?: return
        File(target.imagePath).delete()
        database.frameDao().deleteFrame(frameId)
        database.sessionDao().updateFrameCount(sessionId, (frames.size - 1).coerceAtLeast(0))
    }

    private fun writeSessionMetadataFile(sessionDir: File, metadata: CaptureSessionMetadata) {
        runCatching {
            File(sessionDir, "session_metadata.json")
                .writeText(json.encodeToString(metadata), Charsets.UTF_8)
        }
    }

    private fun SessionEntity.toMetadata(): CaptureSessionMetadata =
        CaptureSessionMetadata(
            sessionId = sessionId,
            sessionName = sessionName,
            dateFolder = dateFolder,
            createdAtEpochMs = createdAtEpochMs,
            completedAtEpochMs = completedAtEpochMs,
            deviceProfileId = deviceProfileId,
            deviceDisplayName = deviceDisplayName,
            operatingMode = runCatching { OperatingMode.valueOf(operatingMode) }.getOrDefault(OperatingMode.FIXED),
            frameCount = frameCount,
            extractionStatus = runCatching { ExtractionStatus.valueOf(extractionStatus) }
                .getOrDefault(ExtractionStatus.NOT_STARTED)
        )

    private fun FrameEntity.toDomain(): CapturedFrame =
        CapturedFrame(
            frameId = frameId,
            sessionId = sessionId,
            sequenceIndex = sequenceIndex,
            imagePath = imagePath,
            capturedAtEpochMs = capturedAtEpochMs,
            quality = json.decodeFromString<ImageQualityMetrics>(qualityJson),
            editorRegion = json.decodeFromString<NormalizedRect>(editorRegionJson),
            estimatedOverlapRatio = estimatedOverlapRatio,
            transitionConfidence = transitionConfidence,
            scrollTicksApplied = scrollTicksApplied,
            operatingMode = runCatching { OperatingMode.valueOf(operatingMode) }.getOrDefault(OperatingMode.FIXED)
        )

    private fun DeviceProfileEntity.toDomain(): HostDeviceProfile =
        HostDeviceProfile(
            id = id,
            bluetoothAddress = bluetoothAddress,
            systemName = systemName,
            displayName = displayName,
            operatingSystemHint = operatingSystemHint,
            scrollCalibration = json.decodeFromString<ScrollCalibration>(scrollCalibrationJson),
            lastConnectedEpochMs = lastConnectedEpochMs
        )
}
