package com.bloodsugar.app.ui

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
    private val backupService: BackupService? = null
) : ViewModel() {

    val allReadings: Flow<List<Reading>> = repository.getAllReadings()

    private val _uiState = MutableStateFlow(ReadingUiState())
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

        // Check for existing backups on startup
        checkForExistingBackups()

        // Auto-restore from backup if no data exists
        autoRestoreIfNeeded()
    }

    private fun checkForExistingBackups() {
        viewModelScope.launch {
            backupService?.let { service ->
                val hasBackup = service.hasBackupAvailable()
                val backupInfo = service.getBackupInfo()
                _backupState.value = _backupState.value.copy(
                    hasBackupAvailable = hasBackup,
                    lastBackupInfo = backupInfo
                )
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
                        if (service.hasBackupAvailable()) {
                            val result = service.restoreFromBackup()
                            result.onSuccess { count ->
                                if (count > 0) {
                                    _backupState.value = _backupState.value.copy(
                                        message = "Automatically restored $count readings from previous installation"
                                    )
                                }
                            }.onFailure { error ->
                                // Log but don't show error to user during auto-restore
                                _backupState.value = _backupState.value.copy(
                                    error = "Auto-restore failed: ${error.message}"
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
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
                    _backupState.value = _backupState.value.copy(
                        isLoading = false,
                        error = "Failed to create backup: ${error.message}"
                    )
                }
            }
        }
    }

    private fun createAutoBackup() {
        viewModelScope.launch {
            try {
                backupService?.createBackup(includeAutoBackup = true)
            } catch (e: Exception) {
                // Silently fail - don't disrupt normal app operation
            }
        }
    }

    fun restoreFromBackup() {
        viewModelScope.launch {
            _backupState.value = _backupState.value.copy(isLoading = true)

            backupService?.let { service ->
                val result = service.restoreFromBackup()
                result.onSuccess { count ->
                    _backupState.value = _backupState.value.copy(
                        isLoading = false,
                        message = "Successfully restored $count readings from backup"
                    )
                }.onFailure { error ->
                    _backupState.value = _backupState.value.copy(
                        isLoading = false,
                        error = "Failed to restore backup: ${error.message}"
                    )
                }
            }
        }
    }

    fun deleteBackup() {
        viewModelScope.launch {
            backupService?.let { service ->
                val result = service.deleteBackup()
                result.onSuccess { deleted ->
                    _backupState.value = _backupState.value.copy(
                        hasBackupAvailable = false,
                        lastBackupInfo = null,
                        message = if (deleted) "Backup deleted successfully" else "No backup found to delete"
                    )
                }.onFailure { error ->
                    _backupState.value = _backupState.value.copy(
                        error = "Failed to delete backup: ${error.message}"
                    )
                }
            }
        }
    }

    fun clearMessage() {
        _backupState.value = _backupState.value.copy(message = null, error = null)
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
    val error: String? = null
)

class ReadingViewModelFactory(
    private val repository: ReadingRepository,
    private val unitPreferences: UnitPreferences? = null,
    private val backupService: BackupService? = null
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ReadingViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ReadingViewModel(repository, unitPreferences, backupService) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}