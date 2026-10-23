package ru.begunok.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import ru.begunok.ui.workout.WorkoutDetailScreen
import ru.begunok.ui.workout.WorkoutDetailViewModel
import ru.begunok.AppContainer
import ru.begunok.ui.history.HistoryScreen
import ru.begunok.ui.history.HistoryViewModel
import ru.begunok.ui.home.HomeScreen
import ru.begunok.ui.home.HomeViewModel
import ru.begunok.ui.sensor.SensorScreen
import ru.begunok.ui.sensor.SensorViewModel
import ru.begunok.ui.settings.SettingsScreen
import ru.begunok.ui.settings.SettingsViewModel

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    Home("home", "Тренировка", Icons.AutoMirrored.Filled.DirectionsRun),
    History("history", "История", Icons.Filled.History),
    Sensor("sensor", "Датчик", Icons.Filled.Bluetooth),
    Settings("settings", "Настройки", Icons.Filled.Settings),
}

@Composable
fun BegunokNavigation(
    container: AppContainer,
    stopRequested: Boolean,
    onStopRequestHandled: () -> Unit,
) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    fun openTab(tab: Tab) {
        if (tab == Tab.Home) {
            navController.popBackStack(Tab.Home.route, inclusive = false)
            return
        }
        navController.navigate(tab.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    LaunchedEffect(stopRequested) {
        if (stopRequested && currentRoute != Tab.Home.route) openTab(Tab.Home)
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = { openTab(tab) },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(navController, startDestination = Tab.Home.route, modifier = Modifier.padding(padding)) {
            composable(Tab.Home.route) {
                HomeScreen(
                    viewModel = viewModel {
                        HomeViewModel(
                            container.appContext,
                            container.heartRateMonitor,
                            container.settings,
                            container.workouts,
                            container.locationClient,
                        )
                    },
                    stopRequested = stopRequested,
                    onStopRequestHandled = onStopRequestHandled,
                    onOpenSensor = { openTab(Tab.Sensor) },
                    onOpenWorkout = { id -> navController.navigate("workout/$id") },
                )
            }
            composable(
                route = "workout/{id}",
                arguments = listOf(navArgument("id") { type = NavType.LongType }),
            ) { entry ->
                val id = entry.arguments?.getLong("id") ?: 0L
                WorkoutDetailScreen(
                    viewModel = viewModel(key = "workout-$id") {
                        WorkoutDetailViewModel(id, container.workouts, container.appContext)
                    },
                    onBack = { navController.popBackStack() },
                    onHome = { openTab(Tab.Home) },
                )
            }
            composable(Tab.History.route) {
                HistoryScreen(
                    viewModel = viewModel { HistoryViewModel(container.workouts) },
                    onOpen = { id -> navController.navigate("workout/$id") },
                )
            }
            composable(Tab.Sensor.route) {
                SensorScreen(viewModel { SensorViewModel(container.heartRateMonitor, container.bleScanner, container.settings) })
            }
            composable(Tab.Settings.route) {
                SettingsScreen(viewModel { SettingsViewModel(container.appContext, container.settings, container.voicePack) })
            }
        }
    }
}
