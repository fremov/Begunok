package ru.begunok.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.unit.dp
import ru.begunok.health.SleepNight
import ru.begunok.health.SleepStageKind
import ru.begunok.ui.theme.numberStyle
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val sleepDate = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("ru"))
private val sleepTimestamp = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.forLanguageTag("ru"))

@Composable
internal fun SleepAnalyticsContent(nights: List<SleepNight>) {
    val ordered = remember(nights) { nights.sortedWith(compareBy<SleepNight> { it.start }.thenBy { it.end }) }
    Section("Сон") {
        if (ordered.isEmpty()) {
            Text("За выбранный период нет записей сна.", color = MaterialTheme.colorScheme.onSurface)
        } else {
            var selected by rememberSaveable(ordered.first().start, ordered.last().end, ordered.size) {
                mutableIntStateOf(ordered.lastIndex)
            }
            val index = selected.coerceIn(ordered.indices)
            val night = ordered[index]
            val zone = ZoneId.systemDefault()
            val startDate = night.start.atZone(zone).toLocalDate()
            val endDate = night.end.atZone(zone).toLocalDate()
            Text(
                if (startDate == endDate) startDate.format(sleepDate)
                else "${startDate.format(sleepDate)} — ${endDate.format(sleepDate)}",
                style = MaterialTheme.typography.titleMedium,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { selected = index - 1 }, enabled = index > 0) { Text("Раньше") }
                TextButton(onClick = { selected = index + 1 }, enabled = index < ordered.lastIndex) { Text("Позже") }
            }
            Text("Запись ${index + 1} из ${ordered.size}")
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(sleepDurationText(night.sleepDuration), style = numberStyle(32), color = MaterialTheme.colorScheme.primary)
                Text("Время сна", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            }
            SleepMetric("Начало", night.start.sleepTimestamp(zone))
            SleepMetric("Окончание", night.end.sleepTimestamp(zone))
            SleepMetric("Бодрствование", sleepDurationText(night.awakeDuration))
            SleepMetric("Пробуждения", night.awakeningCount?.toString() ?: "—")

            val hasDetailedStages = night.stages.any {
                it.end > it.start && it.start < night.end && it.end > night.start &&
                    it.kind in detailedSleepStages
            }
            Text("Фазы сна", style = MaterialTheme.typography.titleMedium)
            listOf(
                "Глубокий сон" to SleepStageKind.DEEP,
                "Лёгкий сон" to SleepStageKind.LIGHT,
                "Быстрый сон (REM)" to SleepStageKind.REM,
            ).forEach { (label, kind) ->
                SleepMetric(label, sleepDurationText(if (hasDetailedStages) night.stageDuration(kind) else null))
            }
            val unspecifiedSleep = night.stageDuration(SleepStageKind.SLEEPING)
            if (unspecifiedSleep != null && !unspecifiedSleep.isZero) {
                SleepMetric("Сон без уточнения фазы", sleepDurationText(unspecifiedSleep))
            }
            if (!hasDetailedStages) {
                Text("Подробные фазы сна в этой записи отсутствуют.", color = MaterialTheme.colorScheme.onSurface)
            }
            if (night.source.isNotBlank()) {
                Text("Источник: ${night.source}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
    if (ordered.isNotEmpty()) {
        val average = remember(ordered) {
            ordered.fold(Duration.ZERO) { total, night -> total.plus(night.sleepDuration) }
                .dividedBy(ordered.size.toLong())
        }
        Section("Сон за выбранный период") {
            SleepMetric("Записей сна", ordered.size.toString())
            SleepMetric("Средняя длительность сна", sleepDurationText(average))
        }
    }
}

private val detailedSleepStages = setOf(SleepStageKind.LIGHT, SleepStageKind.DEEP, SleepStageKind.REM)

@Composable
private fun SleepMetric(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

private fun Instant.sleepTimestamp(zone: ZoneId): String = atZone(zone).format(sleepTimestamp)

private fun sleepDurationText(duration: Duration?): String {
    if (duration == null) return "—"
    val minutes = duration.toMinutes()
    if (minutes == 0L && !duration.isZero) return "< 1 мин"
    val hours = minutes / 60
    val remaining = minutes % 60
    return if (hours == 0L) "$remaining мин" else "$hours ч $remaining мин"
}
