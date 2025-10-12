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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: ReadingViewModel) {
    val unit by viewModel.unit.collectAsStateWithLifecycle(initialValue = UnitPreferences.DEFAULT_UNIT)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
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

        // Add more settings here later

        Spacer(modifier = Modifier.weight(1f))

        VersionFooter()
    }
}

@Preview(showBackground = true)
@Composable
fun SettingsScreenPreview() {
    BloodSugarAppTheme {
        val mockDao = object : com.bloodsugar.app.data.ReadingDao {
            override fun getAllReadings() = kotlinx.coroutines.flow.flowOf(emptyList<Reading>())
            override suspend fun insertReading(reading: Reading) {}
            override suspend fun updateReading(reading: Reading) {}
            override suspend fun deleteReading(reading: Reading) {}
            override suspend fun getReadingById(id: Long): Reading? = null
        }
        val mockRepository = ReadingRepository(mockDao)
        val mockViewModel = ReadingViewModel(mockRepository)
        SettingsScreen(viewModel = mockViewModel)
    }
}
