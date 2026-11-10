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
        Text("Загружаем записи…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Психологическое состояние", style = MaterialTheme.typography.headlineSmall)
        Text(
            data.start?.let { dateRange(it, data.endExclusive.minusDays(1)) } ?: "Вся история до ${data.endExclusive.minusDays(1).format(mentalDate)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (data.entries.isEmpty()) {
            Text("За этот период нет быстрых отметок. Отмечайте настроение, напряжение и энергию в разделе «Психологическое состояние».")
        } else {
            QuickSummary(data)
            QuickCharts(data)
            DaypartSummary(data)
        }
        Text("Опросники", style = MaterialTheme.typography.headlineSmall)
        Text("Каждая шкала показана отдельно. Точка — одно прохождение; нажмите на график или используйте кнопки выбора.", style = MaterialTheme.typography.bodySmall)
        data.questionnaires.forEach { QuestionnaireChart(it) }
    }
}

@Composable
private fun QuickSummary(data: MentalAnalytics) {
    val summary = data.summary
    val average = summary.averages ?: return
    Section("Быстрые отметки", "${summary.entries} записей · ${summary.observedDays} дней с отметками") {
        Text("Средние за период: каждый день с отметками имеет одинаковый вес. Дни без записей не учитываются.", style = MaterialTheme.typography.bodySmall)
        AverageRows(average)
        val previous = data.previous
        if (previous == null) {
            Text("Для сравнения с предыдущим периодом выберите 4 недели или 3 месяца.", style = MaterialTheme.typography.bodySmall)
        } else {
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
    Text("Точка — одна отметка. Нажмите или проведите пальцем по графику, чтобы выбрать запись. Выбор общий для трёх графиков.", style = MaterialTheme.typography.bodySmall)
    val times = entries.map { it.completedAt }
    QuickMetric("Настроение", "0 — очень плохо · 10 — отлично", times, entries.map { it.mood.toFloat() }, StageColors.Done, index, entry.context) { selected = it }
    QuickMetric("Напряжение", "0 — нет напряжения · 10 — крайне сильное", times, entries.map { it.tension.toFloat() }, StageColors.Warmup, index, entry.context) { selected = it }
    QuickMetric("Энергия", "0 — совсем нет сил · 10 — много сил", times, entries.map { it.energy.toFloat() }, StageColors.Run, index, entry.context) { selected = it }
}

@Composable
private fun QuickMetric(title: String, subtitle: String, times: List<Long>, values: List<Float>, color: Color, selected: Int, context: String?, onSelect: (Int) -> Unit) {
    Section(title, "$subtitle. Расстояние между точками соответствует времени; промежутки без записей — не нули.") {
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
    Section("Утро и вечер", "Утро 06:00–11:59 · вечер 18:00–23:59 · местное время") {
        val comparison = data.dayparts
        if (comparison == null) {
            Text("Сравнение появится, когда хотя бы в двух днях будут и утренние, и вечерние отметки.", style = MaterialTheme.typography.bodySmall)
        } else {
            Text("Сравниваются только ${comparison.pairedDays} дней с обоими окнами, с одинаковым весом каждого дня.", style = MaterialTheme.typography.bodySmall)
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
    val higherMeans = when (test) {
        MentalTest.WHO5 -> "выше — лучшее самочувствие"
        MentalTest.PSS10 -> "выше — больше воспринимаемого стресса"
        MentalTest.GAD7 -> "выше — больше симптомов тревоги"
        MentalTest.PHQ9 -> "выше — больше симптомов сниженного настроения"
    }
    Section(test.title, "${test.subtitle} · 0–${test.maxScore} · $higherMeans") {
        val latest = series.latest
        if (latest == null) {
            Text("За этот период нет результатов", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text("Последний: ${latest.score} / ${test.maxScore}", style = MaterialTheme.typography.titleMedium)
            Text(timestamp(latest.completedAt), style = MaterialTheme.typography.bodySmall)
            val change = series.change
            if (change == null) {
                Text("Первый результат этой шкалы — сравнения пока нет", style = MaterialTheme.typography.bodySmall)
            } else {
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
