package ru.begunok.ui.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.begunok.activity.ActivityCalendarPeriod
import ru.begunok.activity.DailyActivityRow
import ru.begunok.ui.Format
import ru.begunok.ui.theme.numberStyle
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val activityLocale = Locale.forLanguageTag("ru")
private val activityDate = DateTimeFormatter.ofPattern("dd.MM.yyyy", activityLocale)
private val activityMonthTitle = DateTimeFormatter.ofPattern("LLLL yyyy", activityLocale)
private val activityMonths = listOf("янв", "фев", "мар", "апр", "май", "июн", "июл", "авг", "сен", "окт", "ноя", "дек")
private val activityLabels = listOf("Тренировки", "Учёба", "Чтение", "LoL", "Душ", "Шаги")

private fun DailyActivityRow.activityMask(): Int =
    (if (workouts > 0) 1 else 0) or (if (study.total > 0) 2 else 0) or
        (if (pages > 0) 4 else 0) or (if ((games ?: 0) > 0) 8 else 0) or
        (if (showerDurationSec > 0) 16 else 0) or (if ((steps ?: 0) > 0) 32 else 0)

private fun dayDescription(date: LocalDate, mask: Int): String {
    val labels = activityLabels.filterIndexed { index, _ -> mask and (1 shl index) != 0 }
    return "${date.format(activityDate)}: ${labels.joinToString().ifEmpty { "нет отмеченной активности" }}"
}

@Composable
internal fun DailyActivityTable(
    state: DailyActivityTableState,
    selection: ActivityCalendarPeriod,
    onSelection: (ActivityCalendarPeriod) -> Unit,
    onReload: () -> Unit,
) {
    val colors = activityColors()
    val today = LocalDate.now()
    val currentMonth = YearMonth.from(today)
    var pickingMonth by rememberSaveable { mutableStateOf(false) }
    var selectedDate by rememberSaveable(selection.month.toString(), selection.yearly) { mutableStateOf<String?>(null) }
    val selected = selectedDate?.let(LocalDate::parse)
    val ready = state.selection == selection
    val rows = if (ready) state.rows else emptyList()
    val byDay = remember(rows) { rows.associateBy { it.day } }
    if (pickingMonth) {
        ActivityMonthPicker(selection, currentMonth, onDismiss = { pickingMonth = false }) {
            onSelection(it)
            pickingMonth = false
        }
    }
    Section("Активность", null) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("Месяц", "Год").forEachIndexed { index, label ->
                SegmentedButton(
                    selected = selection.yearly == (index == 1),
                    onClick = { onSelection(selection.copy(yearly = index == 1)) },
                    shape = SegmentedButtonDefaults.itemShape(index, 2),
                    icon = {},
                ) { Text(label) }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onSelection(selection.previous()) }, enabled = selection.start.year > 1) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = if (selection.yearly) "Предыдущий год" else "Предыдущий месяц")
            }
            TextButton(onClick = { pickingMonth = true }, modifier = Modifier.weight(1f)) {
                val title = if (selection.yearly) "${selection.month.year}" else selection.month.format(activityMonthTitle).replaceFirstChar { it.titlecase(activityLocale) }
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            val canAdvance = if (selection.yearly) selection.month.year < today.year else selection.month < currentMonth
            IconButton(onClick = { onSelection(selection.next()) }, enabled = canAdvance) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = if (selection.yearly) "Следующий год" else "Следующий месяц")
            }
        }
        if (selection.month != currentMonth) {
            TextButton(onClick = { onSelection(ActivityCalendarPeriod(currentMonth, selection.yearly)) }) { Text("К текущему ${if (selection.yearly) "году" else "месяцу"}") }
        }
        if (selection.yearly) {
            ActivityYearGrid(selection, today, selected, byDay, colors) { selectedDate = if (it == selected) null else it.toString() }
        } else {
            ActivityMonthGrid(selection.month, today, selected, byDay, colors) { selectedDate = if (it == selected) null else it.toString() }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            activityLabels.chunked(3).forEachIndexed { row, labels ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    labels.forEachIndexed { column, label ->
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Spacer(Modifier.size(7.dp).clip(RoundedCornerShape(2.dp)).background(colors.all[row * 3 + column]))
                            Text(label, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, maxLines = 1)
                        }
                    }
                    if (labels.size < 3) Spacer(Modifier.weight((3 - labels.size).toFloat()))
                }
            }
        }
        when {
            !ready || state.loading -> Text("Загружаем активность…", style = MaterialTheme.typography.bodySmall)
            state.failed -> {
                Text("Не удалось загрузить активность", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onReload) { Text("Повторить") }
            }
            else -> {
                if (selected != null) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(selected.format(activityDate), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        IconButton(onClick = { selectedDate = null }) { Icon(Icons.Filled.Close, contentDescription = "Снять выбор дня") }
                    }
                } else {
                    Text(if (selection.yearly) "За год" else "За месяц", style = MaterialTheme.typography.labelLarge)
                }
                val summaryRows = if (selected == null) rows else listOfNotNull(byDay[selected])
                ActivityNumbers(summaryRows, colors, state.gamesLoading)
                Text(
                    if (selected == null) "Нажмите на день: все цвета показывают, чем вы занимались." else "Учёба: темы / задачи / повторы. LoL включает ARAM. Душ — время. — нет данных.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.gamesLoading) Text("Загружаем игры LoL…", style = MaterialTheme.typography.bodySmall)
                if (state.stepsLoading) Text("Загружаем шаги…", style = MaterialTheme.typography.bodySmall)
                state.stepsMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onReload) { Text("Обновить шаги") }
                }
                state.gamesMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onReload) { Text("Обновить") }
                }
            }
        }
    }
}

@Composable
private fun ActivityMonthGrid(
    month: YearMonth,
    today: LocalDate,
    selected: LocalDate?,
    byDay: Map<LocalDate, DailyActivityRow>,
    colors: ActivityColors,
    onSelect: (LocalDate) -> Unit,
) {
    val offset = month.atDay(1).dayOfWeek.value - 1
    val weekCount = (offset + month.lengthOfMonth() + 6) / 7
    val empty = MaterialTheme.colorScheme.surfaceContainerHigh
    val outline = MaterialTheme.colorScheme.primary
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс").forEach {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        repeat(weekCount) { week ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(7) { weekday ->
                    val number = week * 7 + weekday - offset + 1
                    if (number !in 1..month.lengthOfMonth()) {
                        Spacer(Modifier.weight(1f).height(40.dp))
                    } else {
                        val date = month.atDay(number)
                        val mask = byDay[date]?.activityMask() ?: 0
                        val future = date > today
                        val shape = RoundedCornerShape(7.dp)
                        val numberColor = when {
                            future -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                            Integer.bitCount(mask) == 1 -> colors.all[Integer.numberOfTrailingZeros(mask)]
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                        Column(
                            Modifier.weight(1f).height(40.dp).clip(shape)
                                .background(if (date == selected) outline.copy(alpha = 0.12f) else empty)
                                .then(if (date == selected || date == today) Modifier.border(1.dp, outline.copy(alpha = if (date == selected) 1f else 0.45f), shape) else Modifier)
                                .semantics { contentDescription = if (future) "${date.format(activityDate)}: будущий день" else dayDescription(date, mask) }
                                .clickable(enabled = !future) { onSelect(date) }
                                .padding(horizontal = 3.dp, vertical = 3.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(number.toString(), style = MaterialTheme.typography.labelLarge, fontWeight = if (mask != 0) FontWeight.Bold else FontWeight.Normal, color = numberColor)
                            ActivityBands(mask, colors, Modifier.fillMaxWidth().height(6.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityYearGrid(
    period: ActivityCalendarPeriod,
    today: LocalDate,
    selected: LocalDate?,
    byDay: Map<LocalDate, DailyActivityRow>,
    colors: ActivityColors,
    onSelect: (LocalDate) -> Unit,
) {
    val weeks = remember(period.month.year) {
        val monday = period.start.minusDays((period.start.dayOfWeek.value - 1).toLong())
        generateSequence(monday) { it.plusWeeks(1) }.takeWhile { it <= period.end }.toList()
    }
    val selectDay by rememberUpdatedState(onSelect)
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    val outline = MaterialTheme.colorScheme.onSurface
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = 1.5.dp
        val cell = ((maxWidth + gap) / weeks.size - gap).coerceAtMost(14.dp)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.fillMaxWidth().height(16.dp)) {
                weeks.forEachIndexed { index, monday ->
                    if ((index == 0 || monday.dayOfMonth <= 7) && index < weeks.size - 3) {
                        Text(activityMonths[monday.coerceAtLeast(period.start).monthValue - 1], Modifier.offset(x = (cell + gap) * index), style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, maxLines = 1)
                    }
                }
            }
            Row(
                Modifier.pointerInput(weeks, cell, gap, today) {
                    val stride = (cell.roundToPx() + gap.roundToPx()).toFloat()
                    detectTapGestures { point ->
                        val week = (point.x / stride).toInt()
                        val day = (point.y / stride).toInt()
                        if (week in weeks.indices && day in 0..6) {
                            val date = weeks[week].plusDays(day.toLong())
                            if (date in period.start..minOf(period.end, today)) selectDay(date)
                        }
                    }
                },
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                weeks.forEach { monday ->
                    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                        repeat(7) { weekday ->
                            val date = monday.plusDays(weekday.toLong())
                            if (date !in period.start..period.end) {
                                Spacer(Modifier.size(cell))
                            } else {
                                val mask = byDay[date]?.activityMask() ?: 0
                                ActivityBands(
                                    mask, colors,
                                    Modifier.size(cell).background(empty.copy(alpha = if (date > today) 0.3f else 1f))
                                        .then(if (date == selected) Modifier.border(1.dp, outline) else Modifier)
                                        .semantics {
                                            contentDescription = dayDescription(date, mask)
                                            if (date <= today) onClick(label = "Показать день") { selectDay(date); true }
                                        },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityBands(mask: Int, colors: ActivityColors, modifier: Modifier) {
    Canvas(modifier) {
        val count = Integer.bitCount(mask)
        if (count == 0) return@Canvas
        var band = 0
        val width = size.width / count
        for (index in colors.all.indices) {
            if (mask and (1 shl index) != 0) {
                drawRect(colors.all[index], topLeft = Offset(width * band, 0f), size = Size(width, size.height))
                band++
            }
        }
    }
}

@Composable
private fun ActivityNumbers(rows: List<DailyActivityRow>, colors: ActivityColors, gamesLoading: Boolean) {
    val gamesKnown = rows.isNotEmpty() && rows.all { it.games != null }
    val games = if (gamesKnown) rows.sumOf { it.games ?: 0 }.toString() else if (gamesLoading) "…" else "—"
    val steps = rows.mapNotNull { it.steps }.takeIf { it.isNotEmpty() }?.sum()?.toString() ?: "—"
    val values = listOf(
        rows.sumOf { it.workouts }.toString(),
        rows.sumOf { it.study.total }.toString(),
        "${rows.sumOf { it.pages }} стр.", games,
        Format.duration(rows.sumOf { it.showerDurationSec } * 1000), steps,
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        values.chunked(3).forEachIndexed { row, cells ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                cells.forEachIndexed { column, value ->
                    val index = row * 3 + column
                    Column(Modifier.weight(1f)) {
                        Text(if (index == 0) "Трен." else activityLabels[index], style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(value, style = numberStyle(if (index == 1) 18 else 23), color = colors.all[index])
                        val detail = when (index) {
                            1 -> "${rows.sumOf { it.study.topics }}т · ${rows.sumOf { it.study.tasks }}з · ${rows.sumOf { it.study.reviews }}п"
                            3 -> if (gamesKnown && rows.any { (it.games ?: 0) > 0 })
                                "${rows.sumOf { it.gameDurationSec ?: 0L } / 60} мин" else null
                            else -> null
                        }
                        detail?.let { Text(it, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, color = colors.all[index]) }
                    }
                }
                if (cells.size < 3) Spacer(Modifier.weight((3 - cells.size).toFloat()))
            }
        }
    }
}

@Composable
private fun ActivityMonthPicker(
    selection: ActivityCalendarPeriod,
    current: YearMonth,
    onDismiss: () -> Unit,
    onSelect: (ActivityCalendarPeriod) -> Unit,
) {
    var year by rememberSaveable { mutableIntStateOf(selection.month.year) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Выбрать месяц") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { year-- }, enabled = year > 1) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Предыдущий год выбора") }
                    Text(year.toString(), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { year++ }, enabled = year < current.year) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Следующий год выбора") }
                }
                (1..12).chunked(3).forEach { months ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        months.forEach { month ->
                            val value = YearMonth.of(year, month)
                            FilterChip(
                                selected = value == selection.month,
                                onClick = { onSelect(ActivityCalendarPeriod(value, yearly = false)) },
                                enabled = value <= current,
                                label = { Text(activityMonths[month - 1]) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                if (selection.yearly) TextButton(onClick = { onSelect(ActivityCalendarPeriod(YearMonth.of(year, selection.month.monthValue), yearly = true)) }) { Text("Показать весь $year год") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}
