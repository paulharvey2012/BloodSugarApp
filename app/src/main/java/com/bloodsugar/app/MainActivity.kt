package com.bloodsugar.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.bloodsugar.app.data.AppDatabase
import com.bloodsugar.app.data.ReadingRepository
import com.bloodsugar.app.data.UnitPreferences
import com.bloodsugar.app.data.BackupService
import com.bloodsugar.app.data.dataStore
import com.bloodsugar.app.ui.BloodSugarApp
import com.bloodsugar.app.ui.ReadingViewModel
import com.bloodsugar.app.ui.ReadingViewModelFactory
import com.bloodsugar.app.ui.theme.BloodSugarAppTheme
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

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

        // Create an activity-scoped ViewModel so non-Compose code (the activity launcher) can call it
        val activityViewModel = ViewModelProvider(this, viewModelFactory).get(ReadingViewModel::class.java)

        // Register a file picker for importing backups (Storage Access Framework)
        val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let {
                try {
                    // Persist read permission so the app can read the file later if needed
                    contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {
                    // ignore permission persistence failures
                }
                activityViewModel.restoreFromUri(it)
            }
        }

        // Expose the launcher via ImportInvoker for the Compose UI to call
        ImportInvoker.launcher = {
            importLauncher.launch(arrayOf("application/json", "application/*", "*/*"))
        }

        // If auto-restore failed due to permission, prompt the user once to pick a file
        var importPrompted = false
        lifecycleScope.launch {
            activityViewModel.backupState.collect { state ->
                val err = state.error ?: ""
                if (!importPrompted && err.contains("Auto-restore failed", ignoreCase = true) && err.contains("permission", ignoreCase = true)) {
                    importPrompted = true
                    // Launch the picker to let the user import a backup
                    ImportInvoker.launcher?.invoke()
                }
            }
        }

        setContent {
            BloodSugarAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Use the activity-scoped ViewModel instance inside Compose
                    BloodSugarApp(viewModel = activityViewModel)
                }
            }
        }
    }
}