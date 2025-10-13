package com.bloodsugar.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bloodsugar.app.data.AppDatabase
import com.bloodsugar.app.data.ReadingRepository
import com.bloodsugar.app.data.UnitPreferences
import com.bloodsugar.app.data.BackupService
import com.bloodsugar.app.data.dataStore
import com.bloodsugar.app.ui.BloodSugarApp
import com.bloodsugar.app.ui.ReadingViewModel
import com.bloodsugar.app.ui.ReadingViewModelFactory
import com.bloodsugar.app.ui.theme.BloodSugarAppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val database = AppDatabase.getDatabase(this)
        val repository = ReadingRepository(database.readingDao())
        // Provide DataStore-backed UnitPreferences so unit selection persists across app restarts
        val unitPrefs = UnitPreferences(applicationContext.dataStore)
        // Initialize BackupService for data persistence across reinstalls
        val backupService = BackupService(applicationContext, repository)
        val viewModelFactory = ReadingViewModelFactory(repository, unitPrefs, backupService)

        setContent {
            BloodSugarAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val viewModel: ReadingViewModel = viewModel(factory = viewModelFactory)
                    BloodSugarApp(viewModel = viewModel)
                }
            }
        }
    }
}