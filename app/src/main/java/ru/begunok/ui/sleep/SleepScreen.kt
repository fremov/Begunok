package ru.begunok.ui.sleep

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.begunok.health.compareSleepPhases
import ru.begunok.ui.history.Section
import java.time.Duration
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val dateFormat = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("ru"))

private fun span(duration: Duration): String {
    val minutes = (duration.seconds / 60.0).roundToInt()
    return if (minutes >= 60) "${minutes / 60} ч ${minutes % 60} мин" else "$minutes мин"
}

private fun reference(current: Duration?, base: Duration?): String {
    if (base == null) return "нет данных"
    if (current == null) return span(base)
    val minutes = ((current - base).toMillis() / 60_000.0).roundToInt()
    return span(base) + when {
        minutes > 0 -> " (сейчас +$minutes мин)"
        minutes < 0 -> " (сейчас −${-minutes} мин)"
        else -> " (без разницы до минуты)"
    }
}

@Composable
internal fun SleepScreen(viewModel: SleepViewModel, onSettings: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        when (val data = state) {
            SleepState.Loading -> Text("Загружаем историю сна…")
            SleepState.Failed -> {
                Text("Не удалось прочитать историю сна.")
                TextButton(onClick = viewModel::retry) { Text("Повторить") }
            }
            is SleepState.Ready -> {
                val reports = data.reports
                if (reports.isEmpty()) {
                    Text("Пока нет записей сна. Проверьте, передаёт ли устройство сон в Health Connect и разрешено ли MyLife его читать.")
                    TextButton(onClick = onSettings) { Text("Настройки") }
                    return@Column
                }
                var selected by rememberSaveable { mutableStateOf<String?>(null) }
                val index = reports.indexOfFirst { it.date.toString() == selected }.takeIf { it >= 0 } ?: reports.lastIndex
                val night = reports[index]
                val previous = reports.getOrNull(index - 1)
                val next = reports.getOrNull(index + 1)
                val comparison = remember(reports, index) { compareSleepPhases(reports, index) }
                Text("Ночь на ${night.date.format(dateFormat)}", style = MaterialTheme.typography.headlineSmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(enabled = previous != null, onClick = { selected = previous?.date.toString() }) { Text("Предыдущая") }
                    TextButton(enabled = next != null, onClick = { selected = next?.date.toString() }) { Text("Следующая") }
                }
                Section("Оценка MyLife") {
                    val score = night.score
                    Text(score?.let { "${it.value} / 100" } ?: "Нет оценки", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                    Text("Длительность сна: ${span(night.recordedSleep)}")
                    if (score == null) Text("Нужна разметка без пробелов и противоречий и хотя бы одна детальная фаза сна.")
                    else Text("Сон занял ${(score.efficiency * 100).roundToInt()}% сеанса · внутренних пробуждений: ${score.internalAwakenings}")
                    var explain by rememberSaveable { mutableStateOf(false) }
                    TextButton(onClick = { explain = !explain }) { Text(if (explain) "Скрыть пояснение" else "Как считается оценка") }
                    if (explain) Text("До 50 баллов за длительность (8 часов), до 30 за долю сна в сеансе и до 20 за непрерывность (минус 4 за внутреннее пробуждение). Это ориентир MyLife, не оценка Xiaomi и не медицинский показатель.")
                }
                Section("Фазы сна") {
                    if (night.intervals.isEmpty()) Text("Сохранена только длительность, интервалов нет.") else {
                        SleepTimeline(night, data.zone)
                        var show by rememberSaveable(night.date.toString()) { mutableStateOf(false) }
                        TextButton(onClick = { show = !show }) { Text(if (show) "Скрыть интервалы" else "Показать интервалы") }
                        if (show) for (interval in night.intervals) Text("${clock(interval.start, data.zone)}–${clock(interval.end, data.zone)} · ${phaseLabel(interval.kind)}")
                    }
                }
                Section("Сравнение по фазам") {
                    previous?.let { Text("Предыдущая ночь: ${it.date.format(dateFormat)}") }
                    for ((phase, values) in comparison) {
                        HorizontalDivider()
                        Text("${phaseLabel(phase)} · ${values.current?.let(::span) ?: "нет данных"}", style = MaterialTheme.typography.titleMedium)
                        Text("Предыдущая: ${reference(values.current, values.previous)}")
                        Text("Среднее за 7 дней: ${reference(values.current, values.weeklyMean)}")
                    }
                    Text("В среднее входят только ночи, где фаза записана.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
