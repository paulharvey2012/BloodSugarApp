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

    // backup candidates state for debug UI
    private val _backupCandidates = MutableStateFlow<List<com.bloodsugar.app.data.BackupCandidateInfo>>(emptyList())
    val backupCandidates: StateFlow<List<com.bloodsugar.app.data.BackupCandidateInfo>> = _backupCandidates

    private val _uiState = MutableStateFlow(ReadingUiState())
    @Suppress("unused")
    val uiState: StateFlow<ReadingUiState> = _uiState

    // Unit preference: in-memory default, but if UnitPreferences is provided it will be kept in sync
    private val _unit = MutableStateFlow(UnitPreferences.DEFAULT_UNIT)
    val unit: StateFlow<String> = _unit

    // Backup/Restore states
    private val _backupState = MutableStateFlow(BackupUiState())
    val backupState: StateFlow<BackupUiState> = _backupState

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
                    val backupInfo = service.getBackupInfo()
                    _backupState.value = _backupState.value.copy(
                        hasBackupAvailable = hasBackup,
                        lastBackupInfo = backupInfo
                    )
                } catch (_: SecurityException) {
                    // Surface permission denied to the UI so user can take action
                    _backupState.value = _backupState.value.copy(
                        hasBackupAvailable = false,
                        error = "Permission denied while accessing backups. Please grant storage permission or pick the backup file manually."
                    )
                } catch (_: Exception) {
                    // Non-permission failures should not crash startup
                    _backupState.value = _backupState.value.copy(
                        hasBackupAvailable = false
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
                    // Database is empty, try to auto-restore from backup
                    backupService?.let { service ->
                        try {
                            val backupInfo = try { service.getBackupInfo() } catch (_: Exception) { null }
                            if (backupInfo != null) {
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
                                    } catch (_: SecurityException) {
                                        _backupState.value = _backupState.value.copy(
                                            error = "Auto-restore failed: permission denied while accessing backups. Please grant storage permission or import the backup manually."
                                        )
                                    } catch (_: Exception) {
                                        // Log or ignore - don't disrupt startup
                                    }
                                }
                            } else {
                                // No backup info available; nothing to restore
                            }
                        } catch (_: SecurityException) {
                            _backupState.value = _backupState.value.copy(
                                error = "Auto-restore failed: permission denied while accessing backups. Please grant storage permission or import the backup manually."
                            )
                        } catch (_: Exception) {
                            // Log or ignore
                        }
                    }
                }
            } catch (_: Exception) {
                // Silently fail - don't disrupt app startup
            }
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


    // Fetch and publish backup candidate list (for debug screen)
    fun fetchBackupCandidates() {
        viewModelScope.launch {
            try {
                val list = backupService?.listBackupCandidates() ?: emptyList()
                _backupCandidates.value = list
            } catch (_: SecurityException) {
                 _backupCandidates.value = emptyList()
                 _backupState.value = _backupState.value.copy(
                     error = "Permission denied while listing backup candidates"
                 )
             } catch (e: Exception) {
                 _backupCandidates.value = emptyList()
                 _backupState.value = _backupState.value.copy(
                     error = "Failed to list backup candidates: ${e.message}"
                 )
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