package ru.begunok.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ru.begunok.mental.MentalAnalytics
import ru.begunok.mental.MentalAverages
import ru.begunok.mental.MentalQuestionnaireSeries
import ru.begunok.mental.MentalTest
import ru.begunok.ui.theme.StageColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

// A null model is loading unless the repository flow has failed.
data class MentalAnalyticsState(val data: MentalAnalytics? = null, val failed: Boolean = false)

private val mentalLocale = Locale.forLanguageTag("ru")
private val mentalDate = DateTimeFormatter.ofPattern("d.MM.yyyy", mentalLocale)
private val mentalTimestamp = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm:ss XXX", mentalLocale)
private fun timestamp(time: Long) = mentalTimestamp.format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()))
private fun decimal(value: Double) = String.format(mentalLocale, "%.1f", value)
private fun signed(value: Double) = String.format(mentalLocale, "%+.1f", value)
private fun dateRange(start: LocalDate, end: LocalDate) = "${start.format(mentalDate)} — ${end.format(mentalDate)}"

@Composable
internal fun MentalAnalyticsContent(state: MentalAnalyticsState, onRetry: () -> Unit) {
    val data = state.data
    if (state.failed) {
        Text("Не удалось загрузить психологическое состояние", color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onRetry) { Text("Повторить") }
        return
    }
    if (data == null) {
        Text("Загружаем записи…")
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Психологическое состояние", style = MaterialTheme.typography.headlineSmall)
        Text(
            data.start?.let { dateRange(it, data.endExclusive.minusDays(1)) } ?: "Вся история до ${data.endExclusive.minusDays(1).format(mentalDate)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (data.entries.isEmpty()) {
            Text("За этот период нет быстрых отметок.")
        } else {
            QuickSummary(data)
            QuickCharts(data)
            DaypartSummary(data)
        }
        Text("Опросники", style = MaterialTheme.typography.headlineSmall)
        data.questionnaires.forEach { QuestionnaireChart(it) }
    }
}

@Composable
internal fun FeelingsAnalyticsContent(state: MentalAnalyticsState, onRetry: () -> Unit, onRecord: () -> Unit) {
    Section("Настроение и энергия: ваши отметки") {
        TextButton(onClick = onRecord) { Text("Отметить состояние") }
        when {
            state.failed -> {
                Text("Не удалось загрузить отметки", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRetry) { Text("Повторить") }
            }
            state.data == null -> Text("Загружаем отметки…")
            state.data.entries.isEmpty() -> Text("За выбранный период отметок нет")
            else -> {
                val latest = state.data.entries.last()
                Text("Последняя отметка: ${timestamp(latest.completedAt)}")
                Text("Настроение: ${latest.mood} / 10")
                Text("Напряжение: ${latest.tension} / 10")
                Text("Энергия: ${latest.energy} / 10")
            }
        }
    }
    state.data?.takeIf { it.entries.isNotEmpty() }?.let { data ->
        QuickSummary(data)
        QuickCharts(data)
        DaypartSummary(data)
    }
}

@Composable
private fun QuickSummary(data: MentalAnalytics) {
    val summary = data.summary
    val average = summary.averages ?: return
    Section("Быстрые отметки") {
        Text("${summary.entries} записей · ${summary.observedDays} дней")
        AverageRows(average)
        val previous = data.previous
        if (previous != null) {
            Text("Предыдущий равный период: ${dateRange(requireNotNull(data.previousStart), requireNotNull(data.start).minusDays(1))}", style = MaterialTheme.typography.bodySmall)
            val prior = previous.averages
            if (prior == null) {
                Text("В предыдущем периоде нет отметок для сравнения.", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("${previous.entries} записей · ${previous.observedDays} дней с отметками", style = MaterialTheme.typography.bodySmall)
                AverageRows(prior, average - prior)
            }
        }
    }
}

@Composable
private fun AverageRows(values: MentalAverages, changes: MentalAverages? = null) {
    listOf(
        Triple("Настроение", values.mood, changes?.mood),
        Triple("Напряжение", values.tension, changes?.tension),
        Triple("Энергия", values.energy, changes?.energy),
    ).forEach { (label, value, change) ->
        Text("$label: ${decimal(value)} / 10" + (change?.let { " · в текущем периоде ${signed(it)} п." } ?: ""))
    }
}

@Composable
private fun QuickCharts(data: MentalAnalytics) {
    val entries = data.entries
    var selected by rememberSaveable(entries.firstOrNull()?.id, entries.lastOrNull()?.completedAt, entries.size) {
        mutableIntStateOf(entries.lastIndex)
    }
    val index = selected.coerceIn(0, entries.lastIndex)
    val entry = entries[index]
    val times = entries.map { it.completedAt }
    QuickMetric("Настроение", times, entries.map { it.mood.toFloat() }, StageColors.Done, index, entry.context) { selected = it }
    QuickMetric("Напряжение", times, entries.map { it.tension.toFloat() }, StageColors.Warmup, index, entry.context) { selected = it }
    QuickMetric("Энергия", times, entries.map { it.energy.toFloat() }, StageColors.Run, index, entry.context) { selected = it }
}

@Composable
private fun QuickMetric(title: String, times: List<Long>, values: List<Float>, color: Color, selected: Int, context: String?, onSelect: (Int) -> Unit) {
    Section(title) {
        MetricChart(
            times = times,
            lines = listOf(ChartLine(values, color, title)),
            formatY = { it.roundToInt().toString() },
            selected = selected,
            onSelect = onSelect,
            chronologicalX = true,
            valueRange = 0f..10f,
            modifier = Modifier.fillMaxWidth().height(190.dp),
        )
        Legend(listOf(color to "Одна отметка · 0–10"))
        PointSelection(selected, times.size, onSelect)
        Text("${timestamp(times[selected])} · ${values[selected].roundToInt()} / 10", style = MaterialTheme.typography.bodyMedium)
        context?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun DaypartSummary(data: MentalAnalytics) {
    Section("Утро и вечер") {
        val comparison = data.dayparts
        if (comparison == null) {
            Text("Нет парных утренних и вечерних отметок.")
        } else {
            Text("Утро · ${comparison.morning.entries} записей / ${comparison.morning.observedDays} дней", style = MaterialTheme.typography.titleSmall)
            AverageRows(requireNotNull(comparison.morning.averages))
            Text("Вечер · ${comparison.evening.entries} записей / ${comparison.evening.observedDays} дней", style = MaterialTheme.typography.titleSmall)
            AverageRows(requireNotNull(comparison.evening.averages))
        }
    }
}

@Composable
private fun QuestionnaireChart(series: MentalQuestionnaireSeries) {
    val test = series.test
    Section(test.title) {
        val latest = series.latest
        if (latest == null) {
            Text("За этот период нет результатов")
        } else {
            Text("Последний: ${latest.score} / ${test.maxScore}", style = MaterialTheme.typography.titleMedium)
            Text(timestamp(latest.completedAt), style = MaterialTheme.typography.bodySmall)
            val change = series.change
            if (change != null) {
                val direction = when {
                    change == 0 -> "без изменений"
                    test == MentalTest.WHO5 && change > 0 -> "самочувствие выше по шкале"
                    test == MentalTest.WHO5 -> "самочувствие ниже по шкале"
                    test == MentalTest.PSS10 && change > 0 -> "воспринимаемый стресс выше по шкале"
                    test == MentalTest.PSS10 -> "воспринимаемый стресс ниже по шкале"
                    change > 0 -> "выраженность симптомов выше по шкале"
                    else -> "выраженность симптомов ниже по шкале"
                }
                Text("${if (change > 0) "+" else ""}$change п. к предыдущему результату · $direction", style = MaterialTheme.typography.bodySmall)
                series.precedingLatest?.let { Text("Предыдущий: ${it.score} · ${timestamp(it.completedAt)}", style = MaterialTheme.typography.bodySmall) }
            }
            var selected by rememberSaveable(test.name, latest.id, series.results.size) { mutableIntStateOf(series.results.lastIndex) }
            val index = selected.coerceIn(0, series.results.lastIndex)
            val result = series.results[index]
            MetricChart(
                times = series.results.map { it.completedAt },
                lines = listOf(ChartLine(series.results.map { it.score.toFloat() }, MaterialTheme.colorScheme.primary, test.title)),
                formatY = { it.roundToInt().toString() },
                selected = index,
                onSelect = { selected = it },
                chronologicalX = true,
                valueRange = 0f..test.maxScore.toFloat(),
                modifier = Modifier.fillMaxWidth().height(190.dp),
            )
            Legend(listOf(MaterialTheme.colorScheme.primary to "Одно прохождение · 0–${test.maxScore}"))
            PointSelection(index, series.results.size, { selected = it })
            Text("${timestamp(result.completedAt)} · ${result.score} / ${test.maxScore}", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun PointSelection(index: Int, count: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onSelect(index - 1) }, enabled = index > 0) { Text("Раньше") }
        Text("${index + 1} / $count", style = MaterialTheme.typography.labelMedium)
        TextButton(onClick = { onSelect(index + 1) }, enabled = index < count - 1) { Text("Позже") }
    }
}
