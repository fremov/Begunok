package ru.begunok.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shower
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import ru.begunok.ui.health.HealthScreen
import ru.begunok.ui.health.HealthViewModel
import ru.begunok.ui.history.AnalyticsScreen
import ru.begunok.ui.history.AnalyticsViewModel
import ru.begunok.ui.history.HistoryScreen
import ru.begunok.ui.history.HistoryViewModel
import ru.begunok.ui.home.HomeScreen
import ru.begunok.ui.home.HomeViewModel
import ru.begunok.ui.plan.PlanEditorScreen
import ru.begunok.ui.plan.PlanEditorViewModel
import ru.begunok.ui.reading.ReadingScreen
import ru.begunok.ui.reading.ReadingViewModel
import ru.begunok.ui.sensor.SensorScreen
import ru.begunok.ui.sensor.SensorViewModel
import ru.begunok.ui.settings.SettingsScreen
import ru.begunok.ui.settings.SettingsViewModel
import ru.begunok.ui.shower.ShowerScreen
import ru.begunok.ui.shower.ShowerViewModel
import ru.begunok.ui.weight.WeightScreen
import ru.begunok.ui.weight.WeightViewModel
import ru.begunok.ui.workout.WorkoutDetailScreen
import ru.begunok.ui.workout.WorkoutDetailViewModel
import ru.begunok.workout.DayType
import ru.begunok.workout.WorkoutService

private enum class Group(val label: String?) {
    Training("Тренировки"),
    Habits("Привычки"),
    Health("Здоровье"),
    App(null),
}

private enum class Section(val label: String, val icon: ImageVector, val group: Group) {
    Workout("Тренировка", Icons.AutoMirrored.Filled.DirectionsRun, Group.Training),
    History("История", Icons.Filled.History, Group.Training),
    Analytics("Аналитика", Icons.Filled.Insights, Group.Training),
    Shower("Холодный душ", Icons.Filled.Shower, Group.Habits),
    Reading("Чтение", Icons.AutoMirrored.Filled.MenuBook, Group.Habits),
    Health("Браслет и показатели", Icons.Filled.MonitorHeart, Group.Health),
    Weight("Вес", Icons.Filled.MonitorWeight, Group.Health),
    Settings("Настройки", Icons.Filled.Settings, Group.App),
}

private const val TABS = "tabs"
private const val SENSOR = "sensor"

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3Api::class)
@Composable
fun BegunokNavigation(
    container: AppContainer,
    stopRequested: Boolean,
    onStopRequestHandled: () -> Unit,
) {
    val navController = rememberNavController()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var section by rememberSaveable { mutableStateOf(Section.Workout) }
    val backStack by navController.currentBackStackEntryAsState()
    val onTabs = backStack?.destination?.route == TABS

    fun open(target: Section) {
        if (!onTabs) navController.popBackStack(TABS, inclusive = false)
        section = target
        scope.launch { drawer.close() }
    }

    LaunchedEffect(stopRequested) {
        if (stopRequested) open(Section.Workout)
    }

    BackHandler(enabled = onTabs && section != Section.Workout) { open(Section.Workout) }
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = onTabs || drawer.isOpen,
        drawerContent = { DrawerContent(section, onSelect = ::open) },
    ) {
        Scaffold(
            topBar = {
                if (onTabs) {
                    TopAppBar(
                        title = { Text(section.label) },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawer.toggle() } }) {
                                Icon(Icons.Filled.Menu, contentDescription = "Меню")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    )
                }
            },
        ) { padding ->
            SharedTransitionLayout(Modifier.padding(padding)) {
                CompositionLocalProvider(LocalSharedTransitionScope provides this) {
                    AppNavHost(navController, section, container, stopRequested, onStopRequestHandled) { open(it) }
                }
            }
        }
    }
}

private suspend fun DrawerState.toggle() = if (isOpen) close() else open()

@Composable
private fun DrawerContent(current: Section, onSelect: (Section) -> Unit) {
    val workout by WorkoutService.state.collectAsStateWithLifecycle()
    ModalDrawerSheet {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 16.dp),
        ) {
            Text("MyLife", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 16.dp))
            Text(
                "бег, тренировки и привычки",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(12.dp))
            Section.entries.groupBy { it.group }.forEach { (group, items) ->
                if (group.label != null) {
                    Text(
                        group.label,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 6.dp),
                    )
                } else {
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                }
                items.forEach { item ->
                    NavigationDrawerItem(
                        label = { Text(item.label) },
                        icon = { Icon(item.icon, contentDescription = null) },
                        selected = item == current,
                        onClick = { onSelect(item) },
                        badge = if (item == Section.Workout && workout.isActive) {
                            { Text("идёт", color = MaterialTheme.colorScheme.primary) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AppNavHost(
    navController: NavHostController,
    section: Section,
    container: AppContainer,
    stopRequested: Boolean,
    onStopRequestHandled: () -> Unit,
    open: (Section) -> Unit,
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
            AnimatedContent(
                targetState = section,
                transitionSpec = {
                    (fadeIn(tween(300)) + slideInHorizontally(tween(320)) { it / 12 }) togetherWith fadeOut(tween(180))
                },
                label = "section",
            ) { current ->
                SectionContent(current, navController, container, stopRequested, onStopRequestHandled)
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
                    WorkoutDetailViewModel(id, container.workouts, container.appContext, container.health)
                },
                onBack = { navController.popBackStack() },
                onHome = { open(Section.Workout) },
            )
        }
    }
}

@Composable
private fun SectionContent(
    section: Section,
    navController: NavHostController,
    container: AppContainer,
    stopRequested: Boolean,
    onStopRequestHandled: () -> Unit,
) {
    when (section) {
        Section.Workout -> HomeScreen(
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
        Section.History -> HistoryScreen(
            viewModel = viewModel { HistoryViewModel(container.workouts) },
            onOpen = { id -> navController.navigate("workout/$id") },
        )
        Section.Analytics -> AnalyticsScreen(viewModel { AnalyticsViewModel(container.workouts) })
        Section.Shower -> ShowerScreen(
            viewModel { ShowerViewModel(container.appContext, container.showers, container.settings, container.voicePack) },
        )
        Section.Reading -> ReadingScreen(viewModel { ReadingViewModel(container.reading, container.settings) })
        Section.Health -> HealthScreen(viewModel { HealthViewModel(container.health) })
        Section.Weight -> WeightScreen(viewModel { WeightViewModel(container.weight, container.settings) })
        Section.Settings -> SettingsScreen(
            viewModel {
                SettingsViewModel(container.appContext, container.settings, container.voicePack, container.heartRateMonitor.state)
            },
            onOpenSensor = { navController.navigate(SENSOR) },
        )
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
