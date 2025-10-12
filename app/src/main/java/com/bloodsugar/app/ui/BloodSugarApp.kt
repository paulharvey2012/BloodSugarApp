package com.bloodsugar.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.bloodsugar.app.ui.components.AppHeader

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
                TopAppBar(
                    title = {
                        Text(
                            text = when (currentDestination?.route) {
                                "add_reading" -> "Add Reading"
                                "history" -> "Reading History"
                                else -> "Add Reading"
                            }
                        )
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
            }
        }
    }
}