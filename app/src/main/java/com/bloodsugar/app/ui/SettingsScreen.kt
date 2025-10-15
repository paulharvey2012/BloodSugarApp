@file:OptIn(ExperimentalMaterial3Api::class)

package com.bloodsugar.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bloodsugar.app.data.Reading
import com.bloodsugar.app.data.ReadingRepository
import com.bloodsugar.app.ui.components.VersionFooter
import com.bloodsugar.app.ui.theme.BloodSugarAppTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bloodsugar.app.data.UnitPreferences
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.layout.PaddingValues

@Composable
fun SettingsScreen(viewModel: ReadingViewModel) {
    val unit by viewModel.unit.collectAsStateWithLifecycle(initialValue = UnitPreferences.DEFAULT_UNIT)
    val backupState by viewModel.backupState.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    // Show snackbar for messages or errors
    LaunchedEffect(backupState.message, backupState.error) {
        backupState.message?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearMessage()
        }
        backupState.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { contentPadding ->
        val layoutDirection = LocalLayoutDirection.current
        val combinedPadding = PaddingValues(
            start = contentPadding.calculateLeftPadding(layoutDirection) + 16.dp,
            end = contentPadding.calculateRightPadding(layoutDirection) + 16.dp,
            top = contentPadding.calculateTopPadding() + 16.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = combinedPadding,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { UnitSelectionCard(viewModel = viewModel, unit = unit) }
            item { DataManagementCard(viewModel = viewModel, backupState = backupState) }
            item { Spacer(modifier = Modifier.height(10.dp)) }
            item { VersionFooter() }
        }
    }
}

@Composable
private fun UnitSelectionCard(viewModel: ReadingViewModel, unit: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "Default Unit", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    onClick = { viewModel.setUnit("mg/dL") },
                    selected = unit == "mg/dL",
                    label = { Text("mg/dL") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    onClick = { viewModel.setUnit("mmol/L") },
                    selected = unit == "mmol/L",
                    label = { Text("mmol/L") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun DataManagementCard(viewModel: ReadingViewModel, backupState: BackupUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "Data Management", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Keep your readings safe across app reinstalls",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (backupState.hasBackupAvailable) {
                backupState.lastBackupInfo?.let { backupInfo ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "Last backup:", style = MaterialTheme.typography.bodySmall)
                            Text(text = "${backupInfo.readingCount} readings", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = java.text.SimpleDateFormat("MMM dd, yyyy HH:mm", java.util.Locale.getDefault()).format(backupInfo.date),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }
            } else {
                Text(text = "No backup available", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(12.dp))
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.createBackup() }, enabled = !backupState.isLoading, modifier = Modifier.weight(1f)) {
                    if (backupState.isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Backup Data")
                    }
                }

                if (backupState.hasBackupAvailable) {
                    OutlinedButton(onClick = { viewModel.restoreFromBackup() }, enabled = !backupState.isLoading, modifier = Modifier.weight(1f)) {
                        Text("Restore")
                    }
                }
            }

            if (backupState.hasBackupAvailable) {
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = { viewModel.deleteBackup() }, enabled = !backupState.isLoading) {
                    Text("Delete Backup", color = MaterialTheme.colorScheme.error)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(text = "ℹ️ Automatic Backup", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "Your data is automatically backed up whenever you add, edit, or delete readings. This backup is preserved when you uninstall and reinstall the app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun SettingsScreenPreview() {
    BloodSugarAppTheme {
        val mockDao = object : com.bloodsugar.app.data.ReadingDao {
            override fun getAllReadings() = kotlinx.coroutines.flow.flowOf(emptyList<Reading>())
            override suspend fun getAllReadingsForBackup(): List<Reading> = emptyList()
            override suspend fun insertReading(reading: Reading) {}
            override suspend fun updateReading(reading: Reading) {}
            override suspend fun deleteReading(reading: Reading) {}
            override suspend fun getReadingById(id: Long): Reading? = null
            // Implement fuzzy count for preview
            override suspend fun countMatchingFuzzy(type: String, value: Double, startDate: java.util.Date, endDate: java.util.Date, epsilon: Double): Int = 0
            // Implement clearAllReadings added to the DAO
            override suspend fun clearAllReadings() {}
        }
        val mockRepository = ReadingRepository(mockDao)
        val mockViewModel = ReadingViewModel(mockRepository)
        SettingsScreen(viewModel = mockViewModel)
    }
}
