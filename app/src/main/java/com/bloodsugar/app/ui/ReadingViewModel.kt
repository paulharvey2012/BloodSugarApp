package com.bloodsugar.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.bloodsugar.app.data.Reading
import com.bloodsugar.app.data.ReadingRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Date

class ReadingViewModel(private val repository: ReadingRepository) : ViewModel() {
    
    val allReadings: Flow<List<Reading>> = repository.getAllReadings()

    private val _uiState = MutableStateFlow(ReadingUiState())
    val uiState: StateFlow<ReadingUiState> = _uiState
    
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
        }
    }
    
    fun updateReading(reading: Reading) {
        viewModelScope.launch {
            repository.updateReading(reading)
        }
    }
    
    fun deleteReading(reading: Reading) {
        viewModelScope.launch {
            repository.deleteReading(reading)
        }
    }
}

data class ReadingUiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

class ReadingViewModelFactory(private val repository: ReadingRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ReadingViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ReadingViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}