package ru.begunok.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shower
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.launch
import ru.begunok.AppContainer
import ru.begunok.ui.common.LocalNavAnimatedScope
import ru.begunok.ui.common.LocalSharedTransitionScope
import ru.begunok.ui.history.AnalyticsScreen
import ru.begunok.ui.history.AnalyticsViewModel
import ru.begunok.ui.history.HistoryScreen
import ru.begunok.ui.history.HistoryViewModel
import ru.begunok.ui.home.HomeScreen
import ru.begunok.ui.home.HomeViewModel
import ru.begunok.ui.plan.PlanEditorScreen
import ru.begunok.ui.plan.PlanEditorViewModel
import ru.begunok.ui.sensor.SensorScreen
import ru.begunok.ui.sensor.SensorViewModel
import ru.begunok.ui.settings.SettingsScreen
import ru.begunok.ui.settings.SettingsViewModel
import ru.begunok.ui.shower.ShowerScreen
import ru.begunok.ui.shower.ShowerViewModel
import ru.begunok.ui.workout.WorkoutDetailScreen
import ru.begunok.ui.workout.WorkoutDetailViewModel
import ru.begunok.workout.DayType

private enum class Tab(val label: String, val icon: ImageVector) {
    Home("Тренировка", Icons.AutoMirrored.Filled.DirectionsRun),
    History("История", Icons.Filled.History),
    Analytics("Аналитика", Icons.Filled.Insights),
    Shower("Душ", Icons.Filled.Shower),
    Settings("Настройки", Icons.Filled.Settings),
}

private const val TABS = "tabs"
private const val SENSOR = "sensor"

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun BegunokNavigation(
    container: AppContainer,
    stopRequested: Boolean,
    onStopRequestHandled: () -> Unit,
) {
    val navController = rememberNavController()
    val pager = rememberPagerState { Tab.entries.size }
    val scope = rememberCoroutineScope()
    val backStack by navController.currentBackStackEntryAsState()
    val onTabs = backStack?.destination?.route == TABS

    fun openTab(tab: Tab) {
        if (!onTabs) navController.popBackStack(TABS, inclusive = false)
        scope.launch { pager.animateScrollToPage(tab.ordinal) }
    }

    LaunchedEffect(stopRequested) {
        if (stopRequested) openTab(Tab.Home)
    }

    BackHandler(enabled = onTabs && pager.currentPage != Tab.Home.ordinal) { openTab(Tab.Home) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = onTabs && pager.targetPage == tab.ordinal,
                        onClick = { openTab(tab) },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label, maxLines = 1) },
                    )
                }
            }
        },
    ) { padding ->
        SharedTransitionLayout(Modifier.padding(padding)) {
            CompositionLocalProvider(LocalSharedTransitionScope provides this) {
                AppNavHost(navController, pager, container, stopRequested, onStopRequestHandled) { openTab(it) }
            }
        }
    }
}

@Composable
private fun AppNavHost(
    navController: NavHostController,
    pager: PagerState,
    container: AppContainer,
    stopRequested: Boolean,
    onStopRequestHandled: () -> Unit,
    openTab: (Tab) -> Unit,
) {
    NavHost(
        navController,
        startDestination = TABS,
        enterTransition = { fadeIn(tween(320)) + slideInHorizontally(tween(380)) { it / 10 } },
        exitTransition = { fadeOut(tween(220)) },
        popEnterTransition = { fadeIn(tween(320)) },
        popExitTransition = { fadeOut(tween(220)) + slideOutHorizontally(tween(320)) { it / 10 } },
    ) {
        screen(TABS) {
            HorizontalPager(pager, key = { it }) { page ->
                when (Tab.entries[page]) {
                    Tab.Home -> HomeScreen(
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
                        onOpenSensor = { navController.navigate(SENSOR) },
                        onOpenWorkout = { id -> navController.navigate("workout/$id?celebrate=true") },
                        onEditPlan = { type -> navController.navigate("plan/${type.name}") },
                    )
                    Tab.History -> HistoryScreen(
                        viewModel = viewModel { HistoryViewModel(container.workouts) },
                        onOpen = { id -> navController.navigate("workout/$id") },
                    )
                    Tab.Analytics -> AnalyticsScreen(viewModel { AnalyticsViewModel(container.workouts) })
                    Tab.Shower -> ShowerScreen(
                        viewModel { ShowerViewModel(container.appContext, container.showers, container.settings, container.voicePack) },
                    )
                    Tab.Settings -> SettingsScreen(
                        viewModel {
                            SettingsViewModel(container.appContext, container.settings, container.voicePack, container.heartRateMonitor.state)
                        },
                        onOpenSensor = { navController.navigate(SENSOR) },
                    )
                }
            }
        }
        screen(SENSOR) {
            SensorScreen(
                viewModel = viewModel { SensorViewModel(container.heartRateMonitor, container.bleScanner, container.settings) },
                onBack = { navController.popBackStack() },
            )
        }
        screen(
            route = "plan/{type}",
            arguments = listOf(navArgument("type") { type = NavType.StringType }),
        ) { entry ->
            val type = DayType.entries.firstOrNull { it.name == entry.arguments?.getString("type") } ?: DayType.Run
            PlanEditorScreen(
                viewModel = viewModel(key = "plan-${type.name}") { PlanEditorViewModel(type, container.settings) },
                onBack = { navController.popBackStack() },
            )
        }
        screen(
            route = "workout/{id}?celebrate={celebrate}",
            arguments = listOf(
                navArgument("id") { type = NavType.LongType },
                navArgument("celebrate") {
                    type = NavType.BoolType
                    defaultValue = false
                },
            ),
        ) { entry ->
            val id = entry.arguments?.getLong("id") ?: 0L
            WorkoutDetailScreen(
                workoutId = id,
                celebrate = entry.arguments?.getBoolean("celebrate") == true,
                viewModel = viewModel(key = "workout-$id") {
                    WorkoutDetailViewModel(id, container.workouts, container.appContext)
                },
                onBack = { navController.popBackStack() },
                onHome = { openTab(Tab.Home) },
            )
        }
    }
}

private fun NavGraphBuilder.screen(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable (NavBackStackEntry) -> Unit,
) {
    composable(route, arguments) { entry ->
        CompositionLocalProvider(LocalNavAnimatedScope provides this) { content(entry) }
    }
}
