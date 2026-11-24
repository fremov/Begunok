package ru.begunok.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import ru.begunok.health.HealthAutoSync
import ru.begunok.activity.ActivityCalendarPeriod
import ru.begunok.activity.ActivityCalendar
import ru.begunok.data.ShowerRepository
import ru.begunok.data.WorkoutRepository
import ru.begunok.ui.Format
import ru.begunok.ui.common.appear
import ru.begunok.ui.theme.StageColors
import ru.begunok.ui.theme.numberStyle
import ru.begunok.workout.countsDistance
import ru.begunok.workout.Period
import ru.begunok.workout.Repetitions
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class AnalyticsViewModel internal constructor(
    repository: WorkoutRepository,
    showers: ShowerRepository,
    calendar: ActivityCalendar,
    healthAutoSync: HealthAutoSync,
) : ViewModel() {
    private val activityReload = MutableStateFlow(0)
    private val _activitySelection = MutableStateFlow(ActivityCalendarPeriod())
    internal val activitySelection = _activitySelection.asStateFlow()
    private val healthChanges = healthAutoSync.historyChanges.map { Unit }

    private val stepTotal = combine(activityReload, healthChanges) { _, _ -> Unit }
        .flatMapLatest {
            flow {
                val today = LocalDate.now()
                val days = healthAutoSync.dailySteps(Period.Month.start(today), today)
                emit(days.takeIf { it.isNotEmpty() }?.sumOf { it.count })
            }.catch { emit(null) }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    internal val dailyActivity = observeDailyActivity(
        calendar, healthAutoSync, activitySelection, activityReload, healthChanges,
    ).flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DailyActivityTableState())

    fun reloadDailyActivity() {
        activityReload.value += 1
    }

    internal fun selectActivity(value: ActivityCalendarPeriod) {
        _activitySelection.value = value.copy(month = minOf(value.month, YearMonth.now()))
    }

    internal val analytics =
        combine(repository.observeFinished(), showers.observeAll(), repository.observeAllSets().map { Repetitions.byWorkout(it) }, stepTotal) { workouts, showerSessions, reps, steps ->
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val week = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val start = checkNotNull(Period.Month.start(today))
            fun day(millis: Long) = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
            var weekCount = 0
            var count = 0
            var meters = 0.0
            var millis = 0L
            var upperReps = 0L
            var lowerReps = 0L
            for (workout in workouts) {
                val date = day(workout.startTime)
                if (date < start || date > today) continue
                if (date >= week) weekCount++
                count++
                if (workout.countsDistance()) meters += workout.distanceM
                millis += workout.durationMs
                reps[workout.id]?.let {
                    upperReps += it.upper
                    lowerReps += it.lower
                }
            }
            val showerSeconds = showerSessions.sumOf { if (day(it.time) in start..today) it.durationSec.toLong() else 0L }
            ActivitySummary(weekCount, count, meters, millis, showerSeconds, steps, upperReps, lowerReps)
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

internal data class ActivitySummary(
    val thisWeek: Int,
    val total: Int,
    val totalDistanceM: Double,
    val totalTimeMs: Long,
    val coldShowerDurationSec: Long,
    val steps: Long?,
    val upperReps: Long,
    val lowerReps: Long,
)

private val ru = Locale.forLanguageTag("ru")

private fun km(meters: Double) = String.format(ru, "%.1f", meters / 1000)

@Composable
fun AnalyticsScreen(viewModel: AnalyticsViewModel) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ActivityContent(viewModel)
    }
}

@Composable
private fun ActivityContent(viewModel: AnalyticsViewModel) {
    val analytics by viewModel.analytics.collectAsStateWithLifecycle()
    val dailyActivity by viewModel.dailyActivity.collectAsStateWithLifecycle()
    val activitySelection by viewModel.activitySelection.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.reloadDailyActivity()
        onPauseOrDispose {}
    }
    val activityColors = activityColors()
    DailyActivityTable(dailyActivity, activitySelection, viewModel::selectActivity, viewModel::reloadDailyActivity)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        val a = analytics
        if (a == null) {
            Text("Загружаем тренировки…")
            return@Column
        }
        Row(Modifier.fillMaxWidth().appear(0), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile("${a.thisWeek}", "на этой неделе", Modifier.weight(1f), StageColors.Done)
            Tile("${a.total}", "за месяц", Modifier.weight(1f), activityColors.training)
        }
        Row(Modifier.fillMaxWidth().appear(1), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile(km(a.totalDistanceM), "км за месяц", Modifier.weight(1f), StageColors.Run)
            Tile(Format.duration(a.totalTimeMs), "время", Modifier.weight(1f), MaterialTheme.colorScheme.secondary)
        }
        Row(Modifier.fillMaxWidth().appear(2), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile(Format.duration(a.coldShowerDurationSec * 1000), "холодный душ", Modifier.weight(1f), activityColors.showers)
            Tile(a.steps?.toString() ?: "—", "шагов", Modifier.weight(1f), activityColors.steps)
        }
        Row(Modifier.fillMaxWidth().appear(3), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile(a.upperReps.toString(), "повторений · верх тела", Modifier.weight(1f), activityColors.training)
            Tile(a.lowerReps.toString(), "повторений · низ тела", Modifier.weight(1f), activityColors.training)
        }
    }
}



@Composable
internal fun Section(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}


@Composable
private fun Tile(value: String, label: String, modifier: Modifier = Modifier, accent: Color? = null) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(value, style = numberStyle(34), color = accent ?: MaterialTheme.colorScheme.onSurface)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
