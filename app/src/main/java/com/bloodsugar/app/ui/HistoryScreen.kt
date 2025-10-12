package com.bloodsugar.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bloodsugar.app.data.Reading
import com.bloodsugar.app.ui.components.VersionFooter
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(viewModel: ReadingViewModel) {
    val readings by viewModel.allReadings.collectAsStateWithLifecycle(initialValue = emptyList())
    var showEditDialog by remember { mutableStateOf(false) }
    var editingReading by remember { mutableStateOf<Reading?>(null) }

    // Edit Dialog
    if (showEditDialog && editingReading != null) {
        EditReadingDialog(
            reading = editingReading!!,
            onDismiss = {
                showEditDialog = false
                editingReading = null
            },
            onSave = { updatedReading ->
                viewModel.updateReading(updatedReading)
                showEditDialog = false
                editingReading = null
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "Reading History",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
        }

        items(readings) { reading ->
            ReadingCard(
                reading = reading,
                onEdit = {
                    editingReading = reading
                    showEditDialog = true
                },
                onDelete = {
                    viewModel.deleteReading(reading)
                }
            )
        }

        // Footer
        item {
            Spacer(modifier = Modifier.height(8.dp))
            VersionFooter()
        }
    }
}

@Composable
fun ReadingCard(
    reading: Reading,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFormatter = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (reading.type == "blood_sugar") "Blood Sugar" else "Ketones",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${reading.value} ${reading.unit}",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Row {
                    IconButton(onClick = onEdit) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit Reading"
                        )
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete Reading",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.DateRange,
                        contentDescription = "Date",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = dateFormatter.format(reading.date),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Text(
                    text = timeFormatter.format(reading.date),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            if (reading.notes.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Notes: ${reading.notes}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditReadingDialog(
    reading: Reading,
    onDismiss: () -> Unit,
    onSave: (Reading) -> Unit
) {
    var editedValue by remember { mutableStateOf(reading.value.toString()) }
    var editedNotes by remember { mutableStateOf(reading.notes) }
    var editedDate by remember { mutableStateOf(reading.date) }
    var editedUnit by remember { mutableStateOf(reading.unit) }

    // Date and Time picker states
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    val calendar = Calendar.getInstance()
    calendar.time = editedDate

    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = editedDate.time
    )

    val timePickerState = rememberTimePickerState(
        initialHour = calendar.get(Calendar.HOUR_OF_DAY),
        initialMinute = calendar.get(Calendar.MINUTE)
    )

    val dateFormatter = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())

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
                            editedDate = newCalendar.time
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
                        newCalendar.time = editedDate
                        newCalendar.set(Calendar.HOUR_OF_DAY, timePickerState.hour)
                        newCalendar.set(Calendar.MINUTE, timePickerState.minute)
                        editedDate = newCalendar.time
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Edit ${if (reading.type == "blood_sugar") "Blood Sugar" else "Ketone"} Reading")
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Value Input
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = editedValue,
                        onValueChange = { editedValue = it },
                        label = { Text("Value") },
                        modifier = Modifier.weight(1f)
                    )

                    // Unit Selection for Blood Sugar
                    if (reading.type == "blood_sugar") {
                        Column {
                            Row {
                                FilterChip(
                                    onClick = { editedUnit = "mg/dL" },
                                    label = { Text("mg/dL") },
                                    selected = editedUnit == "mg/dL"
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                FilterChip(
                                    onClick = { editedUnit = "mmol/L" },
                                    label = { Text("mmol/L") },
                                    selected = editedUnit == "mmol/L"
                                )
                            }
                        }
                    } else {
                        Text(
                            text = editedUnit,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                // Date and Time Selection
                Column {
                    Text(
                        text = "Date & Time",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
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
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = dateFormatter.format(editedDate),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        // Time Button
                        OutlinedButton(
                            onClick = { showTimePicker = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = timeFormatter.format(editedDate),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                // Notes Input
                OutlinedTextField(
                    value = editedNotes,
                    onValueChange = { editedNotes = it },
                    label = { Text("Notes") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val numericValue = editedValue.toDoubleOrNull()
                    if (numericValue != null) {
                        val updatedReading = reading.copy(
                            value = numericValue,
                            unit = editedUnit,
                            date = editedDate,
                            notes = editedNotes
                        )
                        onSave(updatedReading)
                    }
                },
                enabled = editedValue.toDoubleOrNull() != null
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
