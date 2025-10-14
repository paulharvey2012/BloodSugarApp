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

    private suspend fun writeStringToPublicDownloads(fileName: String, content: String): Boolean = withContext(Dispatchers.IO) {
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
                    } ?: return@withContext false
                    return@withContext true
                }

                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/$backupFolderName")
                }

                val uri = resolver.insert(collection, values) ?: return@withContext false
                resolver.openOutputStream(uri)?.use { out ->
                    out.write(content.toByteArray())
                } ?: return@withContext false

                return@withContext true
            } else {
                // Pre-Q: write directly to public Download folder (requires WRITE_EXTERNAL_STORAGE on API<=28)
                val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val folder = File(downloads, backupFolderName)
                if (!folder.exists()) folder.mkdirs()
                val file = File(folder, fileName)
                file.writeText(content)
                return@withContext true
            }
        } catch (e: SecurityException) {
            // Permission denied - rethrow so callers can handle and surface helpful UI
            Log.e(TAG, "Permission denied while writing to public Downloads: ${e.message}")
            throw e
        } catch (_: Exception) {
            // Other IO failures - return false so caller will use fallbacks
            Log.w(TAG, "Failed to write to public Downloads")
            return@withContext false
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

            val fileNameToUse = if (includeAutoBackup) autoBackupFileName else backupFileName

            // Track newly-created locations so cleanup won't remove them
            val keepFilePaths = mutableSetOf<String>()
            val keepUriStrings = mutableSetOf<String>()

            // 1) Try to write to public Downloads (MediaStore on Q+, public folder on older devices)
            val wrotePublic = try {
                writeStringToPublicDownloads(fileNameToUse, jsonString)
            } catch (e: SecurityException) {
                Log.e(TAG, "Permission denied while writing public backup: ${e.message}")
                return@withContext Result.failure(Exception("Permission denied while writing public backup: ${e.message}", e))
            }

            // 2) Also write fallback copies to app-specific external and cache so the app can access them
            try {
                appExternalFolder?.let { folder ->
                    if (!folder.exists()) folder.mkdirs()
                    val dest = File(folder, fileNameToUse)
                    dest.writeText(jsonString)
                    keepFilePaths.add(dest.absolutePath)
                }
            } catch (_: Exception) {
                // ignore fallback write failures
            }

            // 3) If we wrote to MediaStore, discover the newest matching URI and keep it
            if (wrotePublic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val uris = findAllMediaStoreUrisByName(fileNameToUse)
                    if (uris.isNotEmpty()) {
                        val best = uris.maxByOrNull { queryMediaStoreDate(it) ?: 0L }
                        best?.let { keepUriStrings.add(it.toString()) }
                    }
                } catch (_: Exception) {
                    // ignore
                }
            }

            // 4) If we didn't write public (or even if we did), also write to cache so there is a local copy
            try {
                if (!cacheFolder.exists()) cacheFolder.mkdirs()
                val cacheFile = File(cacheFolder, fileNameToUse)
                cacheFile.writeText(jsonString)
                keepFilePaths.add(cacheFile.absolutePath)
            } catch (_: Exception) {
                // ignore
            }

            // 5) Cleanup: delete other backup candidates that aren't part of keep sets
            try {
                deleteOtherBackupCandidates(fileNameToUse, keepFilePaths = keepFilePaths, keepUriStrings = keepUriStrings)
            } catch (_: Exception) {
                // ignore cleanup failures
            }

            // 6) Return result indicating where backup was placed
            if (wrotePublic) {
                return@withContext Result.success("Backup created with ${backupReadings.size} readings in public Downloads")
            }

            // If public write failed, try returning the app-specific or cache path if available
            val fallbackPath = keepFilePaths.firstOrNull()
            if (fallbackPath != null) {
                return@withContext Result.success("Backup created with ${backupReadings.size} readings at: $fallbackPath (note: saved to app storage, may be removed on uninstall)")
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
            throw e
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

    // Improve JSON parsing robustness:
    // - Use a lenient Json configuration that ignores unknown keys.
    // - Normalize the string by trimming and removing a possible BOM (\uFEFF).
    // - If top-level AppBackup parsing fails, try parsing an array of ReadingBackup (older backup format).
    private val robustJson = Json { prettyPrint = true; ignoreUnknownKeys = true; isLenient = true }

    private fun normalizeJsonString(input: String): String {
        var s = input.trim()
        if (s.isNotEmpty() && s[0] == '\uFEFF') s = s.substring(1)
        return s
    }

    private data class ParseResult(val backup: AppBackup, val hadExportDate: Boolean)

    // Parse JSON and indicate whether the JSON included exportDate.
    private fun parseAppBackupWithInfo(jsonString: String): ParseResult {
        val normalized = normalizeJsonString(jsonString)
        try {
            val ab = robustJson.decodeFromString<AppBackup>(normalized)
            return ParseResult(ab, true)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse as AppBackup: ${e.message}")
            // Try fallback: maybe the file is a plain array of ReadingBackup
            try {
                val list = robustJson.decodeFromString<List<ReadingBackup>>(normalized)
                val ab = AppBackup(exportDate = 0L, appVersion = "unknown", readings = list)
                return ParseResult(ab, false)
            } catch (e2: Exception) {
                Log.w(TAG, "Failed to parse as List<ReadingBackup>: ${e2.message}")
                // Attempt to handle concatenated JSON objects (e.g. }{ ) or multiple top-level values
                try {
                    val elements = splitTopLevelJsonElements(normalized)
                    if (elements.size > 1) {
                        val mergedReadings = mutableListOf<ReadingBackup>()
                        elements.forEachIndexed { idx, elem ->
                            // Try parse each element as AppBackup, List<ReadingBackup>, or single ReadingBackup
                            try {
                                val ab = robustJson.decodeFromString<AppBackup>(elem)
                                mergedReadings.addAll(ab.readings)
                                return@forEachIndexed
                            } catch (_: Exception) {}

                            try {
                                val listElem = robustJson.decodeFromString<List<ReadingBackup>>(elem)
                                mergedReadings.addAll(listElem)
                                return@forEachIndexed
                            } catch (_: Exception) {}

                            try {
                                val single = robustJson.decodeFromString<ReadingBackup>(elem)
                                mergedReadings.add(single)
                                return@forEachIndexed
                            } catch (_: Exception) {}
                        }

                        if (mergedReadings.isNotEmpty()) {
                            Log.w(TAG, "Parsed and merged ${mergedReadings.size} readings from ${elements.size} top-level JSON elements")
                            val ab = AppBackup(exportDate = 0L, appVersion = "merged", readings = mergedReadings)
                            return ParseResult(ab, false)
                        }
                    }
                } catch (splitEx: Exception) {
                    Log.w(TAG, "Failed to split/parse multiple JSON elements: ${splitEx.message}")
                }

                // Log full details and re-throw the original error to be handled by caller
                Log.e(TAG, "JSON parsing failed for backup content. Original error: ${e.message}", e)
                throw e
            }
        }
    }

    // Split input into top-level JSON elements (objects or arrays). Handles strings and escapes.
    private fun splitTopLevelJsonElements(input: String): List<String> {
        val res = mutableListOf<String>()
        var inString = false
        var escaped = false
        var depth = 0
        var start = -1

        for (i in input.indices) {
            val ch = input[i]

            if (ch == '"' && !escaped) {
                inString = !inString
            }

            if (!inString) {
                if (ch == '{' || ch == '[') {
                    if (depth == 0) start = i
                    depth++
                } else if (ch == '}' || ch == ']') {
                    depth--
                    if (depth == 0 && start >= 0) {
                        res.add(input.substring(start, i + 1))
                        start = -1
                    }
                }
            }

            // handle escape state
            if (ch == '\\' && !escaped) {
                escaped = true
            } else {
                escaped = false
            }
        }

        // If nothing was detected as top-level objects/arrays, but trimmed input non-empty, return the whole input
        if (res.isEmpty()) {
            val trimmed = input.trim()
            if (trimmed.isNotEmpty()) return listOf(trimmed)
        }

        return res
    }

    // Query MediaStore for a timestamp (DATE_MODIFIED or DATE_ADDED) for a given Uri. Returns milliseconds since epoch or null.
    private fun queryMediaStoreDate(uri: Uri): Long? {
        return try {
            val resolver = context.contentResolver
            val projection = arrayOf(MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.DATE_ADDED)
            resolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val modIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                    if (modIdx >= 0) {
                        val modified = cursor.getLong(modIdx)
                        // DATE_MODIFIED is seconds since epoch on some providers
                        val ms = if (modified < 1e12) modified * 1000 else modified
                        return ms
                    }
                    val addIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                    if (addIdx >= 0) {
                        val added = cursor.getLong(addIdx)
                        val ms = if (added < 1e12) added * 1000 else added
                        return ms
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    // Debug-friendly summary of a candidate backup file/uri
    data class BackupCandidateInfo(
        val filePath: String?,          // filesystem path if applicable
        val uriString: String?,         // MediaStore uri string if applicable
        val readingCount: Int?,         // number of readings parsed (null if parse failed)
        val hadExportDate: Boolean,     // whether the JSON included an exportDate
        val exportDateMillis: Long?,    // exportDate if present
        val sourcePriority: Int,        // source priority used in selection
        val parseError: String?         // parse error message if any
    )

    // Collect detailed info about all found backup candidates for debugging/inspection.
    // This returns a list of BackupCandidateInfo describing each candidate location.
    suspend fun listBackupCandidates(): List<BackupCandidateInfo> = withContext(Dispatchers.IO) {
        val result = mutableListOf<BackupCandidateInfo>()
        val candidates = findBackupCandidates()

        // Capture folder paths for sourcePriority resolution
        val appExtPath: String? = try { appExternalFolder?.absolutePath } catch (_: Exception) { null }
        val cachePath: String = try { cacheFolder.absolutePath } catch (_: Exception) { "" }

        candidates.forEach { (file, uri) ->
            var filePath: String? = null
            var uriString: String? = null
            var readingCount: Int? = null
            var hadExport = false
            var exportDate: Long? = null
            var parseError: String? = null
            val sourcePriority = when {
                uri != null -> 4
                file != null && appExtPath != null && file.absolutePath.startsWith(appExtPath) -> 2
                file != null && cachePath.isNotEmpty() && file.absolutePath.startsWith(cachePath) -> 1
                file != null -> 3
                else -> 0
            }

            try {
                val jsonString = when {
                    uri != null -> {
                        uriString = uri.toString()
                        try {
                            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        } catch (e: SecurityException) {
                            throw e
                        }
                    }
                    file != null -> {
                        filePath = file.absolutePath
                        if (!file.exists()) throw Exception("file not found")
                        file.readText()
                    }
                    else -> null
                } ?: run {
                    parseError = "Unable to read candidate"
                    result.add(BackupCandidateInfo(filePath, uriString, null, false, null, sourcePriority, parseError))
                    return@forEach
                }

                try {
                    val parseResult = parseAppBackupWithInfo(jsonString)
                    readingCount = parseResult.backup.readings.size
                    hadExport = parseResult.hadExportDate && parseResult.backup.exportDate > 0L
                    if (hadExport) exportDate = parseResult.backup.exportDate
                } catch (e: Exception) {
                    parseError = e.message ?: "parse error"
                }
            } catch (se: SecurityException) {
                parseError = "permission denied: ${se.message}"
            } catch (e: Exception) {
                if (parseError == null) parseError = e.message ?: "io error"
            }

            result.add(
                BackupCandidateInfo(
                    filePath = filePath,
                    uriString = uriString,
                    readingCount = readingCount,
                    hadExportDate = hadExport,
                    exportDateMillis = exportDate,
                    sourcePriority = sourcePriority,
                    parseError = parseError
                )
            )
        }

        return@withContext result
    }

    // Delete other backup files/uris matching the given filename that are NOT in the keep sets.
    // This is used after creating a new backup to remove old duplicates while being resilient to
    // permission issues on various Android versions.
    private fun deleteOtherBackupCandidates(
        fileNameToUse: String,
        keepFilePaths: Set<String>,
        keepUriStrings: Set<String>,
        maxToKeep: Int = 3
    ) {
        // Build a unified list of candidates across all locations
        data class Candidate(val file: File?, val uri: Uri?, val id: String, val timestamp: Long)

        val allCandidates = mutableListOf<Candidate>()

        val seenIds = mutableSetOf<String>()

        val candidates = try { findBackupCandidates() } catch (_: Exception) { emptyList<Pair<File?, Uri?>>() }

        candidates.forEachIndexed { idx, (file, uri) ->
            try {
                val id = uri?.toString() ?: file?.absolutePath ?: "(unknown_$idx)"
                if (seenIds.contains(id)) return@forEachIndexed
                seenIds.add(id)

                var ts: Long = Long.MIN_VALUE

                // Prefer exportDate inside JSON if available
                try {
                    val jsonString = when {
                        uri != null -> context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        file != null -> if (file.exists()) file.readText() else null
                        else -> null
                    }
                    if (jsonString != null) {
                        try {
                            val pr = parseAppBackupWithInfo(jsonString)
                            if (pr.hadExportDate && pr.backup.exportDate > 0L) ts = pr.backup.exportDate
                        } catch (_: Exception) {
                            // ignore parsing errors and fall back to metadata
                        }
                    }
                } catch (_: SecurityException) {
                    // permission denied reading body -> fall back to metadata
                } catch (_: Exception) {
                    // ignore
                }

                if (ts == Long.MIN_VALUE) {
                    try {
                        if (file != null) {
                            val lm = file.lastModified()
                            if (lm > 0) ts = lm
                        }
                    } catch (_: Exception) {}

                    try {
                        if (uri != null) {
                            val ms = queryMediaStoreDate(uri)
                            if (ms != null && ms > ts) ts = ms
                        }
                    } catch (_: Exception) {}
                }

                // If still unknown, set to minimum so it sorts last
                if (ts == Long.MIN_VALUE) ts = Long.MIN_VALUE

                allCandidates.add(Candidate(file, uri, id, ts))
            } catch (_: Exception) {
                // continue
            }
        }

        // Partition according to keep sets provided (these are absolute file paths or Uri strings)
        val mustKeep = allCandidates.filter { c -> keepFilePaths.contains(c.id) || keepUriStrings.contains(c.id) }
        val others = allCandidates.filter { c -> !mustKeep.contains(c) }

        // Decide how many of the others we should keep so total kept <= maxToKeep
        val remainingSlots = (maxToKeep - mustKeep.size).coerceAtLeast(0)

        // Sort others by timestamp desc and keep top 'remainingSlots'
        val sortedOthers = others.sortedWith(compareByDescending<Candidate> { it.timestamp }.thenBy { it.id })
        val toKeepFromOthers = sortedOthers.take(remainingSlots).map { it.id }.toSet()

        // All candidates to delete are those not in mustKeep and not in toKeepFromOthers
        val toDelete = allCandidates.filter { c -> !mustKeep.any { it.id == c.id } && !toKeepFromOthers.contains(c.id) }

        // Perform deletions
        toDelete.forEach { c ->
            try {
                if (c.uri != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        try {
                            context.contentResolver.delete(c.uri, null, null)
                            Log.d(TAG, "Deleted MediaStore backup Uri: ${c.id}")
                        } catch (se: SecurityException) {
                            Log.e(TAG, "Permission denied deleting MediaStore Uri ${c.id}: ${se.message}")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to delete MediaStore Uri ${c.id}: ${e.message}")
                        }
                    }
                } else if (c.file != null) {
                    try {
                        if (c.file.exists() && c.file.delete()) {
                            Log.d(TAG, "Deleted backup file: ${c.id}")
                        }
                    } catch (se: SecurityException) {
                        Log.e(TAG, "Permission denied deleting file ${c.id}: ${se.message}")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to delete file ${c.id}: ${e.message}")
                    }
                }
            } catch (_: Exception) {
                // ignore individual failures
            }
        }
    }

}

data class BackupInfo(
    val date: Date,
    val readingCount: Int,
    val filePath: String
)
