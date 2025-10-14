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

@Suppress("UNUSED_PARAMETER")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(viewModel: ReadingViewModel) {
    Column(modifier = Modifier.fillMaxSize()) {

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                  //  Text(text = "Help", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = "This is an app to store blood sugar and ketones readings",
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("About") },
                        singleLine = false,
                        maxLines = 4
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            VersionFooter()
        }
    }
}

@Preview(showBackground = true)
@Composable
fun HelpScreenPreview() {
    BloodSugarAppTheme {
        val mockDao = object : com.bloodsugar.app.data.ReadingDao {
            override fun getAllReadings() = kotlinx.coroutines.flow.flowOf(emptyList<Reading>())
            override suspend fun getAllReadingsForBackup(): List<Reading> = emptyList()
            override suspend fun insertReading(reading: Reading) {}
            override suspend fun updateReading(reading: Reading) {}
            override suspend fun deleteReading(reading: Reading) {}
            override suspend fun getReadingById(id: Long): Reading? = null
            // Implement fuzzy count for preview to satisfy updated ReadingDao interface
            override suspend fun countMatchingFuzzy(type: String, value: Double, startDate: java.util.Date, endDate: java.util.Date, epsilon: Double): Int = 0
            // Implement clearAllReadings added to the DAO
            override suspend fun clearAllReadings() {}
        }
        val mockRepository = ReadingRepository(mockDao)
        val mockViewModel = ReadingViewModel(mockRepository)
        HelpScreen(viewModel = mockViewModel)
    }
}
