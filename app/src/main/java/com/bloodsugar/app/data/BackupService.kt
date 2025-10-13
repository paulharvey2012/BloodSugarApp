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

    // Search for a backup either in MediaStore (public Downloads) or filesystem fallbacks
    private fun findBackupSource(): Pair<File?, Uri?> {
        // 1) Look in MediaStore (modern, persistent across uninstall)
        try {
            val mediaUri = findMediaStoreUriByName(autoBackupFileName) ?: findMediaStoreUriByName(backupFileName)
            if (mediaUri != null) return Pair(null, mediaUri)
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied while querying MediaStore: ${e.message}")
            throw e
        } catch (_: Exception) {
            Log.w(TAG, "Failed while querying MediaStore")
            // continue to other fallbacks
        }

        // 2) Look in public Downloads folder (pre-Q filesystem)
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
        // Selection tuple: (hasExportDate (1/0), timestampMillis, sourcePriority)
        // sourcePriority: 4=MediaStore(Uri),3=public Downloads,2=appExternal,1=cache
        var bestPair: Pair<File?, Uri?> = Pair(null, null)
        var bestHasExport = -1
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
                    Log.d(TAG, "Backup candidate: $candidateId -> hadExport=$hadExport, timestamp=$timestamp, src=$sourcePriority")

                    // Compare tuple (hadExport, timestamp, sourcePriority)
                    val better = when {
                        hadExport > bestHasExport -> true
                        hadExport < bestHasExport -> false
                        timestamp > bestTimestamp -> true
                        timestamp < bestTimestamp -> false
                        sourcePriority > bestSourcePriority -> true
                        else -> false
                    }

                    if (better) {
                        bestHasExport = hadExport
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
            Log.i(TAG, "Selected latest backup: $chosenId (hadExport=$bestHasExport, timestamp=$bestTimestamp, src=$bestSourcePriority)")
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

            // Try to write to public Downloads (MediaStore or public folder)
            val wrotePublic = try {
                writeStringToPublicDownloads(if (includeAutoBackup) autoBackupFileName else backupFileName, jsonString)
            } catch (e: SecurityException) {
                // Let caller know this was a permission issue
                Log.e(TAG, "Permission denied while writing public backup: ${e.message}")
                return@withContext Result.failure(Exception("Permission denied while writing public backup: ${e.message}", e))
            }

            // Also write fallback copies to app-specific external and cache so the app can access them
            try {
                appExternalFolder?.let { folder ->
                    if (!folder.exists()) folder.mkdirs()
                    val manualFile = File(folder, backupFileName)
                    manualFile.writeText(jsonString)
                    if (includeAutoBackup) {
                        val autoFile = File(folder, autoBackupFileName)
                        autoFile.writeText(jsonString)
                    }
                }
            } catch (_: Exception) {
                // ignore fallback write failures
            }

            if (wrotePublic) {
                return@withContext Result.success("Backup created with ${backupReadings.size} readings in public Downloads")
            }

            // If public write failed, try writing to app-specific external or cache
            try {
                val destFolder = appExternalFolder ?: cacheFolder
                if (!destFolder.exists()) destFolder.mkdirs()
                val destFile = File(destFolder, if (includeAutoBackup) autoBackupFileName else backupFileName)
                destFile.writeText(jsonString)
                return@withContext Result.success("Backup created with ${backupReadings.size} readings at: ${destFile.absolutePath} (note: saved to app storage, may be removed on uninstall)")
            } catch (e: Exception) {
                return@withContext Result.failure(Exception("Failed to write backup: ${e.message}", e))
            }
        } catch (e: Exception) {
            Result.failure(Exception("Backup failed: ${e.message}", e))
        }
    }

    suspend fun restoreFromBackup(filePath: String? = null): Result<Int> = withContext(Dispatchers.IO) {
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
            backup.readings.forEach { backupReading ->
                try {
                    val inserted = repository.insertReadingIfNotExists(backupReading.toReading())
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

    suspend fun restoreFromUri(uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
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
            backup.readings.forEach { backupReading ->
                try {
                    val inserted = repository.insertReadingIfNotExists(backupReading.toReading())
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

            // 2) Public Downloads folder (pre-Q)
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
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query MediaStore date for $uri: ${e.message}")
            null
        }
    }
}

data class BackupInfo(
    val date: Date,
    val readingCount: Int,
    val filePath: String
)
