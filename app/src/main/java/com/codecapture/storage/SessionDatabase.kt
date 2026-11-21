package com.codecapture.storage

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "capture_sessions")
data class SessionEntity(
    @PrimaryKey val sessionId: String,
    val sessionName: String,
    val dateFolder: String,
    val directoryPath: String,
    val createdAtEpochMs: Long,
    val completedAtEpochMs: Long?,
    val deviceProfileId: String?,
    val deviceDisplayName: String?,
    val operatingMode: String,
    val frameCount: Int,
    val extractionStatus: String,
    val extractedFilePath: String?,
    val extractionReportPath: String?
)

@Entity(tableName = "captured_frames")
data class FrameEntity(
    @PrimaryKey val frameId: String,
    val sessionId: String,
    val sequenceIndex: Int,
    val imagePath: String,
    val capturedAtEpochMs: Long,
    val qualityJson: String,
    val editorRegionJson: String,
    val estimatedOverlapRatio: Float?,
    val transitionConfidence: Float,
    val scrollTicksApplied: Int,
    val operatingMode: String
)

@Entity(tableName = "host_device_profiles")
data class DeviceProfileEntity(
    @PrimaryKey val id: String,
    val bluetoothAddress: String,
    val systemName: String,
    val displayName: String,
    val operatingSystemHint: String,
    val scrollCalibrationJson: String,
    val lastConnectedEpochMs: Long
)

@Entity(tableName = "extraction_checkpoints")
data class ExtractionCheckpointEntity(
    @PrimaryKey val sessionId: String,
    val status: String,
    val currentStage: String,
    val processedFrameCount: Int,
    val totalFrameCount: Int,
    val intermediateOcrJson: String,
    val engineId: String,
    val languageOverride: String?,
    val errorMessage: String?,
    val updatedAtEpochMs: Long
)

@Dao
interface SessionDao {
    @Query("SELECT * FROM capture_sessions ORDER BY createdAtEpochMs DESC")
    fun observeAllSessions(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM capture_sessions WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getSession(sessionId: String): SessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: SessionEntity)

    @Query("UPDATE capture_sessions SET frameCount = :count WHERE sessionId = :sessionId")
    suspend fun updateFrameCount(sessionId: String, count: Int)

    @Query("UPDATE capture_sessions SET completedAtEpochMs = :completedAt WHERE sessionId = :sessionId")
    suspend fun markCompleted(sessionId: String, completedAt: Long)

    @Query("UPDATE capture_sessions SET extractionStatus = :status, extractedFilePath = :extractedPath, extractionReportPath = :reportPath WHERE sessionId = :sessionId")
    suspend fun updateExtractionResult(
        sessionId: String,
        status: String,
        extractedPath: String?,
        reportPath: String?
    )

    @Query("UPDATE capture_sessions SET extractionStatus = :status WHERE sessionId = :sessionId")
    suspend fun updateExtractionStatus(sessionId: String, status: String)

    @Query("DELETE FROM capture_sessions WHERE sessionId = :sessionId")
    suspend fun deleteSession(sessionId: String)
}

@Dao
interface FrameDao {
    @Query("SELECT * FROM captured_frames WHERE sessionId = :sessionId ORDER BY sequenceIndex ASC")
    suspend fun getFramesForSession(sessionId: String): List<FrameEntity>

    @Query("SELECT * FROM captured_frames WHERE sessionId = :sessionId ORDER BY sequenceIndex ASC")
    fun observeFramesForSession(sessionId: String): Flow<List<FrameEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFrame(frame: FrameEntity)

    @Query("DELETE FROM captured_frames WHERE frameId = :frameId")
    suspend fun deleteFrame(frameId: String)

    @Query("DELETE FROM captured_frames WHERE sessionId = :sessionId")
    suspend fun deleteFramesForSession(sessionId: String)
}

@Dao
interface DeviceProfileDao {
    @Query("SELECT * FROM host_device_profiles ORDER BY lastConnectedEpochMs DESC")
    fun observeAll(): Flow<List<DeviceProfileEntity>>

    @Query("SELECT * FROM host_device_profiles WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): DeviceProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: DeviceProfileEntity)

    @Query("DELETE FROM host_device_profiles WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface ExtractionCheckpointDao {
    @Query("SELECT * FROM extraction_checkpoints WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getCheckpoint(sessionId: String): ExtractionCheckpointEntity?

    @Query("SELECT * FROM extraction_checkpoints WHERE sessionId = :sessionId LIMIT 1")
    fun observeCheckpoint(sessionId: String): Flow<ExtractionCheckpointEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCheckpoint(checkpoint: ExtractionCheckpointEntity)

    @Query("DELETE FROM extraction_checkpoints WHERE sessionId = :sessionId")
    suspend fun clearCheckpoint(sessionId: String)
}

@Database(
    entities = [
        SessionEntity::class,
        FrameEntity::class,
        DeviceProfileEntity::class,
        ExtractionCheckpointEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class CodeCaptureDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun frameDao(): FrameDao
    abstract fun deviceProfileDao(): DeviceProfileDao
    abstract fun extractionCheckpointDao(): ExtractionCheckpointDao

    companion object {
        fun build(context: Context): CodeCaptureDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                CodeCaptureDatabase::class.java,
                "codecapture.db"
            ).build()
    }
}
