package ru.begunok.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import ru.begunok.data.WorkoutRepository
import ru.begunok.ui.Format
import ru.begunok.ui.common.appear
import ru.begunok.ui.theme.StageColors
import ru.begunok.ui.theme.ZoneColors
import ru.begunok.ui.theme.numberStyle
import ru.begunok.workout.Analytics
import ru.begunok.workout.ExerciseCatalog
import ru.begunok.workout.ExerciseStat
import ru.begunok.workout.Period
import ru.begunok.workout.RunPoint
import ru.begunok.workout.WeekSummary
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

class AnalyticsViewModel(repository: WorkoutRepository) : ViewModel() {
    private val _period = MutableStateFlow(Period.Month)
    val period: StateFlow<Period> = _period.asStateFlow()

    val analytics: StateFlow<Analytics?> =
        combine(repository.observeFinished(), repository.observeAllSets(), _period) { workouts, sets, period ->
            Analytics.build(workouts, sets, LocalDate.now(), period = period)
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setPeriod(value: Period) {
        _period.value = value
    }
}

private val ru = Locale.forLanguageTag("ru")
private val dayMonth = DateTimeFormatter.ofPattern("d.MM", ru)
private val shortDay = SimpleDateFormat("d.MM", ru)
private val PaceColor = Color(0xFF6CB0FF)
private val HrColor = ZoneColors.Above

private fun km(meters: Double) = String.format(ru, "%.1f", meters / 1000)

@Composable
fun AnalyticsScreen(viewModel: AnalyticsViewModel) {
    val analytics by viewModel.analytics.collectAsStateWithLifecycle()
    val period by viewModel.period.collectAsStateWithLifecycle()
    val a = analytics ?: return
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Аналитика", style = MaterialTheme.typography.displaySmall)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Period.entries.forEachIndexed { i, p ->
                SegmentedButton(
                    selected = p == period,
                    onClick = { viewModel.setPeriod(p) },
                    shape = SegmentedButtonDefaults.itemShape(i, Period.entries.size),
                    icon = {},
                ) { Text(p.label) }
            }
        }
        if (a.total == 0 && a.daysSinceLast == null) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Text("Аналитика появится после первой тренировки", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }
        Row(Modifier.fillMaxWidth().appear(0), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile("${a.thisWeek}/${a.weekPlan}", "на этой неделе", Modifier.weight(1f), StageColors.Done)
            Tile("${a.total}", "тренировок", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().appear(1), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile(km(a.totalDistanceM), "км за период", Modifier.weight(1f), StageColors.Run)
            Tile(Format.duration(a.totalTimeMs), "время", Modifier.weight(1f))
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

        WeekCompare(a.currentWeek, a.previousWeek, Modifier.appear(2))

        Section("Тренировки по неделям", "столбец — неделя с понедельника, пунктир — план ${a.weekPlan} в неделю", Modifier.appear(3)) {
            BarChart(
                values = a.weeks.map { it.workouts.toFloat() },
                labels = a.weeks.map { it.start.format(dayMonth) },
                color = MaterialTheme.colorScheme.primary,
                format = { it.roundToInt().toString() },
                modifier = Modifier.fillMaxWidth().height(160.dp),
                goal = ChartRule(a.weekPlan.toFloat(), "план", StageColors.Done),
            )
        }
        Section("Километры по неделям", "сумма дистанции всех тренировок за неделю", Modifier.appear(4)) {
            BarChart(
                values = a.weeks.map { (it.distanceM / 1000).toFloat() },
                labels = a.weeks.map { it.start.format(dayMonth) },
                color = StageColors.Run,
                format = { String.format(ru, "%.1f", it) },
                modifier = Modifier.fillMaxWidth().height(160.dp),
            )
        }

        if (a.runs.isNotEmpty()) RunsSection(a)

        if (a.exercises.isNotEmpty()) {
            Text("Упражнения", style = MaterialTheme.typography.headlineSmall)
            a.exercises.forEach { ExerciseCard(it) }
        }
    }
}

@Composable
private fun RunsSection(a: Analytics) {
    val runs = a.runs
    var selected by rememberSaveable(runs.size) { mutableIntStateOf(runs.lastIndex) }
    val run = runs[selected.coerceIn(0, runs.lastIndex)]
    val times = runs.map { it.time }

    Text("Бег", style = MaterialTheme.typography.headlineSmall)
    if (a.programRuns > 0) {
        Text(
            "Интервальные программы выполнены полностью: ${a.programCompleted} из ${a.programRuns}",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    Text(
        "Каждая точка или столбец — одна пробежка. Нажмите или проведите пальцем по графику, чтобы выбрать пробежку.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SelectedRun(run, runs.getOrNull(selected - 1))

    val avgKm = (runs.map { it.distanceM }.average() / 1000).toFloat()
    Section("Дистанция", "км за пробежку · пунктир — среднее ${String.format(ru, "%.2f", avgKm)} км") {
        MetricChart(
            times = times,
            lines = emptyList(),
            bars = ChartLine(runs.map { (it.distanceM / 1000).toFloat() }, StageColors.Run, "км"),
            formatY = { String.format(ru, "%.1f", it) },
            rules = listOf(ChartRule(avgKm, "среднее", MaterialTheme.colorScheme.onSurfaceVariant)),
            selected = selected,
            onSelect = { selected = it },
            modifier = Modifier.fillMaxWidth().height(190.dp),
        )
    }

    val paces = runs.map { it.paceSecPerKm?.toFloat() }
    val avgPace = paces.filterNotNull().takeIf { it.isNotEmpty() }?.average()?.toFloat()
    Section("Средний темп", "мин/км · шкала перевёрнута: выше — быстрее" + (avgPace?.let { " · среднее ${Format.pace(it.toDouble())}" } ?: "")) {
        MetricChart(
            times = times,
            lines = listOf(ChartLine(paces, PaceColor, "темп", area = true)),
            formatY = { Format.pace(it.toDouble()) },
            invertY = true,
            rules = listOfNotNull(avgPace?.let { ChartRule(it, "среднее", MaterialTheme.colorScheme.onSurfaceVariant) }),
            selected = selected,
            onSelect = { selected = it },
            modifier = Modifier.fillMaxWidth().height(190.dp),
        )
    }

    val limit = runs.last().upperLimit.toFloat()
    Section("Пульс", "уд/мин · пунктир — верхняя граница ${limit.roundToInt()}") {
        MetricChart(
            times = times,
            lines = listOf(
                ChartLine(runs.map { it.maxHr?.toFloat() }, HrColor.copy(alpha = 0.45f), "макс."),
                ChartLine(runs.map { it.avgHr?.toFloat() }, HrColor, "средний", area = true),
            ),
            formatY = { it.roundToInt().toString() },
            rules = listOf(ChartRule(limit, "граница", ZoneColors.Above)),
            selected = selected,
            onSelect = { selected = it },
            modifier = Modifier.fillMaxWidth().height(190.dp),
        )
        Legend(listOf(HrColor to "средний", HrColor.copy(alpha = 0.45f) to "максимальный"))
    }

    Records(a)
    RunTable(runs, selected) { selected = it }
}

@Composable
private fun SelectedRun(run: RunPoint, previous: RunPoint?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                listOfNotNull(Format.dateTime(run.time), run.title).joinToString(" · "),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth()) {
                RunValue("км", Format.distanceKm(run.distanceM), previous?.let { delta((run.distanceM - it.distanceM) / 1000, 2, true) }, Modifier.weight(1f))
                RunValue("время", Format.duration(run.durationMs), null, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth()) {
                val paceDelta = if (run.paceSecPerKm != null && previous?.paceSecPerKm != null) {
                    val d = run.paceSecPerKm - previous.paceSecPerKm
                    Delta((if (d < 0) "быстрее на " else "медленнее на ") + Format.pace(abs(d)), d < 0)
                } else {
                    null
                }
                RunValue("темп, мин/км", Format.pace(run.paceSecPerKm), paceDelta, Modifier.weight(1f))
                RunValue("пульс ср./макс.", "${Format.heartRate(run.avgHr)}/${Format.heartRate(run.maxHr)}", null, Modifier.weight(1f))
            }
            if (previous != null) {
                Text(
                    "Сравнение с предыдущей пробежкой",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private data class Delta(val text: String, val good: Boolean)

private fun delta(value: Double, digits: Int, moreIsBetter: Boolean): Delta? {
    if (abs(value) < 0.5 * 10.0.pow(-digits)) return null
    val text = (if (value > 0) "▲ " else "▼ ") + String.format(ru, "%.${digits}f", abs(value))
    return Delta(text, (value > 0) == moreIsBetter)
}

@Composable
private fun RunValue(label: String, value: String, delta: Delta?, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, style = numberStyle(32))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        delta?.let {
            Text(it.text, style = MaterialTheme.typography.labelMedium, color = if (it.good) ZoneColors.Normal else MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun WeekCompare(current: WeekSummary, previous: WeekSummary, modifier: Modifier = Modifier) {
    Section("Неделя к неделе", "бег на этой неделе по сравнению с прошлой", modifier) {
        Row(Modifier.fillMaxWidth()) {
            Text("", Modifier.weight(1.3f))
            Text("эта", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text("прошлая", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        CompareRow("Пробежки", "${current.runs}", "${previous.runs}", (current.runs - previous.runs).toDouble(), true)
        CompareRow("Километры", km(current.distanceM), km(previous.distanceM), current.distanceM - previous.distanceM, true)
        CompareRow(
            "Темп",
            Format.pace(current.paceSecPerKm),
            Format.pace(previous.paceSecPerKm),
            if (current.paceSecPerKm != null && previous.paceSecPerKm != null) current.paceSecPerKm - previous.paceSecPerKm else 0.0,
            false,
        )
        CompareRow(
            "Пульс",
            Format.heartRate(current.avgHr),
            Format.heartRate(previous.avgHr),
            if (current.avgHr != null && previous.avgHr != null) (current.avgHr - previous.avgHr).toDouble() else 0.0,
            false,
        )
    }
}

@Composable
private fun CompareRow(label: String, current: String, previous: String, diff: Double, moreIsBetter: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1.3f), style = MaterialTheme.typography.bodyLarge)
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(current, style = numberStyle(22))
            if (abs(diff) > 1e-6) {
                Spacer(Modifier.width(4.dp))
                Text(
                    if (diff > 0) "▲" else "▼",
                    color = if ((diff > 0) == moreIsBetter) ZoneColors.Normal else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        Text(previous, Modifier.weight(1f), style = numberStyle(22), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Records(a: Analytics) {
    val r = a.records
    Section("Рекорды", "за всё время") {
        r.longest?.let { RecordRow("Самая длинная", "${Format.distanceKm(it.distanceM)} км", it.time) }
        r.fastest?.let { RecordRow("Самый быстрый темп (от 1 км)", "${Format.pace(it.paceSecPerKm)} /км", it.time) }
        r.longestTime?.let { RecordRow("Самая долгая", Format.duration(it.durationMs), it.time) }
    }
}

@Composable
private fun RecordRow(label: String, value: String, time: Long) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(Format.dateTime(time), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(value, style = numberStyle(24), color = MaterialTheme.colorScheme.tertiary)
    }
}

@Composable
private fun RunTable(runs: List<RunPoint>, selected: Int, onSelect: (Int) -> Unit) {
    val fastest = runs.filter { it.distanceM >= Analytics.MIN_RECORD_PACE_M }.mapNotNull { it.paceSecPerKm }.minOrNull()
    val longest = runs.maxOf { it.distanceM }
    Section("Все пробежки", "нажмите на строку, чтобы выделить её на графиках") {
        Row(Modifier.fillMaxWidth()) {
            listOf("дата" to 1f, "км" to 0.8f, "время" to 1f, "темп" to 0.9f, "пульс" to 0.8f).forEach { (h, w) ->
                Text(h, Modifier.weight(w), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider()
        runs.indices.reversed().forEach { i ->
            val r = runs[i]
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (i == selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 8.dp),
            ) {
                Text(shortDay.format(Date(r.time)), Modifier.weight(1f))
                Text(
                    Format.distanceKm(r.distanceM),
                    Modifier.weight(0.8f),
                    fontWeight = if (r.distanceM == longest) FontWeight.Bold else FontWeight.Normal,
                    color = if (r.distanceM == longest) MaterialTheme.colorScheme.tertiary else Color.Unspecified,
                )
                Text(Format.duration(r.durationMs), Modifier.weight(1f))
                Text(
                    Format.pace(r.paceSecPerKm),
                    Modifier.weight(0.9f),
                    fontWeight = if (r.paceSecPerKm == fastest) FontWeight.Bold else FontWeight.Normal,
                    color = if (r.paceSecPerKm != null && r.paceSecPerKm == fastest) MaterialTheme.colorScheme.tertiary else Color.Unspecified,
                )
                Text(Format.heartRate(r.avgHr), Modifier.weight(0.8f))
            }
        }
    }
}

@Composable
private fun ExerciseCard(stat: ExerciseStat) {
    val unit = if (stat.timed) " с" else ""
    val last = stat.last
    val level = ExerciseCatalog.level(stat.key.substringAfter(':'))
    var selected by rememberSaveable(stat.key, stat.sessions.size) { mutableIntStateOf(stat.sessions.lastIndex) }
    val session = stat.sessions[selected.coerceIn(0, stat.sessions.lastIndex)]
    Section(stat.name, listOfNotNull(stat.day?.label, level?.let { "ступень ${it.first} из ${it.second}" }).joinToString(" · ")) {
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(session.reps.joinToString(" · ") { "$it$unit" }, style = numberStyle(26))
                Text(
                    "${Format.dateTime(session.time)} · цель ${session.target}$unit " + if (session.success) "✓" else "✗",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (session.success) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${stat.best}$unit", style = numberStyle(26), color = MaterialTheme.colorScheme.tertiary)
                Text("лучший подход", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (stat.sessions.size >= 2) {
            MetricChart(
                times = stat.sessions.map { it.time },
                lines = listOf(
                    ChartLine(stat.sessions.map { it.target.toFloat() }, MaterialTheme.colorScheme.onSurfaceVariant, "цель", dashed = true),
                    ChartLine(stat.sessions.map { it.best.toFloat() }, MaterialTheme.colorScheme.primary, "лучший", area = true),
                ),
                formatY = { "${it.roundToInt()}$unit" },
                selected = selected,
                onSelect = { selected = it },
                modifier = Modifier.fillMaxWidth().height(150.dp),
            )
            Legend(listOf(MaterialTheme.colorScheme.primary to "лучший подход", MaterialTheme.colorScheme.onSurfaceVariant to "цель"))
        } else {
            Text(
                "График появится после второй тренировки с этим упражнением · последний раз ${last.reps.joinToString(" · ")}$unit",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Section(title: String, subtitle: String?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            subtitle?.takeIf { it.isNotEmpty() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
