package com.bloodsugar.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.bloodsugar.app.data.Reading
import com.bloodsugar.app.data.ReadingRepository
import com.bloodsugar.app.ui.components.AppHeader
import com.bloodsugar.app.ui.theme.BloodSugarAppTheme
import kotlinx.coroutines.flow.flowOf
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BloodSugarApp(viewModel: ReadingViewModel) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    Column(modifier = Modifier.fillMaxSize()) {
        // Header at the very top
        AppHeader()

        Scaffold(
            topBar = {
                var menuExpanded by remember { mutableStateOf(false) }

                TopAppBar(
                    title = {
                        Text(
                            text = when (currentDestination?.route) {
                                "add_reading" -> "Add Reading"
                                "history" -> "Reading History"
                                "settings" -> "Settings"
                                else -> "Add Reading"
                            }
                        )
                    },
                    actions = {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(text = { Text("Settings") }, onClick = {
                                menuExpanded = false
                                navController.navigate("settings") {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            })
                        }
                    }
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Add, contentDescription = "Add Reading") },
                        label = { Text("Add") },
                        selected = currentDestination?.route == "add_reading",
                        onClick = {
                            navController.navigate("add_reading") {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.List, contentDescription = "History") },
                        label = { Text("History") },
                        selected = currentDestination?.route == "history",
                        onClick = {
                            navController.navigate("history") {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = "add_reading",
                modifier = Modifier.padding(innerPadding)
            ) {
                composable("add_reading") {
                    AddReadingScreen(viewModel = viewModel)
                }
                composable("history") {
                    HistoryScreen(viewModel = viewModel)
                }
                composable("settings") {
                    SettingsScreen(viewModel = viewModel)
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun BloodSugarAppPreview() {
    BloodSugarAppTheme {
        val mockRepository = ReadingRepository(mockPreviewDao())
        val mockViewModel = ReadingViewModel(mockRepository)
        BloodSugarApp(viewModel = mockViewModel)
    }
}

@Preview(showBackground = true, name = "Dark Theme")
@Composable
fun BloodSugarAppDarkPreview() {
    BloodSugarAppTheme(darkTheme = true) {
        val mockRepository = ReadingRepository(mockPreviewDao())
        val mockViewModel = ReadingViewModel(mockRepository)
        BloodSugarApp(viewModel = mockViewModel)
    }
}

@Preview(showBackground = true)
@Composable
fun ReadingCardPreview() {
    BloodSugarAppTheme {
        ReadingCard(
            reading = Reading(
                id = 1,
                type = "blood_sugar",
                value = 120.0,
                unit = "mg/dL",
                date = Date(),
                notes = "Before breakfast"
            ),
            onEdit = { },
            onDelete = { }
        )
    }
}

@Preview(showBackground = true, name = "Ketone Card")
@Composable
fun KetoneReadingCardPreview() {
    BloodSugarAppTheme {
        ReadingCard(
            reading = Reading(
                id = 2,
                type = "ketone",
                value = 0.5,
                unit = "mmol/L",
                date = Date(),
                notes = "Morning reading with longer notes to test wrapping"
            ),
            onEdit = { },
            onDelete = { }
        )
    }
}

// Mock DAO for preview purposes
private fun mockPreviewDao(): com.bloodsugar.app.data.ReadingDao {
    return object : com.bloodsugar.app.data.ReadingDao {
        override fun getAllReadings() = flowOf(emptyList<Reading>())
        override suspend fun insertReading(reading: Reading) {}
        override suspend fun updateReading(reading: Reading) {}
        override suspend fun deleteReading(reading: Reading) {}
        override suspend fun getReadingById(id: Long): Reading? = null
    }
}
