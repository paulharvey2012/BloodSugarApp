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
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Determine if we should attempt a one-time auto-restore. This flag is stored in
        // SharedPreferences so the restore is only attempted once after install.
        val prefs = getSharedPreferences("com.bloodsugar.app.prefs", MODE_PRIVATE)
        val PREF_AUTO_RESTORE_DONE = "auto_restore_done_v1"
        val alreadyDone = prefs.getBoolean(PREF_AUTO_RESTORE_DONE, false)
        val autoRestoreOnInit = !alreadyDone

        val database = AppDatabase.getDatabase(this)
        val repository = ReadingRepository(database.readingDao())
        // Provide DataStore-backed UnitPreferences so unit selection persists across app restarts
        val unitPrefs = UnitPreferences(applicationContext.dataStore)
        // Initialize BackupService for data persistence across reinstalls
        val backupService = BackupService(applicationContext, repository)
        val viewModelFactory = ReadingViewModelFactory(repository, unitPrefs, backupService, autoRestoreOnInit)

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

        // Observe backup state to mark the auto-restore preference on successful restores,
        // and prompt the user with the import picker in case of permission failures.
        var importPrompted = false
        var restoreMarked = alreadyDone
        lifecycleScope.launch {
            activityViewModel.backupState.collect { state ->
                // If an auto-restore just completed successfully and we haven't yet marked it, mark it
                val restored = state.lastRestoreCount
                if (!restoreMarked && autoRestoreOnInit && restored != null && restored > 0) {
                    try {
                        prefs.edit().putBoolean(PREF_AUTO_RESTORE_DONE, true).apply()
                    } catch (_: Exception) {}
                    restoreMarked = true
                }

                // If auto-restore failed due to permission, prompt the user once to pick a file
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