package ru.begunok.ui.math

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.begunok.math.MathActivityEvent
import ru.begunok.math.MathActivityKind
import ru.begunok.math.MathDayActivity
import ru.begunok.math.mathActivityByDay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val activityDateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy")
private val monthFormat = DateTimeFormatter.ofPattern("LLL", Locale.forLanguageTag("ru"))

@Composable
internal fun MathActivity(events: List<MathActivityEvent>, today: LocalDate) {
    val byDay = remember(events) { mathActivityByDay(events) }
    val topics = remember(events) { events.filter { it.kind == MathActivityKind.Topic }.map { it.lessonId }.distinct().size }
    val tasks = remember(events) { events.count { it.kind == MathActivityKind.Task } }
    val reviews = remember(events) { events.count { it.kind == MathActivityKind.Review } }
    val recent = remember(byDay, today) { byDay.entries.filter { it.key <= today }.sortedByDescending { it.key }.take(14) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Учебная активность", style = MaterialTheme.typography.titleLarge)
            Text("За всё время: $topics тем впервые · $tasks задач отмечено вами · $reviews повторений", style = MaterialTheme.typography.bodyMedium)
            Text("365 дней · ${today.minusDays(364).format(activityDateFormat)} — ${today.format(activityDateFormat)}", style = MaterialTheme.typography.bodySmall)
            MathHeatmap(byDay, today)
            Text(
                "Цвет — число действий: впервые пройденные темы, отмеченные вами задачи и оценки повторения. Открытие урока не считается занятием.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            Text("Последние ${recent.size} активных дней", style = MaterialTheme.typography.titleMedium)
            if (recent.isEmpty()) {
                Text("Активность появится после отметки темы, задачи или повторения.", style = MaterialTheme.typography.bodyMedium)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Дата", Modifier.weight(1.6f), style = MaterialTheme.typography.labelMedium)
                    Text("Темы", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                    Text("Задачи", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                    Text("Повторы", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                }
                recent.forEach { (day, counts) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(day.format(activityDateFormat), Modifier.weight(1.6f), style = MaterialTheme.typography.bodySmall)
                        Text(counts.topics.toString(), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Text(counts.tasks.toString(), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Text(counts.reviews.toString(), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun MathHeatmap(activity: Map<LocalDate, MathDayActivity>, today: LocalDate) {
    val start = today.minusDays(364)
    val firstMonday = start.minusDays((start.dayOfWeek.value - 1).toLong())
    val weeks = remember(today) {
        generateSequence(firstMonday) { it.plusWeeks(1) }.takeWhile { it <= today }.toList()
    }
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    val primary = MaterialTheme.colorScheme.primary
    fun cellColor(count: Int): Color = when {
        count == 0 -> empty
        count == 1 -> primary.copy(alpha = 0.25f)
        count <= 3 -> primary.copy(alpha = 0.45f)
        count <= 6 -> primary.copy(alpha = 0.7f)
        else -> primary
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState(Int.MAX_VALUE)), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Spacer(Modifier.height(20.dp))
                listOf("Пн", "", "Ср", "", "Пт", "", "").forEach { label ->
                    Text(label, Modifier.height(14.dp).width(24.dp), style = MaterialTheme.typography.labelSmall)
                }
            }
            weeks.forEachIndexed { index, monday ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val showMonth = index == 0 || monday.month != weeks[index - 1].month
                    Text(
                        if (showMonth) monday.coerceAtLeast(start).format(monthFormat) else "",
                        modifier = Modifier.width(14.dp).height(20.dp),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Visible,
                    )
                    repeat(7) { dayIndex ->
                        val day = monday.plusDays(dayIndex.toLong())
                        if (day < start || day > today) {
                            Spacer(Modifier.size(14.dp))
                        } else {
                            val counts = activity[day] ?: MathDayActivity()
                            Spacer(
                                Modifier.size(14.dp).background(cellColor(counts.total)).semantics {
                                    contentDescription = "${day.format(activityDateFormat)}: тем ${counts.topics}, задач ${counts.tasks}, повторений ${counts.reviews}; всего ${counts.total}"
                                },
                            )
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0 to "0", 1 to "1", 2 to "2–3", 4 to "4–6", 7 to "7+").forEach { (count, label) ->
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Spacer(Modifier.size(12.dp).background(cellColor(count)))
                    Text(label, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
