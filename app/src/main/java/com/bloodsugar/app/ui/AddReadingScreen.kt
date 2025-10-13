package com.bloodsugar.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.bloodsugar.app.data.ReadingRepository
import com.bloodsugar.app.ui.components.VersionFooter
import com.bloodsugar.app.ui.theme.BloodSugarAppTheme
import java.text.SimpleDateFormat
import java.util.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun AddReadingScreen(viewModel: ReadingViewModel, navController: NavController) {
    var selectedType by remember { mutableStateOf(0) } // 0 for blood sugar, 1 for ketones
    var value by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var selectedDate by remember { mutableStateOf(Date()) }

    // Date and Time picker states
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    // LazyColumn scroll state
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Scroll to top when this composable is first composed
    LaunchedEffect(Unit) {
        coroutineScope.launch {
            listState.scrollToItem(0)
        }
    }

    val calendar = Calendar.getInstance()
    calendar.time = selectedDate

    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = selectedDate.time
    )

    val timePickerState = rememberTimePickerState(
        initialHour = calendar.get(Calendar.HOUR_OF_DAY),
        initialMinute = calendar.get(Calendar.MINUTE)
    )

    val dateFormatter = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())

    // Read unit preference from ViewModel
    val selectedUnit by viewModel.unit.collectAsStateWithLifecycle(initialValue = "mmol/L")

    // Focus handling: when user presses Next on the value field, move focus to notes
    val notesFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    // Extracted save logic so it can be reused by the Save button and the Notes IME Done action
    val saveReading: () -> Unit = {
        if (value.isNotBlank()) {
            val numericValue = value.toDoubleOrNull()
            if (numericValue != null) {
                if (selectedType == 0) {
                    viewModel.addBloodSugarReading(
                        value = numericValue,
                        date = selectedDate,
                        notes = notes,
                        unit = selectedUnit
                    )
                } else {
                    viewModel.addKetoneReading(
                        value = numericValue,
                        date = selectedDate,
                        notes = notes,
                        unit = selectedUnit
                    )
                }
                // Reset form
                value = ""
                notes = ""
                selectedDate = Date()
            }
        }
        // hide keyboard and clear focus after saving
        focusManager.clearFocus()
        keyboardController?.hide()
        // Navigate to history screen after saving using same pattern as bottom navigation
        navController.navigate("history") {
            popUpTo("add_reading") {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    // Date Picker Dialog
    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            val newCalendar = Calendar.getInstance()
                            newCalendar.timeInMillis = millis
                            newCalendar.set(Calendar.HOUR_OF_DAY, calendar.get(Calendar.HOUR_OF_DAY))
                            newCalendar.set(Calendar.MINUTE, calendar.get(Calendar.MINUTE))
                            selectedDate = newCalendar.time
                        }
                        showDatePicker = false
                    }
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    // Time Picker Dialog
    if (showTimePicker) {
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("Select Time") },
            text = {
                TimePicker(state = timePickerState)
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val newCalendar = Calendar.getInstance()
                        newCalendar.time = selectedDate
                        newCalendar.set(Calendar.HOUR_OF_DAY, timePickerState.hour)
                        newCalendar.set(Calendar.MINUTE, timePickerState.minute)
                        selectedDate = newCalendar.time
                        showTimePicker = false
                    }
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            // Reading Type Selection
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Reading Type",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            onClick = {
                                selectedType = 0
                                // Keep existing unit preference (do not override)
                            },
                            label = { Text("Blood Sugar") },
                            selected = selectedType == 0,
                            modifier = Modifier.weight(1f),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary
                            )
                        )
                        FilterChip(
                            onClick = {
                                selectedType = 1
                                // Default unit for ketones is mmol/L
                                viewModel.setUnit("mmol/L")
                            },
                            label = { Text("Ketones") },
                            selected = selectedType == 1,
                            modifier = Modifier.weight(1f),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary
                            )
                        )
                    }
                }
            }
        }


        item {
            // Value Input
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Value",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = value,
                            onValueChange = { value = it },
                            label = { Text("Reading") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Decimal,
                                imeAction = ImeAction.Next
                            ),
                            keyboardActions = KeyboardActions(
                                onNext = { notesFocusRequester.requestFocus() }
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = selectedUnit,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
        
        item {
            // Date and Time Selection
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Date & Time",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Date Button
                        OutlinedButton(
                            onClick = { showDatePicker = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.DateRange,
                                contentDescription = "Select Date",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(dateFormatter.format(selectedDate))
                        }

                        // Time Button
                        OutlinedButton(
                            onClick = { showTimePicker = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(timeFormatter.format(selectedDate))
                        }
                    }
                }
            }
        }
        
        item {
            // Notes
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Notes",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text("Optional notes") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(notesFocusRequester),
                        minLines = 3,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { saveReading() })
                    )
                }
            }
        }

        item {
            // Save Button
            Button(
                onClick = { saveReading() },
                modifier = Modifier.fillMaxWidth(),
                enabled = value.isNotBlank()
            ) {
                Text("Save")
            }
        }

        // Footer
        item {
            Spacer(modifier = Modifier.height(32.dp))
            VersionFooter()
        }
    }
}

@Preview(showBackground = true)
@Composable
fun AddReadingScreenPreview() {
    BloodSugarAppTheme {
        // Create a mock repository and viewModel for preview
        val mockRepository = ReadingRepository(mockReadingDao())
        val mockViewModel = ReadingViewModel(mockRepository)
        AddReadingScreen(viewModel = mockViewModel, navController = rememberNavController())
    }
}

@Preview(showBackground = true, name = "Dark Theme")
@Composable
fun AddReadingScreenDarkPreview() {
    BloodSugarAppTheme(darkTheme = true) {
        val mockRepository = ReadingRepository(mockReadingDao())
        val mockViewModel = ReadingViewModel(mockRepository)
        AddReadingScreen(viewModel = mockViewModel, navController = rememberNavController())
    }
}

// Mock DAO for preview purposes
private fun mockReadingDao(): com.bloodsugar.app.data.ReadingDao {
    return object : com.bloodsugar.app.data.ReadingDao {
        override fun getAllReadings() = kotlinx.coroutines.flow.flowOf(emptyList<com.bloodsugar.app.data.Reading>())
        override suspend fun getAllReadingsForBackup(): List<com.bloodsugar.app.data.Reading> = emptyList()
        override suspend fun insertReading(reading: com.bloodsugar.app.data.Reading) {}
        override suspend fun updateReading(reading: com.bloodsugar.app.data.Reading) {}
        override suspend fun deleteReading(reading: com.bloodsugar.app.data.Reading) {}
        override suspend fun getReadingById(id: Long): com.bloodsugar.app.data.Reading? = null
    }
}
