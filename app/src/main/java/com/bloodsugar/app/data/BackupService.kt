@file:OptIn(InternalSerializationApi::class)

package com.bloodsugar.app.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.InputStream
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
    private val backupFolderName = "BloodSugarApp"
    private val TAG = "BackupService"

    // App-specific external folder (deleted on uninstall) - used as fallback
    private val appExternalFolder: File?
        get() = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)?.let { File(it, backupFolderName) }

    // Cache fallback
    private val cacheFolder: File
        get() = File(context.cacheDir, backupFolderName)

    private suspend fun writeStringToPublicDownloads(fileName: String, content: String): Uri? = withContext(Dispatchers.IO) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                // Check if file already exists
                val existing = findMediaStoreUriByName(fileName)
                if (existing != null) {
                    // Overwrite existing
                    resolver.openOutputStream(existing)?.use { out ->
                        out.write(content.toByteArray())
                    } ?: return@withContext null
                    return@withContext existing
                }

                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/$backupFolderName")
                }

                val uri = resolver.insert(collection, values) ?: return@withContext null
                resolver.openOutputStream(uri)?.use { out ->
                    out.write(content.toByteArray())
                } ?: return@withContext null

                return@withContext uri
            } else {
                // Pre-Q: write directly to public Download folder (requires WRITE_EXTERNAL_STORAGE on API<=28)
                val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val folder = File(downloads, backupFolderName)
                if (!folder.exists()) folder.mkdirs()
                val file = File(folder, fileName)
                file.writeText(content)
                // Return a file Uri for consistency (may be null for callers that only care about MediaStore)
                return@withContext Uri.fromFile(file)
            }
        } catch (e: SecurityException) {
            // Permission denied - rethrow so callers can handle and surface helpful UI
            Log.e(TAG, "Permission denied while writing to public Downloads: ${e.message}")
            throw e
        } catch (_: Exception) {
            // Other IO failures - return null so caller will use fallbacks
            Log.w(TAG, "Failed to write to public Downloads")
            return@withContext null
        }
    }

    private fun findMediaStoreUriByName(fileName: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(fileName)
        resolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(idIndex)
                return Uri.withAppendedPath(collection, id.toString())
            }
        }
        return null
    }

    // Return all URIs in MediaStore that match the given filename (may be multiple entries).
    private fun findAllMediaStoreUrisByName(fileName: String): List<Uri> {
        val result = mutableListOf<Uri>()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return result
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(fileName)
        resolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex)
                result.add(Uri.withAppendedPath(collection, id.toString()))
            }
        }
        return result
    }

    @Suppress("unused")
    // Search for a backup either in MediaStore (public Downloads) or filesystem fallbacks
    private fun findBackupSource(): Pair<File?, Uri?> {
        // 1) Look in MediaStore (modern, persistent across uninstall)
        try {
            val mediaUri = try {
                findMediaStoreUriByName(autoBackupFileName) ?: findMediaStoreUriByName(backupFileName)
            } catch (se: SecurityException) {
                // Don't rethrow: querying MediaStore for Downloads can fail under scoped storage
                // on some devices. Log and continue to filesystem/app cache fallbacks instead.
                Log.e(TAG, "Permission denied while querying MediaStore: ${se.message}")
                null
            } catch (e: Exception) {
                Log.w(TAG, "Failed while querying MediaStore: ${e.message}")
                null
            }
            if (mediaUri != null) return Pair(null, mediaUri)
        } catch (_: Exception) {
            // continue to other fallbacks
        }

        // 2) Look in public Downloads folder (pre-Q filesystem)
        // Accessing the public Downloads filesystem directly is only valid on pre-Q devices.
        // On Android Q+ the app should rely on MediaStore URIs (handled above) or the user
        // providing a Uri via the Storage Access Framework. Avoid direct filesystem access
        // on Q+ to prevent EACCES / SecurityException caused by scoped storage.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            try {
                val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val folder = File(downloads, backupFolderName)
                val autoFile = File(folder, autoBackupFileName)
                val manualFile = File(folder, backupFileName)
                if (autoFile.exists()) return Pair(autoFile, null)
                if (manualFile.exists()) return Pair(manualFile, null)
            } catch (e: SecurityException) {
                Log.e(TAG, "Permission denied while accessing public Downloads folder: ${e.message}")
                throw e
            } catch (_: Exception) {
                Log.w(TAG, "Failed while accessing public Downloads folder")
            }
        }

        // 3) Look in app-specific external folder
        try {
            appExternalFolder?.let { folder ->
                if (!folder.exists()) folder.mkdirs()
                val autoFile = File(folder, autoBackupFileName)
                val manualFile = File(folder, backupFileName)
                if (autoFile.exists()) return Pair(autoFile, null)
                if (manualFile.exists()) return Pair(manualFile, null)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied while accessing app external folder: ${e.message}")
            throw e
        } catch (_: Exception) {
            Log.w(TAG, "Failed while accessing app external folder")
        }

        // 4) Look in cache
        try {
            val autoFile = File(cacheFolder, autoBackupFileName)
            val manualFile = File(cacheFolder, backupFileName)
            if (autoFile.exists()) return Pair(autoFile, null)
            if (manualFile.exists()) return Pair(manualFile, null)
        } catch (_: Exception) {
            Log.w(TAG, "Failed while accessing cache folder")
        }

        return Pair(null, null)
    }

    // Collect all candidate backup File/Uri locations (both auto and manual filenames).
    private fun findBackupCandidates(): List<Pair<File?, Uri?>> {
        val candidates = mutableListOf<Pair<File?, Uri?>>()

        // 1) MediaStore (API Q+): check both filenames and include all matching URIs
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                findAllMediaStoreUrisByName(autoBackupFileName).forEach { candidates.add(Pair(null, it)) }
                findAllMediaStoreUrisByName(backupFileName).forEach { candidates.add(Pair(null, it)) }
            }
        } catch (_: Exception) {
            // ignore
        }

        // 2) Public Downloads folder (pre-Q)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            try {
                val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val folder = File(downloads, backupFolderName)
                val autoFile = File(folder, autoBackupFileName)
                val manualFile = File(folder, backupFileName)
                if (autoFile.exists()) candidates.add(Pair(autoFile, null))
                if (manualFile.exists()) candidates.add(Pair(manualFile, null))
            } catch (_: Exception) {
                // ignore
            }
        }

        // 3) App-specific external folder
        try {
            appExternalFolder?.let { folder ->
                val autoFile = File(folder, autoBackupFileName)
                val manualFile = File(folder, backupFileName)
                if (autoFile.exists()) candidates.add(Pair(autoFile, null))
                if (manualFile.exists()) candidates.add(Pair(manualFile, null))
            }
        } catch (_: Exception) {
            // ignore
        }

        // 4) Cache folder
        try {
            val autoFile = File(cacheFolder, autoBackupFileName)
            val manualFile = File(cacheFolder, backupFileName)
            if (autoFile.exists()) candidates.add(Pair(autoFile, null))
            if (manualFile.exists()) candidates.add(Pair(manualFile, null))
        } catch (_: Exception) {
            // ignore
        }

        return candidates
    }

    // Find the most recent backup by reading and parsing each candidate's exportDate.
    private suspend fun findLatestBackup(): Pair<File?, Uri?> = withContext(Dispatchers.IO) {
        val candidates = findBackupCandidates()
        // Capture folder paths into local vals to avoid smart-cast issues when using properties with custom getters
        val appExtPath: String? = try { appExternalFolder?.absolutePath } catch (_: Exception) { null }
        val cachePath: String = try { cacheFolder.absolutePath } catch (_: Exception) { "" }
        // Selection tuple: (hasExportDate (1/0), readingCount, timestampMillis, sourcePriority)
        // sourcePriority: 4=MediaStore(Uri),3=public Downloads,2=appExternal,1=cache
        var bestPair: Pair<File?, Uri?> = Pair(null, null)
        var bestHasExport = -1
        var bestReadingCount = -1
        var bestTimestamp = Long.MIN_VALUE
        var bestSourcePriority = -1

        candidates.forEach { (file, uri) ->
            try {
                val jsonString = when {
                    uri != null -> context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    file != null -> file.readText()
                    else -> null
                } ?: return@forEach

                try {
                    val parseResult = parseAppBackupWithInfo(jsonString)

                    val hadExport = if (parseResult.hadExportDate && parseResult.backup.exportDate > 0L) 1 else 0
                    val readingCount = parseResult.backup.readings.size

                    val timestamp = if (hadExport == 1) {
                        parseResult.backup.exportDate
                    } else {
                        // fallback to metadata
                        var metaDate = Long.MIN_VALUE
                        try {
                            if (file != null) {
                                val lm = file.lastModified()
                                if (lm > 0) metaDate = lm
                            }
                        } catch (_: Exception) {}

                        try {
                            if (uri != null) {
                                val msDate = queryMediaStoreDate(uri)
                                if (msDate != null && msDate > metaDate) metaDate = msDate
                            }
                        } catch (_: Exception) {}

                        metaDate
                    }

                    val sourcePriority = when {
                        uri != null -> 4
                        file != null && appExtPath != null && file.absolutePath.startsWith(appExtPath) -> 2
                        file != null && cachePath.isNotEmpty() && file.absolutePath.startsWith(cachePath) -> 1
                        file != null -> 3 // public Downloads or other filesystem
                        else -> 0
                    }

                    val candidateId = uri?.toString() ?: file?.absolutePath ?: "(unknown)"
                    Log.d(TAG, "Backup candidate: $candidateId -> hadExport=$hadExport, readings=$readingCount, timestamp=$timestamp, src=$sourcePriority")

                    // Prefer by: hadExport (1/0), then readingCount, then timestamp, then sourcePriority
                    val better = when {
                        hadExport > bestHasExport -> true
                        hadExport < bestHasExport -> false
                        readingCount > bestReadingCount -> true
                        readingCount < bestReadingCount -> false
                        timestamp > bestTimestamp -> true
                        timestamp < bestTimestamp -> false
                        sourcePriority > bestSourcePriority -> true
                        else -> false
                    }

                    if (better) {
                        bestHasExport = hadExport
                        bestReadingCount = readingCount
                        bestTimestamp = timestamp
                        bestSourcePriority = sourcePriority
                        bestPair = Pair(file, uri)
                    }
                } catch (_: Exception) {
                    // parsing failed for this candidate - skip
                }
            } catch (_: SecurityException) {
                // permission problem reading this candidate: skip but continue
            } catch (_: Exception) {
                // ignore io failures for this candidate
            }
        }

        if (bestPair.first != null || bestPair.second != null) {
            val chosenId = bestPair.second?.toString() ?: bestPair.first?.absolutePath ?: "(none)"
            Log.i(TAG, "Selected latest backup: $chosenId (hadExport=$bestHasExport, readings=$bestReadingCount, timestamp=$bestTimestamp, src=$bestSourcePriority)")
        } else {
            Log.i(TAG, "No backup candidates found by findLatestBackup()")
        }

        return@withContext bestPair
    }

    // Always write to a single canonical backup filename so there is exactly one overwriteable backup file.
    // The includeAutoBackup parameter is kept for backwards compatibility but ignored.
    suspend fun createBackup(includeAutoBackup: Boolean = true): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Prepare backup data
            val readingsList = try {
                repository.getAllReadingsForBackup()
            } catch (_: Exception) {
                emptyList()
            }

            val backupReadings = readingsList.map { ReadingBackup.fromReading(it) }
            val backup = AppBackup(
                exportDate = System.currentTimeMillis(),
                appVersion = "1.0.0",
                readings = backupReadings
            )

            val jsonString = json.encodeToString(backup)

            // Use a single canonical filename for all backups so manual and automatic backups overwrite the same file.
            val fileNameToUse = backupFileName

            // Try to write to a single authoritative location. Do NOT create multiple copies.
            // 1) Try to write to public Downloads (MediaStore on Q+, public folder on older devices)
            val writtenPublicUri: Uri? = try {
                writeStringToPublicDownloads(fileNameToUse, jsonString)
            } catch (e: SecurityException) {
                Log.e(TAG, "Permission denied while writing public backup: ${e.message}")
                null
            }

            if (writtenPublicUri != null) {
                // Optionally clean duplicate MediaStore entries for the same filename, keep only the uri we wrote
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        val uris = findAllMediaStoreUrisByName(fileNameToUse)
                        uris.forEach { candidate ->
                            try {
                                if (candidate != writtenPublicUri) {
                                    context.contentResolver.delete(candidate, null, null)
                                }
                            } catch (_: Exception) {
                                // ignore individual delete failures
                            }
                        }
                    } catch (_: Exception) {
                        // ignore
                    }
                }

                return@withContext Result.success("Backup created with ${backupReadings.size} readings in public Downloads")
            }

            // 2) Try to write to app-specific external folder (fallback)
            try {
                appExternalFolder?.let { folder ->
                    if (!folder.exists()) folder.mkdirs()
                    val dest = File(folder, fileNameToUse)
                    dest.writeText(jsonString)
                    return@withContext Result.success("Backup created with ${backupReadings.size} readings at: ${dest.absolutePath} (note: saved to app storage, may be removed on uninstall)")
                }
            } catch (_: Exception) {
                // ignore fallback write failures
            }

            // 3) Fallback to cache
            try {
                if (!cacheFolder.exists()) cacheFolder.mkdirs()
                val cacheFile = File(cacheFolder, fileNameToUse)
                cacheFile.writeText(jsonString)
                return@withContext Result.success("Backup created with ${backupReadings.size} readings at: ${cacheFile.absolutePath} (note: saved to cache)")
            } catch (_: Exception) {
                // ignore
            }

            return@withContext Result.failure(Exception("Failed to write backup to any location"))
        } catch (e: Exception) {
            Result.failure(Exception("Backup failed: ${e.message}", e))
        }
    }

    suspend fun restoreFromBackup(filePath: String? = null, clearExisting: Boolean = false): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val (file, uri) = if (filePath != null) {
                Pair(File(filePath), null)
            } else {
                // Pick the latest backup across all candidate locations
                findLatestBackup()
            }

            val jsonString: String = when {
                uri != null -> {
                    // Read from MediaStore
                    val input: InputStream = try {
                        context.contentResolver.openInputStream(uri)
                            ?: return@withContext Result.failure(Exception("Unable to open backup input stream"))
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Permission denied while opening backup Uri: ${e.message}")
                        return@withContext Result.failure(Exception("Permission denied while accessing backup Uri: ${e.message}", e))
                    }
                    input.bufferedReader().use { it.readText() }
                }
                file != null -> {
                    if (!file.exists()) return@withContext Result.failure(Exception("Backup file not found at ${file.absolutePath}"))
                    try {
                        file.readText()
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Permission denied while reading backup file: ${e.message}")
                        return@withContext Result.failure(Exception("Permission denied while reading backup file: ${e.message}", e))
                    }
                }
                else -> return@withContext Result.failure(Exception("No backup file found in any location"))
            }

            val backup = parseAppBackupWithInfo(jsonString).backup

            var restoredCount = 0

            // If requested, clear existing readings first so the backup becomes the sole source of truth
            if (clearExisting) {
                try {
                    repository.clearAllReadings()
                } catch (_: Exception) {
                    // Continue anyway; insertion will still try and dedupe
                }
            }

            backup.readings.forEach { backupReading ->
                try {
                    // If we've cleared existing readings above, it's fine to insert directly; otherwise rely on dedupe
                    val inserted = if (clearExisting) {
                        try {
                            repository.insertReading(backupReading.toReading())
                            true
                        } catch (_: Exception) { false }
                    } else {
                        repository.insertReadingIfNotExists(backupReading.toReading())
                    }
                    if (inserted) restoredCount++
                } catch (_: Exception) {
                    // continue
                }
            }

            Result.success(restoredCount)
        } catch (e: SecurityException) {
            Log.e(TAG, "Restore failed due to permission error: ${e.message}")
            Result.failure(Exception("Restore failed: permission denied - ${e.message}", e))
        } catch (e: Exception) {
            Result.failure(Exception("Restore failed: ${e.message}", e))
        }
    }

    suspend fun restoreFromUri(uri: Uri, clearExisting: Boolean = false): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val input: InputStream = try {
                context.contentResolver.openInputStream(uri)
                    ?: return@withContext Result.failure(Exception("Unable to open backup input stream from Uri"))
            } catch (e: SecurityException) {
                Log.e(TAG, "Permission denied while opening backup Uri: ${e.message}")
                return@withContext Result.failure(Exception("Permission denied while accessing backup Uri: ${e.message}", e))
            }

            val jsonString = input.bufferedReader().use { it.readText() }
            val backup = parseAppBackupWithInfo(jsonString).backup

            var restoredCount = 0

            if (clearExisting) {
                try {
                    repository.clearAllReadings()
                } catch (_: Exception) {
                    // ignore
                }
            }

            backup.readings.forEach { backupReading ->
                try {
                    val inserted = if (clearExisting) {
                        try {
                            repository.insertReading(backupReading.toReading())
                            true
                        } catch (_: Exception) { false }
                    } else {
                        repository.insertReadingIfNotExists(backupReading.toReading())
                    }
                    if (inserted) restoredCount++
                } catch (_: Exception) {
                    // continue on insertion errors
                }
            }

            Result.success(restoredCount)
        } catch (e: SecurityException) {
            Log.e(TAG, "Restore from Uri failed due to permission error: ${e.message}")
            Result.failure(Exception("Restore failed: permission denied - ${e.message}", e))
        } catch (e: Exception) {
            Result.failure(Exception("Restore failed from Uri: ${e.message}", e))
        }
    }

    suspend fun hasBackupAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val (file, uri) = findLatestBackup()
            return@withContext file != null || uri != null
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied while checking for backup: ${e.message}")
            // Instead of throwing, return false - if we can't check due to permissions,
            // we should assume no accessible backup exists
            return@withContext false
        } catch (_: Exception) {
            return@withContext false
        }
    }

    suspend fun getBackupInfo(): BackupInfo? = withContext(Dispatchers.IO) {
        try {
            val (file, uri) = findLatestBackup()
            val jsonString: String? = when {
                uri != null -> {
                    try {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Permission denied while reading backup Uri for info: ${e.message}")
                        return@withContext null
                    }
                }
                file != null -> {
                    if (file.exists()) {
                        try {
                            file.readText()
                        } catch (e: SecurityException) {
                            Log.e(TAG, "Permission denied while reading backup file for info: ${e.message}")
                            return@withContext null
                        }
                    } else null
                }
                else -> null
            }

            if (jsonString == null) return@withContext null
            val parseResult = parseAppBackupWithInfo(jsonString)
            val path = uri?.toString() ?: file?.absolutePath ?: ""
            // If the JSON had an explicit exportDate > 0 use it; otherwise fall back to file/MediaStore metadata or current time.
            val dateMillis = when {
                parseResult.hadExportDate && parseResult.backup.exportDate > 0L -> parseResult.backup.exportDate
                file != null -> try { val lm = file.lastModified(); if (lm > 0L) lm else System.currentTimeMillis() } catch (_: Exception) { System.currentTimeMillis() }
                uri != null -> queryMediaStoreDate(uri) ?: System.currentTimeMillis()
                else -> System.currentTimeMillis()
            }

            return@withContext BackupInfo(
                date = Date(dateMillis),
                readingCount = parseResult.backup.readings.size,
                filePath = path
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied while getting backup info: ${e.message}")
            null
        } catch (_: Exception) {
            null
        }
    }

    suspend fun deleteBackup(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            var deletedAny = false

            // 1) MediaStore (API Q+)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val resolver = context.contentResolver
                    val autoUri = findMediaStoreUriByName(autoBackupFileName)
                    val manualUri = findMediaStoreUriByName(backupFileName)
                    try {
                        if (autoUri != null) {
                            resolver.delete(autoUri, null, null)
                            deletedAny = true
                        }
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Permission denied while deleting backup Uri: ${e.message}")
                        throw e
                    } catch (_: Exception) {
                        // ignore individual delete failures
                    }

                    try {
                        if (manualUri != null) {
                            resolver.delete(manualUri, null, null)
                            deletedAny = true
                        }
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Permission denied while deleting backup Uri: ${e.message}")
                        throw e
                    } catch (_: Exception) {
                        // ignore
                    }
                }
            } catch (e: SecurityException) {
                throw e
            } catch (_: Exception) {
                // continue to filesystem fallbacks
            }

            // 2) Public Downloads folder
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                try {
                    val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val folder = File(downloads, backupFolderName)
                    val autoFile = File(folder, autoBackupFileName)
                    val manualFile = File(folder, backupFileName)
                    if (autoFile.exists() && autoFile.delete()) deletedAny = true
                    if (manualFile.exists() && manualFile.delete()) deletedAny = true
                } catch (e: SecurityException) {
                    Log.e(TAG, "Permission denied while deleting public Downloads backup: ${e.message}")
                    throw e
                } catch (_: Exception) {
                    // ignore
                }
            }

            // 3) App-specific external folder
            try {
                appExternalFolder?.let { folder ->
                    val autoFile = File(folder, autoBackupFileName)
                    val manualFile = File(folder, backupFileName)
                    if (autoFile.exists() && autoFile.delete()) deletedAny = true
                    if (manualFile.exists() && manualFile.delete()) deletedAny = true
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Permission denied while deleting app external backup: ${e.message}")
                throw e
            } catch (_: Exception) {
                // ignore
            }

            // 4) Cache folder
            try {
                val autoFile = File(cacheFolder, autoBackupFileName)
                val manualFile = File(cacheFolder, backupFileName)
                if (autoFile.exists() && autoFile.delete()) deletedAny = true
                if (manualFile.exists() && manualFile.delete()) deletedAny = true
            } catch (_: Exception) {
                // ignore
            }

            Result.success(deletedAny)
        } catch (e: SecurityException) {
            Log.e(TAG, "Delete backup failed due to permission error: ${e.message}")
            Result.failure(Exception("Permission denied while deleting backup: ${e.message}", e))
        } catch (e: Exception) {
            Log.e(TAG, "Delete backup failed: ${e.message}")
            Result.failure(Exception("Failed to delete backup: ${e.message}", e))
        }
    }

    // Clean up old backups - DISABLED. This app now keeps a single backup (overwrite behavior) so cleanup isn't required.
    suspend fun cleanupOldBackups(): Result<Int> = withContext(Dispatchers.IO) {
        Log.i(TAG, "cleanupOldBackups() is disabled: app now maintains a single backup and overwrites it.")
        return@withContext Result.success(0)
    }

    private fun parseAppBackupWithInfo(jsonString: String): AppBackupParseResult {
        try {
            // First try to parse as a complete AppBackup
            val backup = json.decodeFromString<AppBackup>(jsonString)
            return AppBackupParseResult(backup = backup, hadExportDate = true)
        } catch (_: Exception) {
            // If that fails, try parsing as a generic JSON to check what fields are present
            try {
                // Parse as a map to check if exportDate field exists
                val jsonMap = json.parseToJsonElement(jsonString).jsonObject
                val hasExportDate = jsonMap.containsKey("exportDate")

                // Create a backup with default exportDate if missing
                val backup = if (hasExportDate) {
                    json.decodeFromString<AppBackup>(jsonString)
                } else {
                    // Create a backup with default exportDate
                    AppBackup(exportDate = 0L, appVersion = "unknown", readings = emptyList())
                }

                return AppBackupParseResult(backup = backup, hadExportDate = hasExportDate)
            } catch (_: Exception) {
                // As a last resort, create an empty backup
                return AppBackupParseResult(
                    backup = AppBackup(exportDate = 0L, appVersion = "unknown", readings = emptyList()),
                    hadExportDate = false
                )
            }
        }
    }

    private fun queryMediaStoreDate(uri: Uri): Long? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        try {
            val resolver = context.contentResolver
            val projection = arrayOf(MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DATE_MODIFIED)
            resolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val dateAddedIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                    val dateModifiedIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)

                    val dateAdded = if (dateAddedIndex >= 0) cursor.getLong(dateAddedIndex) * 1000 else 0L
                    val dateModified = if (dateModifiedIndex >= 0) cursor.getLong(dateModifiedIndex) * 1000 else 0L

                    return maxOf(dateAdded, dateModified).takeIf { it > 0L }
                }
            }
        } catch (_: Exception) {
            // ignore
        }
        return null
    }

    @Suppress("UNUSED_PARAMETER")
    private suspend fun deleteOtherBackupCandidates(
        fileNameToUse: String,
        keepFilePaths: Set<String> = emptySet(),
        keepUriStrings: Set<String> = emptySet(),
        maxToKeep: Int = 5
    ) = withContext(Dispatchers.IO) {
        try {
            val candidates = findBackupCandidates()
            val candidatesWithInfo = mutableListOf<Triple<Pair<File?, Uri?>, Long, Int>>()

            // Collect info about each candidate
            candidates.forEach { candidate ->
                try {
                    val (file, uri) = candidate

                    // Skip if this candidate is in the keep sets
                    if ((file != null && keepFilePaths.contains(file.absolutePath)) ||
                        (uri != null && keepUriStrings.contains(uri.toString()))) {
                        return@forEach
                    }

                    val jsonString = when {
                        uri != null -> context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        file != null -> file.readText()
                        else -> null
                    } ?: return@forEach

                    val parseResult = parseAppBackupWithInfo(jsonString)
                    val timestamp = if (parseResult.hadExportDate && parseResult.backup.exportDate > 0L) {
                        parseResult.backup.exportDate
                    } else {
                        // fallback to file metadata
                        when {
                            file != null -> file.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
                            uri != null -> queryMediaStoreDate(uri) ?: System.currentTimeMillis()
                            else -> System.currentTimeMillis()
                        }
                    }

                    candidatesWithInfo.add(Triple(candidate, timestamp, parseResult.backup.readings.size))
                } catch (_: Exception) {
                    // ignore candidates we can't process
                }
            }

            // Sort by timestamp descending (newest first) and keep only the newest maxToKeep
            val sorted = candidatesWithInfo.sortedByDescending { it.second }
            val toDelete = if (sorted.size > maxToKeep) sorted.drop(maxToKeep) else emptyList()

            // Delete the excess candidates
            toDelete.forEach { (candidate, _, _) ->
                try {
                    val (file, uri) = candidate
                    when {
                        uri != null -> {
                            context.contentResolver.delete(uri, null, null)
                        }
                        file != null -> {
                            file.delete()
                        }
                    }
                } catch (_: Exception) {
                    // ignore delete failures
                }
            }
        } catch (_: Exception) {
            // ignore cleanup failures
        }
    }
}

data class BackupInfo(
    val date: Date,
    val readingCount: Int,
    val filePath: String
)

data class AppBackupParseResult(
    val backup: AppBackup,
    val hadExportDate: Boolean
)
