package com.bloodsugar.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.edit
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
import java.io.File

class MainActivity : ComponentActivity() {

    private val TAG = "MainActivity"

    private lateinit var activityViewModel: ReadingViewModel
    private var pendingAutoRestore = false
    // Keep a reference so we can inspect backup info before attempting automatic restore
    private var backupService: BackupService? = null

    // Permission launcher for storage access
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted && pendingAutoRestore) {
            // Permission granted, attempt auto-restore
            attemptAutoRestore()
        } else if (pendingAutoRestore) {
            // Permission denied, fall back to manual import prompt
            pendingAutoRestore = false
            // The ViewModel will handle the permission error and prompt for manual import
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Determine if we should attempt a one-time auto-restore. This flag is stored in
        // SharedPreferences so the restore is only attempted once after install.
        val prefs = getSharedPreferences("com.bloodsugar.app.prefs", MODE_PRIVATE)
        val PREF_AUTO_RESTORE_DONE = "auto_restore_done_v1"
        // Use a no-backup file as the authoritative marker so the "already done" flag
        // isn't restored by Android Auto Backup on reinstall. Fall back to the
        // SharedPreferences value for backward compatibility.
        val noBackupDir = try { applicationContext.noBackupFilesDir } catch (_: Exception) { filesDir }
        val markerFile = File(noBackupDir, PREF_AUTO_RESTORE_DONE)
        // Rely on the no-backup marker file as the authoritative source. Do NOT use the
        // SharedPreferences boolean for the initial decision because SharedPreferences
        // can be restored by Android Auto Backup on reinstall, which would incorrectly
        // skip the one-time auto-restore. We still write the preference for
        // backward-compatibility, but the marker file controls the behavior.
        val alreadyDone = markerFile.exists()
        Log.d(TAG, "noBackupDir=${noBackupDir?.absolutePath}, markerExists=$alreadyDone")
        val shouldAutoRestore = !alreadyDone
        Log.d(TAG, "shouldAutoRestore=$shouldAutoRestore")

        val database = AppDatabase.getDatabase(this)
        val repository = ReadingRepository(database.readingDao())
        // Provide DataStore-backed UnitPreferences so unit selection persists across app restarts
        val unitPrefs = UnitPreferences(applicationContext.dataStore)
        // Initialize BackupService for data persistence across reinstalls
        backupService = BackupService(applicationContext, repository)

        // Debug intent: if launched with dump_candidates=true, fetch candidate info and log it
        if (intent?.getBooleanExtra("dump_candidates", false) == true) {
            lifecycleScope.launch {
                try {
                    // Use the service-provided debug helper to list all discovered candidates and the chosen latest
                    val debugInfo: List<String> = try {
                        backupService?.debugGetCandidatesInfo() ?: listOf("backupService=null")
                    } catch (e: Exception) {
                        listOf("debug_error=${e.message}")
                    }

                     debugInfo.forEach { Log.d(TAG, it) }
                     val first = debugInfo.firstOrNull() ?: "No debug info"
                     try { Toast.makeText(this@MainActivity, first, Toast.LENGTH_LONG).show() } catch (_: Exception) {}
                 } catch (e: Exception) {
                     Log.e(TAG, "Failed to dump backup candidates: ${e.message}")
                 }
             }
         }

        // Create ViewModel without auto-restore initially - we'll handle permissions first
        val viewModelFactory = ReadingViewModelFactory(repository, unitPrefs, backupService, autoRestoreOnInit = false)

        // Create an activity-scoped ViewModel so non-Compose code (the activity launcher) can call it
        activityViewModel = ViewModelProvider(this, viewModelFactory).get(ReadingViewModel::class.java)

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

        // Handle auto-restore with proper permission checking
        // On first run after reinstall: perform a one-time auto-restore flow. This runs a
        // permission check and only opens the import picker if necessary (e.g. best
        // candidate is a content Uri or permission is required). Mark the preference
        // immediately so this auto-restore attempt only happens once after reinstall.
        if (shouldAutoRestore) {
            try {
                // Do NOT mark the one-time auto-restore as done here. Instead delay
                // creating the no-backup marker and writing SharedPreferences until
                // we either successfully restore or we actively prompt the user to
                // import a backup file (SAF picker). This avoids skipping the
                // restore when the attempt fails due to permission or other issues.
                Log.d(TAG, "Starting auto-restore flow: checking permissions and backup candidates")
            } catch (_: Exception) {
                // ignore prefs write failures
            }
            // Kick off permission check + possible auto-restore (this will only open the
            // picker if the database is empty and a content URI requires user permission,
            // or if other conditions in attemptAutoRestore()/checkPermissionsAndAutoRestore() demand it).
            checkPermissionsAndAutoRestore()
        }

        // Debug/test hook: if the activity is started with the intent extra `force_import=true`,
        // immediately open the import picker. This is handy for testing on devices/emulators
        // via adb: `adb shell am start -n com.bloodsugar.app/.MainActivity --ez force_import true`.
        if (intent?.getBooleanExtra("force_import", false) == true) {
            ImportInvoker.launcher?.invoke()
        }

        // Observe backup state to mark the auto-restore preference on successful restores,
        // and prompt the user with the import picker in case of permission failures.
        var importPrompted = false
        var restoreMarked = alreadyDone
        lifecycleScope.launch {
            activityViewModel.backupState.collect { state ->
                // If an auto-restore just completed successfully and we haven't yet marked it, mark it
                val restored = state.lastRestoreCount
                if (!restoreMarked && shouldAutoRestore && restored != null && restored > 0) {
                    try {
                        // Successful restore: mark as completed (SharedPreferences + no-backup marker)
                        prefs.edit { putBoolean(PREF_AUTO_RESTORE_DONE, true) }
                        try {
                            if (!markerFile.exists()) {
                                noBackupDir?.let { dir -> if (!dir.exists()) dir.mkdirs() }
                                markerFile.createNewFile()
                                Log.d(TAG, "Created no-backup marker at ${markerFile.absolutePath} after successful restore")
                            }
                        } catch (_: Exception) {
                            // ignore marker file creation failures
                        }
                    } catch (_: Exception) {}
                    restoreMarked = true

                    // Build toast text including expected backup info if available
                    val expected = state.lastBackupInfo?.readingCount
                    val source = state.lastBackupInfo?.filePath
                    val toastText = if (expected != null) {
                        if (expected != restored) {
                            "Restored $restored of $expected readings from backup (${source ?: "unknown"})"
                        } else {
                            "Restored $restored readings from backup (${source ?: "unknown"})"
                        }
                    } else {
                        "Restored $restored readings from backup"
                    }

                    // Show a visible toast so the user notices the automatic restore
                    try {
                        Toast.makeText(this@MainActivity, toastText, Toast.LENGTH_LONG).show()
                    } catch (_: Exception) {
                        // ignore toast failures
                    }
                }

                // If auto-restore failed due to permission, prompt the user once to pick a file
                val err = state.error ?: ""
                // Trigger import prompt when auto-restore failed due to permission OR when it was skipped
                // because the best backup candidate is a content URI (requires user import).
                if (!importPrompted && (
                        (err.contains("Auto-restore failed", ignoreCase = true) && err.contains("permission", ignoreCase = true))
                        || err.contains("Auto-restore skipped", ignoreCase = true)
                        || err.contains("content uri", ignoreCase = true)
                        || err.contains("shared storage", ignoreCase = true)
                    )) {
                     importPrompted = true
                     // Before launching the picker, persist the one-time marker
                     // so the picker prompt is only shown once after reinstall.
                     try {
                         prefs.edit { putBoolean(PREF_AUTO_RESTORE_DONE, true) }
                         if (!markerFile.exists()) {
                             noBackupDir?.let { dir -> if (!dir.exists()) dir.mkdirs() }
                             markerFile.createNewFile()
                            Log.d(TAG, "Created no-backup marker at ${markerFile.absolutePath} before launching SAF picker")
                         }
                     } catch (_: Exception) {
                         // ignore marker/prefs write failures
                     }
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

    private fun checkPermissionsAndAutoRestore() {
        Log.d(TAG, "checkPermissionsAndAutoRestore called")
        // For Android 10+ (API 29+), we don't need READ_EXTERNAL_STORAGE for MediaStore
        // For Android 6-9 (API 23-28), we need READ_EXTERNAL_STORAGE
        val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED

        if (needsPermission) {
            // Request permission first
            pendingAutoRestore = true
            storagePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        } else {
            // Permission not needed or already granted
            attemptAutoRestore()
        }
    }

    private fun attemptAutoRestore() {
        Log.d(TAG, "attemptAutoRestore called")
        pendingAutoRestore = false
        lifecycleScope.launch {
            try {
                // Regardless of whether the current database contains readings,
                // attempt to import the latest available backup when the no-backup
                // marker is missing (this indicates a reinstall/first-run).
                // BackupService and the repository will dedupe existing entries.
                try {
                    val info = try { backupService?.getBackupInfo() } catch (_: SecurityException) { null }
                    Log.d(TAG, "backupInfo=$info")
                    if (info != null) {
                        val path = info.filePath
                        Log.d(TAG, "Best backup candidate path=$path")
                        if (path.startsWith("content://", ignoreCase = true)) {
                            // Best candidate is a content URI. Try restoring directly
                            // first — some MediaStore URIs are readable without further
                            // user interaction on modern devices. If that direct restore
                            // fails due to permission, the ViewModel will update
                            // backupState with an error and MainActivity's observer
                            // will prompt the user with the SAF picker (and create
                            // the one-time marker at that point).
                            try {
                                Log.d(TAG, "Attempting direct restore from content URI candidate")
                                activityViewModel.restoreFromBackup()
                            } catch (_: Exception) {
                                // If a direct attempt to start restore throws, fall back
                                // to prompting the user via SAF (observer will handle it).
                                Log.d(TAG, "Direct restore attempt threw; observer will handle prompting")
                            }
                            return@launch
                        }
                    }
                } catch (_: Exception) {
                    // ignore and fall through to direct restore attempt
                    Log.d(TAG, "Exception while getting backup info; will attempt direct restore anyway")
                }

                // Either no special handling needed or fallback: trigger auto-restore
                Log.d(TAG, "Triggering restoreFromBackup()")
                activityViewModel.restoreFromBackup()
             } catch (_: Exception) {
                 // Silently fail - don't disrupt app startup
             }
         }
     }
 }
