package com.bloodsugar.app.ui

import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.bloodsugar.app.data.Reading
import com.bloodsugar.app.data.ReadingRepository
import com.bloodsugar.app.data.UnitPreferences
import com.bloodsugar.app.data.BackupService
import com.bloodsugar.app.data.BackupInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.util.Date

class ReadingViewModel(
    private val repository: ReadingRepository,
    private val unitPreferences: UnitPreferences? = null,
    private val backupService: BackupService? = null,
    // When true, the ViewModel will attempt auto-restore during initialization if DB is empty.
    autoRestoreOnInit: Boolean = true
) : ViewModel() {

    val allReadings: Flow<List<Reading>> = repository.getAllReadings()

    private val _uiState = MutableStateFlow(ReadingUiState())
    @Suppress("unused")
    val uiState: StateFlow<ReadingUiState> = _uiState

    // Unit preference: in-memory default, but if UnitPreferences is provided it will be kept in sync
    private val _unit = MutableStateFlow(UnitPreferences.DEFAULT_UNIT)
    val unit: StateFlow<String> = _unit

    // Backup/Restore states
    private val _backupState = MutableStateFlow(BackupUiState())
    val backupState: StateFlow<BackupUiState> = _backupState

    // Debug candidate list exposed to UI for one-tap restore testing
    private val _backupCandidates = MutableStateFlow<List<String>>(emptyList())
    val backupCandidates: StateFlow<List<String>> = _backupCandidates

    init {
        unitPreferences?.let { prefs ->
            viewModelScope.launch {
                prefs.unitFlow.collect { value ->
                    _unit.value = value
                }
            }
        }

        // Always check for existing backups on startup
        checkForExistingBackups()

        // Only attempt auto-restore during initialization when explicitly allowed (first-run)
        if (autoRestoreOnInit) {
            autoRestoreIfNeeded()
        }
    }


    private fun checkForExistingBackups() {
        viewModelScope.launch {
            backupService?.let { service ->
                try {
                    val hasBackup = service.hasBackupAvailable()
                    val backupInfo = if (hasBackup) service.getBackupInfo() else null
                    _backupState.value = _backupState.value.copy(
                        hasBackupAvailable = hasBackup,
                        lastBackupInfo = backupInfo
                    )
                } catch (_: Exception) {
                    // If any error occurs during backup checking, simply set no backup available
                    // This prevents error messages on fresh installs
                    _backupState.value = _backupState.value.copy(
                        hasBackupAvailable = false,
                        lastBackupInfo = null
                    )
                }
            }
        }
    }

    private fun autoRestoreIfNeeded() {
        viewModelScope.launch {
            try {
                // Check if database is empty by counting readings
                var shouldRestore = false
                allReadings.firstOrNull()?.let { readings ->
                    shouldRestore = readings.isEmpty()
                }

                if (shouldRestore) {
                    // Database is empty, check if there are any backup files available before attempting restore
                    backupService?.let { service ->
                        try {
                            // First check if any backup is actually available - this now returns false for permission issues
                            val hasBackup = service.hasBackupAvailable()
                            if (!hasBackup) {
                                // No backup files exist - attempt a filesystem-focused fallback using debug candidate info.
                                // This helps with cases where MediaStore checks return false due to scoped storage but
                                // a readable file may still exist in Downloads/BloodSugarApp.
                                try {
                                    val tried = tryAutoImportFromDebugCandidates(service)
                                    if (!tried) return@let
                                } catch (_: Exception) {
                                    // Fall through silently if fallback fails
                                    return@let
                                }
                            }

                            // Only proceed if we confirmed backups exist
                            val backupInfo = try {
                                service.getBackupInfo()
                            } catch (_: Exception) {
                                // If we can't get backup info even though hasBackup was true,
                                // this suggests permission issues, so exit silently
                                return@let
                            }

                            if (backupInfo != null && backupInfo.readingCount > 0) {
                                // If the best candidate is a MediaStore Uri (content://) we should avoid
                                // attempting an automatic restore here because some devices/providers
                                // may deny read access without an explicit user-granted Uri permission.
                                // In that case, prompt the user to import the backup manually instead
                                // (the Import flow will persist permission).
                                if (backupInfo.filePath.startsWith("content://")) {
                                    _backupState.value = _backupState.value.copy(
                                        error = "Auto-restore skipped: backup located in shared storage (content URI). Please import the backup file manually."
                                    )
                                } else {
                                    try {
                                        // Important: on first-run auto-restore we clear the existing DB so the backup becomes the full history.
                                        val result = service.restoreFromBackup(clearExisting = true)
                                        result.onSuccess { count ->
                                            if (count > 0) {
                                                val pathSnippet = when {
                                                    backupInfo.filePath.isNotBlank() -> backupInfo.filePath
                                                    else -> "(unknown location)"
                                                }

                                                val msg = if (backupInfo.readingCount != count) {
                                                    "Automatically restored $count of ${backupInfo.readingCount} readings from previous installation (source: $pathSnippet)"
                                                } else {
                                                    "Automatically restored $count readings from previous installation (source: $pathSnippet)"
                                                }

                                                _backupState.value = _backupState.value.copy(
                                                    message = msg,
                                                    hasBackupAvailable = true,
                                                    lastRestoreCount = count
                                                )
                                            }
                                        }.onFailure { error ->
                                            // If restore failed due to permissions, surface a clear message
                                            val msg = error.message ?: "Unknown error"
                                            if (msg.contains("Permission denied", ignoreCase = true)) {
                                                _backupState.value = _backupState.value.copy(
                                                    error = "Auto-restore failed due to missing storage permission. Please grant storage permission or import a backup file manually."
                                                )
                                            } else {
                                                _backupState.value = _backupState.value.copy(
                                                    error = "Auto-restore failed: $msg"
                                                )
                                            }
                                        }
                                    } catch (_: Exception) {
                                        // Silently fail - don't disrupt startup
                                    }
                                }
                            }
                            // If backupInfo is null or has 0 readings, silently do nothing
                        } catch (_: Exception) {
                            // Silently fail for any other errors during backup checking
                        }
                    }
                }
            } catch (_: Exception) {
                // Silently fail - don't disrupt app startup
            }
        }
    }

    // Attempt a filesystem-focused automatic import using debug candidate info.
    // Returns true if an import attempt was made (success or failure), false if no suitable filesystem candidate found.
    private suspend fun tryAutoImportFromDebugCandidates(service: BackupService): Boolean {
        return try {
            val list = try { service.debugGetCandidatesInfo() } catch (_: Exception) { null }
            if (list.isNullOrEmpty()) return false

            // Look for the first candidate that looks like a filesystem path (not a content:// URI).
            val filesystemCandidate = list.mapNotNull { entry ->
                val payload = when {
                    entry.startsWith("candidate=") -> entry.substringAfter("candidate=")
                    entry.startsWith("selected_latest=") -> entry.substringAfter("selected_latest=")
                    else -> entry
                }
                // Exclude MediaStore/content URIs
                if (payload.startsWith("content://", ignoreCase = true)) null
                else payload
            }.firstOrNull { it.isNotBlank() }

            if (filesystemCandidate.isNullOrBlank()) return false

            // Attempt to restore from the filesystem path (clear existing DB so the backup becomes the full history)
            try {
                val result = service.restoreFromBackup(filePath = filesystemCandidate, clearExisting = true)
                result.onSuccess { count ->
                    if (count > 0) {
                        _backupState.value = _backupState.value.copy(
                            message = "Automatically restored $count readings from backup file: $filesystemCandidate",
                            hasBackupAvailable = true,
                            lastRestoreCount = count
                        )
                    }
                }.onFailure { error ->
                    val msg = error.message ?: "Unknown error"
                    if (msg.contains("Permission denied", ignoreCase = true)) {
                        _backupState.value = _backupState.value.copy(
                            error = "Auto-import failed due to missing storage permission. Please grant storage permission or import the backup file manually."
                        )
                    } else {
                        _backupState.value = _backupState.value.copy(
                            error = "Auto-import failed: $msg"
                        )
                    }
                }
            } catch (_: SecurityException) {
                _backupState.value = _backupState.value.copy(
                    error = "Auto-import failed due to missing storage permission. Please grant storage permission or import the backup file manually."
                )
            } catch (_: Exception) {
                // ignore other failures
            }

            true
        } catch (_: Exception) {
            false
        }
    }

    fun setUnit(newUnit: String) {
        if (unitPreferences != null) {
            viewModelScope.launch {
                unitPreferences.setUnit(newUnit)
            }
        } else {
            _unit.value = newUnit
        }
    }

    fun addBloodSugarReading(value: Double, date: Date, notes: String, unit: String = "mg/dL") {
        viewModelScope.launch {
            val reading = Reading(
                type = "blood_sugar",
                value = value,
                unit = unit,
                date = date,
                notes = notes
            )
            repository.insertReading(reading)
            // Auto-backup after adding new reading
            createAutoBackup()
        }
    }
    
    fun addKetoneReading(value: Double, date: Date, notes: String, unit: String = "mmol/L") {
        viewModelScope.launch {
            val reading = Reading(
                type = "ketone",
                value = value,
                unit = unit,
                date = date,
                notes = notes
            )
            repository.insertReading(reading)
            // Auto-backup after adding new reading
            createAutoBackup()
        }
    }
    
    fun updateReading(reading: Reading) {
        viewModelScope.launch {
            repository.updateReading(reading)
            // Auto-backup after updating reading
            createAutoBackup()
        }
    }
    
    fun deleteReading(reading: Reading) {
        viewModelScope.launch {
            repository.deleteReading(reading)
            // Auto-backup after deleting reading
            createAutoBackup()
        }
    }

    // Backup/Restore functions
    fun createBackup() {
        viewModelScope.launch {
            _backupState.value = _backupState.value.copy(isLoading = true)

            backupService?.let { service ->
                val result = service.createBackup()
                result.onSuccess { filePath ->
                    _backupState.value = _backupState.value.copy(
                        isLoading = false,
                        message = "Backup created successfully at: $filePath",
                        hasBackupAvailable = true
                    )
                    checkForExistingBackups()
                }.onFailure { error ->
                    val msg = error.message ?: "Unknown error"
                    if (msg.contains("Permission denied", ignoreCase = true)) {
                        _backupState.value = _backupState.value.copy(
                            isLoading = false,
                            error = "Failed to create backup due to missing storage permission. Please grant permission or export the backup manually."
                        )
                    } else {
                        _backupState.value = _backupState.value.copy(
                            isLoading = false,
                            error = "Failed to create backup: $msg"
                        )
                    }
                }
            }
        }
    }

    private fun createAutoBackup() {
        viewModelScope.launch {
            try {
                backupService?.createBackup(includeAutoBackup = true)
            } catch (_: SecurityException) {
                // permission denied - quietly ignore automatic backups but surface state elsewhere
                _backupState.value = _backupState.value.copy(
                    error = "Automatic backup skipped: permission denied for storage."
                )
            } catch (_: Exception) {
                // Silently fail - don't disrupt normal app operation
            }
        }
    }

    fun restoreFromBackup() {
        viewModelScope.launch {
            _backupState.value = _backupState.value.copy(isLoading = true)

            backupService?.let { service ->
                try {
                    val result = service.restoreFromBackup()
                    result.onSuccess { count ->
                        _backupState.value = _backupState.value.copy(
                            isLoading = false,
                            message = "Successfully restored $count readings from backup",
                            hasBackupAvailable = count > 0,
                            lastRestoreCount = count
                        )
                    }.onFailure { error ->
                        val msg = error.message ?: "Unknown error"
                        if (msg.contains("Permission denied", ignoreCase = true)) {
                            _backupState.value = _backupState.value.copy(
                                isLoading = false,
                                error = "Failed to restore backup: permission denied while accessing backup. Please grant storage permission or import the backup file using the import option."
                            )
                        } else {
                            _backupState.value = _backupState.value.copy(
                                isLoading = false,
                                error = "Failed to restore backup: $msg",
                                lastRestoreCount = 0
                            )
                        }
                    }
                } catch (_: SecurityException) {
                    _backupState.value = _backupState.value.copy(
                        isLoading = false,
                        error = "Failed to restore backup: permission denied while accessing backup. Please grant storage permission or import the backup file using the import option."
                    )
                } catch (_: Exception) {
                    _backupState.value = _backupState.value.copy(
                        isLoading = false,
                        error = "Failed to restore backup: ${'$'}{(null as Exception?)?.message}",
                        lastRestoreCount = 0
                    )
                }
            }
        }
    }

    fun restoreFromUri(uri: Uri) {
        viewModelScope.launch {
            _backupState.value = _backupState.value.copy(isLoading = true)
            backupService?.let { service ->
                try {
                    val result = service.restoreFromUri(uri)
                    result.onSuccess { count ->
                        _backupState.value = _backupState.value.copy(
                            isLoading = false,
                            message = "Successfully restored $count readings from imported backup",
                            hasBackupAvailable = count > 0,
                            lastRestoreCount = count
                        )
                    }.onFailure { error ->
                        val msg = error.message ?: "Unknown error"
                        if (msg.contains("Permission denied", ignoreCase = true)) {
                            _backupState.value = _backupState.value.copy(
                                isLoading = false,
                                error = "Failed to restore imported backup: permission denied while accessing the file."
                            )
                        } else {
                            _backupState.value = _backupState.value.copy(
                                isLoading = false,
                                error = "Failed to restore imported backup: $msg",
                                lastRestoreCount = 0
                            )
                        }
                    }
                } catch (_: SecurityException) {
                    _backupState.value = _backupState.value.copy(
                        isLoading = false,
                        error = "Failed to restore imported backup: permission denied while accessing the file."
                    )
                } catch (_: Exception) {
                    _backupState.value = _backupState.value.copy(
                        isLoading = false,
                        error = "Failed to restore imported backup: ${'$'}{(null as Exception?)?.message}",
                        lastRestoreCount = 0
                    )
                }
            }
        }
    }



    // Restore from a file path (filesystem) and optionally clear existing readings
    fun restoreFromCandidateFilePath(filePath: String, clearExisting: Boolean = true) {
        viewModelScope.launch {
            _backupState.value = _backupState.value.copy(isLoading = true)
            try {
                val result = backupService?.restoreFromBackup(filePath = filePath, clearExisting = clearExisting)
                result?.onSuccess { count ->
                    _backupState.value = _backupState.value.copy(
                        isLoading = false,
                        message = "Successfully restored $count readings from backup",
                        hasBackupAvailable = count > 0,
                        lastRestoreCount = count
                    )
                    // refresh candidates/info after restore
                    checkForExistingBackups()
                }?.onFailure { error ->
                    _backupState.value = _backupState.value.copy(isLoading = false, error = "Failed to restore backup: ${error.message}")
                }
            } catch (_: SecurityException) {
                _backupState.value = _backupState.value.copy(isLoading = false, error = "Permission denied while restoring backup: ${'$'}{(null as SecurityException?)?.message}")
            } catch (_: Exception) {
                _backupState.value = _backupState.value.copy(isLoading = false, error = "Failed to restore backup: ${'$'}{(null as Exception?)?.message}")
            }
        }
    }

    // Restore from a MediaStore uri string
    fun restoreFromCandidateUri(uriString: String, clearExisting: Boolean = true) {
        viewModelScope.launch {
            _backupState.value = _backupState.value.copy(isLoading = true)
            try {
                val uri = uriString.toUri()
                val result = backupService?.restoreFromUri(uri, clearExisting = clearExisting)
                result?.onSuccess { count ->
                    _backupState.value = _backupState.value.copy(
                        isLoading = false,
                        message = "Successfully restored $count readings from backup",
                        hasBackupAvailable = count > 0,
                        lastRestoreCount = count
                    )
                    checkForExistingBackups()
                }?.onFailure { error ->
                    _backupState.value = _backupState.value.copy(isLoading = false, error = "Failed to restore backup: ${error.message}")
                }
            } catch (_: SecurityException) {
                _backupState.value = _backupState.value.copy(isLoading = false, error = "Permission denied while restoring backup: ${'$'}{(null as SecurityException?)?.message}")
            } catch (_: Exception) {
                _backupState.value = _backupState.value.copy(isLoading = false, error = "Failed to restore backup: ${'$'}{(null as Exception?)?.message}")
            }
        }
    }

    fun deleteBackup() {
        viewModelScope.launch {
            backupService?.let { service ->
                val result: Result<Boolean> = service.deleteBackup()
                result.onSuccess { deleted ->
                     _backupState.value = _backupState.value.copy(
                         hasBackupAvailable = false,
                         lastBackupInfo = null,
                         message = if (deleted) "Backup deleted successfully" else "No backup found to delete"
                     )
                 }.onFailure { error: Throwable ->
                     val msg = error.message ?: "Unknown error"
                     if (msg.contains("Permission denied", ignoreCase = true)) {
                         _backupState.value = _backupState.value.copy(
                             error = "Failed to delete backup: permission denied while accessing backup files."
                         )
                     } else {
                         _backupState.value = _backupState.value.copy(
                             error = "Failed to delete backup: ${'$'}{error.message}"
                         )
                     }
                 }
             }
         }
     }


    fun clearMessage() {
        _backupState.value = _backupState.value.copy(message = null, error = null, lastRestoreCount = null)
    }

    // Debug candidate functions

    // Load debug candidate strings from BackupService (uses debugGetCandidatesInfo)
    fun loadBackupCandidates() {
        viewModelScope.launch {
            try {
                val list = try { backupService?.debugGetCandidatesInfo() } catch (_: Exception) { null }
                _backupCandidates.value = list ?: listOf("No candidates or backupService==null")
            } catch (_: Exception) {
                _backupCandidates.value = listOf("Failed to load candidates")
            }
        }
    }

    // Restore from a debug candidate string (e.g. "candidate=/sdcard/Download/.." or "selected_latest=content://...")
    fun restoreFromCandidateString(candidate: String, clearExisting: Boolean = true) {
        // Normalize string to path/uri
        val payload = when {
            candidate.startsWith("candidate=") -> candidate.substringAfter("candidate=")
            candidate.startsWith("selected_latest=") -> candidate.substringAfter("selected_latest=")
            else -> candidate
        }

        if (payload.startsWith("content://", ignoreCase = true)) {
            // Use existing ViewModel helper to restore from URI string
            restoreFromCandidateUri(payload, clearExisting = clearExisting)
        } else {
            // Treat as filesystem path
            restoreFromCandidateFilePath(payload, clearExisting = clearExisting)
        }
    }

    // Copy latest filesystem candidate into app folder (if any) and restore from it.
    fun copyLatestFilesystemCandidateAndRestore(clearExisting: Boolean = true) {
        viewModelScope.launch {
            if (backupService == null) return@launch
            _backupState.value = _backupState.value.copy(isLoading = true)
            try {
                val copiedPath = try { backupService.copyLatestFilesystemCandidateToAppFolder() } catch (_: SecurityException) {
                    _backupState.value = _backupState.value.copy(isLoading = false, error = "Failed to copy backup: permission denied")
                    null
                }

                if (!copiedPath.isNullOrBlank()) {
                    // Trigger restore using the filesystem path
                    val result = backupService.restoreFromBackup(filePath = copiedPath, clearExisting = clearExisting)
                    result.onSuccess { count ->
                        _backupState.value = _backupState.value.copy(isLoading = false, message = "Restored $count readings from copied backup", lastRestoreCount = count, hasBackupAvailable = count > 0)
                        checkForExistingBackups()
                    }
                    result.onFailure { error ->
                        _backupState.value = _backupState.value.copy(isLoading = false, error = "Failed to restore copied backup: ${error.message}")
                    }
                } else {
                    _backupState.value = _backupState.value.copy(isLoading = false, error = "No filesystem backup candidate available to copy")
                }
            } catch (_: Exception) {
                _backupState.value = _backupState.value.copy(isLoading = false, error = "Failed to copy/restore backup")
            }
        }
    }
}

data class ReadingUiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

data class BackupUiState(
    val isLoading: Boolean = false,
    val hasBackupAvailable: Boolean = false,
    val lastBackupInfo: BackupInfo? = null,
    val message: String? = null,
    val error: String? = null,
    // Number of readings imported by the most recent restore attempt (null if none yet)
    val lastRestoreCount: Int? = null
)

class ReadingViewModelFactory(
    private val repository: ReadingRepository,
    private val unitPreferences: UnitPreferences? = null,
    private val backupService: BackupService? = null,
    // When true, the created ViewModel will attempt auto-restore during init if DB is empty.
    private val autoRestoreOnInit: Boolean = true
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ReadingViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ReadingViewModel(repository, unitPreferences, backupService, autoRestoreOnInit) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}