package com.bloodsugar.app.data

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@Serializable
data class ReadingBackup(
    val id: Long = 0,
    val type: String,
    val value: Double,
    val unit: String,
    val dateTimestamp: Long, // Store date as timestamp for JSON serialization
    val notes: String = ""
) {
    fun toReading(): Reading {
        return Reading(
            id = 0, // Let Room auto-generate new IDs
            type = type,
            value = value,
            unit = unit,
            date = Date(dateTimestamp),
            notes = notes
        )
    }

    companion object {
        fun fromReading(reading: Reading): ReadingBackup {
            return ReadingBackup(
                id = reading.id,
                type = reading.type,
                value = reading.value,
                unit = reading.unit,
                dateTimestamp = reading.date.time,
                notes = reading.notes
            )
        }
    }
}

@Serializable
data class AppBackup(
    val exportDate: Long,
    val appVersion: String,
    val readings: List<ReadingBackup>
)

class BackupService(
    private val context: Context,
    private val repository: ReadingRepository
) {
    private val json = Json { prettyPrint = true }
    private val backupFileName = "bloodsugar_backup.json"
    private val autoBackupFileName = "bloodsugar_auto_backup.json"

    private val backupDir: File
        get() {
            // Try multiple backup locations for maximum persistence
            val locations = listOf(
                // Primary: Downloads folder (survives uninstalls and is user-accessible)
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "BloodSugarApp"),
                // Secondary: Documents folder
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "BloodSugarApp"),
                // Tertiary: External storage root
                File(Environment.getExternalStorageDirectory(), "BloodSugarApp"),
                // Fallback: App-specific external storage (gets deleted on uninstall)
                File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "BloodSugarApp")
            )

            // Return the first location that is writable
            for (location in locations) {
                try {
                    if (!location.exists()) {
                        location.mkdirs()
                    }
                    if (location.exists() && location.canWrite()) {
                        return location
                    }
                } catch (e: Exception) {
                    // Continue to next location
                }
            }

            // Final fallback - use app's cache directory
            return File(context.cacheDir, "BloodSugarApp")
        }

    suspend fun createBackup(includeAutoBackup: Boolean = true): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Ensure backup directory exists
            if (!backupDir.exists()) {
                backupDir.mkdirs()
            }

            // Get all readings from database using direct method (not Flow)
            val readingsList = try {
                repository.getAllReadingsForBackup()
            } catch (e: Exception) {
                emptyList()
            }

            // Create backup data
            val backupReadings = readingsList.map { ReadingBackup.fromReading(it) }
            val backup = AppBackup(
                exportDate = System.currentTimeMillis(),
                appVersion = "1.0.0",
                readings = backupReadings
            )

            // Write manual backup
            val backupFile = File(backupDir, backupFileName)
            val jsonString = json.encodeToString(backup)
            backupFile.writeText(jsonString)

            // Write auto backup if requested
            if (includeAutoBackup) {
                val autoBackupFile = File(backupDir, autoBackupFileName)
                autoBackupFile.writeText(jsonString)
            }

            // Return success with count
            Result.success("Backup created with ${backupReadings.size} readings at: ${backupFile.absolutePath}")
        } catch (e: Exception) {
            Result.failure(Exception("Backup failed: ${e.message}", e))
        }
    }

    suspend fun restoreFromBackup(filePath: String? = null): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val backupFile = if (filePath != null) {
                File(filePath)
            } else {
                // Search across all possible backup locations for any existing backup
                val locations = listOf(
                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "BloodSugarApp"),
                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "BloodSugarApp"),
                    File(Environment.getExternalStorageDirectory(), "BloodSugarApp"),
                    File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "BloodSugarApp"),
                    File(context.cacheDir, "BloodSugarApp")
                )

                var foundFile: File? = null
                for (location in locations) {
                    try {
                        val autoFile = File(location, autoBackupFileName)
                        val manualFile = File(location, backupFileName)
                        when {
                            autoFile.exists() -> {
                                foundFile = autoFile
                                break
                            }
                            manualFile.exists() -> {
                                foundFile = manualFile
                                break
                            }
                        }
                    } catch (e: Exception) {
                        // Continue searching other locations
                    }
                }

                foundFile ?: return@withContext Result.failure(Exception("No backup file found in any location"))
            }

            if (!backupFile.exists()) {
                return@withContext Result.failure(Exception("Backup file not found: ${backupFile.absolutePath}"))
            }

            val jsonString = backupFile.readText()
            val backup = json.decodeFromString<AppBackup>(jsonString)

            // Restore readings to database
            var restoredCount = 0
            backup.readings.forEach { backupReading ->
                try {
                    repository.insertReading(backupReading.toReading())
                    restoredCount++
                } catch (e: Exception) {
                    // Log error but continue with other readings
                }
            }

            Result.success(restoredCount)
        } catch (e: Exception) {
            Result.failure(Exception("Restore failed: ${e.message}", e))
        }
    }

    suspend fun hasBackupAvailable(): Boolean = withContext(Dispatchers.IO) {
        // Search across all possible backup locations
        val locations = listOf(
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "BloodSugarApp"),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "BloodSugarApp"),
            File(Environment.getExternalStorageDirectory(), "BloodSugarApp"),
            File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "BloodSugarApp"),
            File(context.cacheDir, "BloodSugarApp")
        )

        for (location in locations) {
            try {
                val autoFile = File(location, autoBackupFileName)
                val manualFile = File(location, backupFileName)
                if (autoFile.exists() || manualFile.exists()) {
                    return@withContext true
                }
            } catch (e: Exception) {
                // Continue searching other locations
            }
        }
        false
    }

    suspend fun getBackupInfo(): BackupInfo? = withContext(Dispatchers.IO) {
        // Search across all possible backup locations
        val locations = listOf(
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "BloodSugarApp"),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "BloodSugarApp"),
            File(Environment.getExternalStorageDirectory(), "BloodSugarApp"),
            File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "BloodSugarApp"),
            File(context.cacheDir, "BloodSugarApp")
        )

        for (location in locations) {
            try {
                val autoFile = File(location, autoBackupFileName)
                val manualFile = File(location, backupFileName)

                val file = when {
                    autoFile.exists() -> autoFile
                    manualFile.exists() -> manualFile
                    else -> continue
                }

                val jsonString = file.readText()
                val backup = json.decodeFromString<AppBackup>(jsonString)

                return@withContext BackupInfo(
                    date = Date(backup.exportDate),
                    readingCount = backup.readings.size,
                    filePath = file.absolutePath
                )
            } catch (e: Exception) {
                // Continue searching other locations
            }
        }
        null
    }

    suspend fun deleteBackup(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val autoFile = File(backupDir, autoBackupFileName)
            val manualFile = File(backupDir, backupFileName)

            var deleted = false
            if (autoFile.exists()) {
                deleted = autoFile.delete() || deleted
            }
            if (manualFile.exists()) {
                deleted = manualFile.delete() || deleted
            }

            Result.success(deleted)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

data class BackupInfo(
    val date: Date,
    val readingCount: Int,
    val filePath: String
)
