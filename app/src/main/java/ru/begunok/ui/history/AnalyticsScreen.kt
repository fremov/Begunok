package ru.begunok.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import ru.begunok.data.WorkoutRepository
import ru.begunok.ui.Format
import ru.begunok.ui.theme.ZoneColors
import ru.begunok.workout.Analytics
import ru.begunok.workout.ExerciseCatalog
import ru.begunok.workout.ExerciseStat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

class AnalyticsViewModel(repository: WorkoutRepository) : ViewModel() {
    val analytics: StateFlow<Analytics?> = combine(repository.observeFinished(), repository.observeAllSets()) { workouts, sets ->
        Analytics.build(workouts, sets, LocalDate.now())
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

private val RunColor = Color(0xFFFC4C02)
private val dayMonth = DateTimeFormatter.ofPattern("d.MM", Locale.forLanguageTag("ru"))

@Composable
fun AnalyticsScreen(viewModel: AnalyticsViewModel) {
    val analytics by viewModel.analytics.collectAsStateWithLifecycle()
    val a = analytics ?: return
    if (a.total == 0) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text("Аналитика появится после первой тренировки", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            Tile("${a.thisWeek} из ${a.weekPlan}", "на этой неделе", Modifier.weight(1f))
            Tile("${a.total}", "всего тренировок", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth()) {
            Tile(Format.distanceKm(a.totalDistanceM), "всего км", Modifier.weight(1f))
            Tile(Format.duration(a.totalTimeMs), "общее время", Modifier.weight(1f))
        }
        a.daysSinceLast?.let { days ->
            val text = when (days) {
                0 -> "Последняя тренировка сегодня"
                1 -> "Последняя тренировка вчера"
                else -> "Последняя тренировка $days дн. назад"
            }
            Text(
                if (days >= 2) "$text. Не пропускай дважды!" else text,
                style = MaterialTheme.typography.titleMedium,
                color = if (days >= 2) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }

        ChartCard("Тренировки по неделям", "план ${a.weekPlan} в неделю") {
            BarChart(
                values = a.weeks.map { it.workouts.toFloat() },
                labels = a.weeks.map { it.start.format(dayMonth) },
                color = MaterialTheme.colorScheme.primary,
                format = { it.roundToInt().toString() },
                modifier = Modifier.fillMaxWidth().height(140.dp),
            )
        }
        ChartCard("Километры по неделям", null) {
            BarChart(
                values = a.weeks.map { (it.distanceM / 1000).toFloat() },
                labels = a.weeks.map { it.start.format(dayMonth) },
                color = RunColor,
                format = { String.format(Locale.forLanguageTag("ru"), "%.1f", it) },
                modifier = Modifier.fillMaxWidth().height(140.dp),
            )
        }

        if (a.runs.isNotEmpty()) {
            Text("Бег", style = MaterialTheme.typography.titleLarge)
            if (a.programRuns > 0) {
                Text("Интервальные программы выполнены полностью: ${a.programCompleted} из ${a.programRuns}")
            }
            val last = a.runs.last()
            ChartCard("Дистанция, км", "последняя ${Format.distanceKm(last.distanceM)}") {
                TrendChart(
                    values = a.runs.map { (it.distanceM / 1000).toFloat() },
                    color = RunColor,
                    format = { String.format(Locale.forLanguageTag("ru"), "%.1f", it) },
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                )
            }
            ChartCard("Средний темп, мин/км", "последний ${Format.pace(last.paceSecPerKm)} · ниже — быстрее") {
                TrendChart(
                    values = a.runs.map { it.paceSecPerKm?.toFloat() },
                    color = RunColor,
                    format = { Format.pace(it.toDouble()) },
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                )
            }
            ChartCard("Средний пульс", "последний ${Format.heartRate(last.avgHr)}") {
                TrendChart(
                    values = a.runs.map { it.avgHr?.toFloat() },
                    color = ZoneColors.Above,
                    format = { it.roundToInt().toString() },
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                )
            }
        }

        if (a.exercises.isNotEmpty()) {
            Text("Упражнения", style = MaterialTheme.typography.titleLarge)
            Text(
                "Линия — лучший подход, пунктир — цель",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            a.exercises.forEach { ExerciseCard(it) }
        }
    }
}

@Composable
private fun ExerciseCard(stat: ExerciseStat) {
    val unit = if (stat.timed) " с" else ""
    val last = stat.last
    val level = ExerciseCatalog.level(stat.key.substringAfter(':'))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stat.name, style = MaterialTheme.typography.titleMedium)
            Text(
                listOfNotNull(stat.day?.label, level?.let { "ступень ${it.first} из ${it.second}" }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Последний раз: ${last.reps.joinToString(" · ") { "$it$unit" }} (цель ${last.target}$unit) " +
                    if (last.success) "✓" else "✗",
                color = if (last.success) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
            )
            Text("Лучший подход: ${stat.best}$unit · тренировок: ${stat.sessions.size}", style = MaterialTheme.typography.bodyMedium)
            if (stat.sessions.size >= 2) {
                TrendChart(
                    values = stat.sessions.map { it.best.toFloat() },
                    reference = stat.sessions.map { it.target.toFloat() },
                    color = MaterialTheme.colorScheme.primary,
                    format = { "${it.roundToInt()}$unit" },
                    modifier = Modifier.fillMaxWidth().height(110.dp),
                )
            }
        }
    }
}

@Composable
private fun ChartCard(title: String, subtitle: String?, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

@Composable
private fun Tile(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
