package ru.begunok.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ru.begunok.health.HealthDay
import ru.begunok.health.HealthHistory
import ru.begunok.health.HealthHistoryDay
import ru.begunok.health.HealthSyncState
import ru.begunok.ui.theme.StageColors
import ru.begunok.ui.theme.ZoneColors
import ru.begunok.ui.theme.numberStyle
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

internal data class HealthHistoryUi(val data: HealthHistory? = null, val message: String? = null)

private val bandDateTime = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
private val bandDate = DateTimeFormatter.ofPattern("dd.MM.yyyy")
private fun Instant.bandTime(): String = atZone(ZoneId.systemDefault()).format(bandDateTime)
private fun hours(duration: Duration?): String = duration?.let { "%d:%02d".format(it.toHours(), it.toMinutes() % 60) } ?: "—"

@Composable
internal fun HealthAnalyticsContent(saved: HealthHistoryUi, sync: HealthSyncState, feelings: MentalAnalyticsState) {
    val history = saved.data
    val today = LocalDate.now()
    val current = sync.day?.takeIf { sync.updatedAt?.atZone(ZoneId.systemDefault())?.toLocalDate() == today }
    val stored = history?.days?.lastOrNull()
    val day = current ?: stored?.data
    val latestHr = sync.day?.lastHr ?: history?.heartRates?.lastOrNull() ?: stored?.data?.lastHr
    val latestOxygen = sync.day?.spo2 ?: history?.oxygen?.lastOrNull() ?: stored?.data?.spo2
    val latestStress = current?.lastStress ?: history?.stress?.lastOrNull() ?: stored?.data?.lastStress
    val latestVitality = current?.lastVitality ?: history?.vitality?.lastOrNull() ?: stored?.data?.lastVitality
    val lastFeeling = feelings.data?.entries?.lastOrNull()?.takeIf {
        Instant.ofEpochMilli(it.completedAt).atZone(ZoneId.systemDefault()).toLocalDate() == (if (current != null) today else stored?.date)
    }
    Section("Автоматическое отслеживание") {
        val readAt = sync.updatedAt ?: stored?.readAt
        Text(readAt?.let { "Последнее чтение: ${it.bandTime()}" } ?: "Ожидаем первое чтение")
        latestHr?.let {
            Text("Последний пульс: ${it.bpm} уд/мин · ${it.time.bandTime()}")
            if (Duration.between(it.time, Instant.now()) > Duration.ofMinutes(15)) {
                Text("Новых измерений пульса от источника нет", color = MaterialTheme.colorScheme.error)
            }
        }
        latestOxygen?.let { Text("Последний SpO₂: ${it.percent.roundToInt()}% · ${it.time.bandTime()}") }
        latestStress?.let { Text("Последний стресс: ${it.score} · ${it.time.bandTime()}") }
        latestVitality?.let { Text("Витальность Xiaomi: ${"%.1f".format(it.score)} · ${it.time.bandTime()}") }
        lastFeeling?.let { Text("Отметка самочувствия: ${Instant.ofEpochMilli(it.completedAt).bandTime()}") }
        sync.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        saved.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    if (day != null) {
        Text(if (current != null || stored?.date == today) "Сегодня" else stored?.date?.format(bandDate).orEmpty(), style = MaterialTheme.typography.titleLarge)
        val totals = day.totals
        val items = listOf(
            "Шаги" to (totals.steps?.toString() ?: "—"),
            "Активные ккал" to (totals.activeKcal?.roundToInt()?.toString() ?: "—"),
            "Пульс ср., уд/мин" to (totals.avgHr?.toString() ?: "—"),
            "Пульс мин–макс" to (if (totals.minHr != null && totals.maxHr != null) "${totals.minHr}–${totals.maxHr}" else "—"),
            "Последний SpO₂, %" to (day.spo2?.percent?.roundToInt()?.toString() ?: "—"),
            "Сон, ч:мин" to hours(day.sleep),
            "Расстояние, км" to (day.distanceMeters?.let { "%.2f".format(it / 1000) } ?: "—"),
            "Каденс, шаг/мин" to (day.avgStepCadence?.roundToInt()?.toString() ?: "—"),
            "Стресс браслета, 0–100" to (latestStress?.score?.toString() ?: "—"),
            "Витальность Xiaomi, баллы" to (latestVitality?.score?.let { "%.1f".format(it) } ?: "—"),
            "Настроение, ваша оценка" to (lastFeeling?.mood?.let { "$it / 10" } ?: "—"),
            "Энергия, ваша оценка" to (lastFeeling?.energy?.let { "$it / 10" } ?: "—"),
        )
        items.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (label, value) ->
                    Card(Modifier.weight(1f)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(value, style = numberStyle(30), color = MaterialTheme.colorScheme.primary)
                            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }
    }
    if (history == null) {
        if (saved.message == null) Text("Загружаем сохранённые показатели…")
        return
    }
    if (history.days.isEmpty() && history.heartRates.isEmpty() && history.oxygen.isEmpty()) {
        Text("Сохранённых показателей пока нет")
        return
    }
    val pulse = remember(history.heartRates) { history.heartRates.map { it.time to it.bpm.toFloat() } }
    val oxygen = remember(history.oxygen) { history.oxygen.map { it.time to it.percent.toFloat() } }
    SampleBandChart("Пульс, уд/мин", pulse, ZoneColors.Above) { it.roundToInt().toString() }
    SampleBandChart("SpO₂, %", oxygen, MaterialTheme.colorScheme.tertiary) { it.roundToInt().toString() }
    BraceletExtrasAnalyticsContent(saved, sync)
    DailyBandChart("Шаги по дням", history.days, StageColors.Walk, { it.totals.steps?.toFloat() }) { it.roundToInt().toString() }
    DailyBandChart("Активные калории, ккал", history.days, StageColors.Work, { it.totals.activeKcal?.toFloat() }) { it.roundToInt().toString() }
    DailyBandChart("Расстояние, км", history.days, StageColors.Run, { it.distanceMeters?.div(1000)?.toFloat() }) { "%.2f".format(it) }
    DailyBandChart("Сон, ч", history.days, MaterialTheme.colorScheme.secondary, { it.sleep?.seconds?.div(3600f) }) { "%.1f".format(it) }
    DailyBandChart("Каденс, шаг/мин", history.days, MaterialTheme.colorScheme.primary, { it.avgStepCadence?.toFloat() }) { it.roundToInt().toString() }
    Section("Пульс по дням, уд/мин") {
        val days = history.days
        if (days.none { it.data.totals.avgHr != null }) {
            Text("Нет сохранённых измерений")
        } else {
            MetricChart(
                times = days.map { it.date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() },
                lines = listOf(
                    ChartLine(days.map { it.data.totals.minHr?.toFloat() }, MaterialTheme.colorScheme.secondary, "мин."),
                    ChartLine(days.map { it.data.totals.avgHr?.toFloat() }, ZoneColors.Above, "ср."),
                    ChartLine(days.map { it.data.totals.maxHr?.toFloat() }, MaterialTheme.colorScheme.tertiary, "макс."),
                ),
                chronologicalX = true,
                formatY = { it.roundToInt().toString() },
                modifier = Modifier.fillMaxWidth().height(190.dp),
            )
            Legend(listOf(MaterialTheme.colorScheme.secondary to "мин.", ZoneColors.Above to "ср.", MaterialTheme.colorScheme.tertiary to "макс."))
        }
    }
}

@Composable
private fun DailyBandChart(title: String, days: List<HealthHistoryDay>, color: Color, value: (HealthDay) -> Float?, format: (Float) -> String) {
    Section(title) {
        if (days.none { value(it.data) != null }) {
            Text("Нет сохранённых измерений")
        } else {
            MetricChart(
                times = days.map { it.date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() },
                lines = listOf(ChartLine(days.map { value(it.data) }, color, title, area = true)),
                chronologicalX = true,
                formatY = format,
                modifier = Modifier.fillMaxWidth().height(180.dp),
            )
        }
    }
}

/** Paging keeps each original measurement selectable without drawing years of samples at once. */
@Composable
private fun SampleBandChart(title: String, samples: List<Pair<Instant, Float>>, color: Color, format: (Float) -> String) {
    Section(title) {
        if (samples.isEmpty()) {
            Text("Нет сохранённых измерений")
        } else {
            val pageCount = (samples.size + 299) / 300
            var pageFromLatest by rememberSaveable(title) { mutableIntStateOf(0) }
            val page = pageFromLatest.coerceIn(0, pageCount - 1)
            val end = (samples.size - page * 300).coerceAtLeast(1)
            val start = (end - 300).coerceAtLeast(0)
            val points = remember(samples, start, end) { samples.subList(start, end) }
            var selected by rememberSaveable(title, points.first().first.toEpochMilli(), points.size) { mutableIntStateOf(points.lastIndex) }
            val index = selected.coerceIn(points.indices)
            Text("${points[index].first.bandTime()} · ${format(points[index].second)}", style = MaterialTheme.typography.titleMedium)
            MetricChart(
                times = points.map { it.first.toEpochMilli() },
                lines = listOf(ChartLine(points.map { it.second }, color, title)),
                chronologicalX = true,
                formatY = format,
                selected = index,
                onSelect = { selected = it },
                modifier = Modifier.fillMaxWidth().height(190.dp),
            )
            if (pageCount > 1) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { pageFromLatest = page + 1 }, enabled = page < pageCount - 1) { Text("Раньше") }
                    Text("${start + 1}–$end / ${samples.size}")
                    TextButton(onClick = { pageFromLatest = page - 1 }, enabled = page > 0) { Text("Позже") }
                }
            }
        }
    }
}

@Composable
internal fun BraceletExtrasAnalyticsContent(saved: HealthHistoryUi, sync: HealthSyncState) {
    val history = saved.data
    val stress = remember(history?.stress) { history?.stress.orEmpty().map { it.time to it.score.toFloat() } }
    val vitality = remember(history?.vitality) { history?.vitality.orEmpty().map { it.time to it.score.toFloat() } }
    SampleBandChart("Стресс браслета, 0–100", stress, MaterialTheme.colorScheme.tertiary) { it.roundToInt().toString() }
    SampleBandChart("Витальность Xiaomi: активность за 7 дней", vitality, StageColors.Work) { "%.1f".format(it) }
    val latest = sync.day?.lastVitality ?: history?.vitality?.lastOrNull()
    latest?.earned?.let { earned ->
        Section("Витальность за день") {
            Text("%.1f балла".format(earned), style = numberStyle(30), color = MaterialTheme.colorScheme.primary)
            Text(latest.time.bandTime())
        }
    }
}
