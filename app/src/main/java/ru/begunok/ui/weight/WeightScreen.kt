package ru.begunok.ui.weight

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.begunok.ui.common.AnimatedNumber
import ru.begunok.ui.common.appear
import ru.begunok.ui.history.ChartLine
import ru.begunok.ui.history.ChartRule
import ru.begunok.ui.history.MetricChart
import ru.begunok.ui.theme.numberStyle
import ru.begunok.weight.WeightGoal
import ru.begunok.weight.WeightPoint
import ru.begunok.weight.WeightStats
import ru.begunok.workout.plural
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

private val Scale = Color(0xFF7C5CFF)
private val ScaleDeep = Color(0xFF4F46E5)
private val ru = Locale.forLanguageTag("ru")
private val dayFormat = DateTimeFormatter.ofPattern("d MMMM", ru)
private val fullDayFormat = DateTimeFormatter.ofPattern("d MMMM yyyy", ru)

private enum class Range(val label: String, val days: Long?) {
    Month("30 дней", 30),
    Quarter("90 дней", 90),
    All("Всё", null),
}

@Composable
fun WeightScreen(viewModel: WeightViewModel) {
    val overview by viewModel.overview.collectAsStateWithLifecycle()
    var recording by rememberSaveable { mutableStateOf<Long?>(null) }
    var goalEditing by rememberSaveable { mutableStateOf(false) }
    val o = overview ?: return
    val today = LocalDate.now()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.appear(0)) { Hero(o, today, onRecord = { recording = today.toEpochDay() }) }
        if (o.points.isNotEmpty()) {
            Box(Modifier.appear(1)) { GoalCard(o, today, onEdit = { goalEditing = true }, onClear = viewModel::clearGoal) }
            Box(Modifier.appear(2)) { ChartCard(o) }
            Box(Modifier.appear(3)) { HistoryCard(o.points, today, onOpen = { recording = it.toEpochDay() }) }
        }
    }

    recording?.let { epochDay ->
        val day = LocalDate.ofEpochDay(epochDay)
        RecordDialog(
            initialDay = day,
            points = o.points,
            today = today,
            onDismiss = { recording = null },
            onSave = { d, kg ->
                recording = null
                viewModel.record(d, kg)
            },
            onDelete = { d ->
                recording = null
                viewModel.delete(d)
            },
        )
    }
    val current = o.current
    if (goalEditing && current != null) {
        GoalDialog(
            goal = o.goal,
            current = current.kg,
            today = today,
            onDismiss = { goalEditing = false },
            onSave = { target, deadline ->
                goalEditing = false
                viewModel.setGoal(target, deadline)
            },
        )
    }
}

@Composable
private fun Hero(o: WeightOverview, today: LocalDate, onRecord: () -> Unit) {
    val current = o.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(32.dp))
            .background(Brush.linearGradient(listOf(Scale, ScaleDeep)))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (current == null) {
            Icon(Icons.Filled.MonitorWeight, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
            Text("Запишите свой вес", style = MaterialTheme.typography.titleLarge, color = Color.White)
            Text(
                "Взвешивайтесь утром натощак — так вес меньше скачет. Приложение сгладит колебания, покажет тренд " +
                    "и посчитает, когда вы дойдёте до цели.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.9f),
            )
        } else {
            Text(
                "ВЕС · ${dayLabel(current.day, today)}".uppercase(ru),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.85f),
            )
            Row(verticalAlignment = Alignment.Bottom) {
                AnimatedNumber(kg(current.kg), numberStyle(56), color = Color.White)
                Text(" кг", style = MaterialTheme.typography.titleLarge, color = Color.White, modifier = Modifier.padding(bottom = 8.dp))
            }
            listOfNotNull(
                o.weekChange?.let { "${signedKg(it)} кг за неделю" },
                o.trendPerWeek?.let { "тренд ${signedKg(it)} кг/нед" },
            ).takeIf { it.isNotEmpty() }?.let {
                Text(it.joinToString(" · "), style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.92f))
            }
        }
        Button(
            onClick = onRecord,
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = ScaleDeep),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(" Записать вес")
        }
    }
}

@Composable
private fun PanelCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

@Composable
private fun GoalCard(o: WeightOverview, today: LocalDate, onEdit: () -> Unit, onClear: () -> Unit) {
    val goal = o.goal
    val status = o.status
    if (goal == null || status == null) {
        PanelCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Flag, contentDescription = null, tint = Scale)
                Text("  Цель по весу", style = MaterialTheme.typography.titleLarge)
            }
            Text(
                "Укажите, к какому весу хотите прийти и, если хотите, к какому сроку. Прогресс будет считаться от текущего веса.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onEdit, colors = ButtonDefaults.buttonColors(containerColor = Scale)) { Text("Поставить цель") }
        }
        return
    }
    PanelCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Цель ${kg(goal.targetKg)} кг", style = MaterialTheme.typography.titleLarge)
                Text(
                    if (goal.losing) "снижение веса" else "набор веса",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            GoalMenu(onEdit, onClear)
        }
        val fraction by animateFloatAsState(status.progress, tween(700), label = "goal")
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp),
            color = Scale,
            trackColor = Scale.copy(alpha = 0.15f),
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
        Row(Modifier.fillMaxWidth()) {
            Stat(kg(goal.startKg), "старт, кг", Modifier.weight(1f))
            Stat(if (status.reached) "✓" else kg(status.remainingKg), "осталось, кг", Modifier.weight(1f))
            Stat("${(status.progress * 100).toInt()}%", "пройдено", Modifier.weight(1f))
        }
        val lines = buildList {
            if (status.reached) add("Цель достигнута! Можно поставить следующую.")
            status.forecast?.let { date ->
                val days = ChronoUnit.DAYS.between(today, date).toInt()
                add("В текущем темпе — к ${date.format(dayFormat)} (≈ $days ${plural(days, "день", "дня", "дней")})")
            }
            if (!status.reached && status.forecast == null) {
                add(if (o.trendPerWeek == null) "Прогноз появится, когда наберётся неделя взвешиваний" else "Сейчас вес идёт не в сторону цели")
            }
            goal.deadline?.let { deadline ->
                when {
                    status.overdue -> add("Срок ${deadline.format(fullDayFormat)} прошёл — можно назначить новый")
                    status.neededPerWeek != null -> add(
                        "К ${deadline.format(fullDayFormat)}: нужно ${kg(status.neededPerWeek)} кг в неделю" +
                            if (status.neededPerWeek > SAFE_KG_PER_WEEK) " — это быстрее безопасного темпа" else "",
                    )
                }
            }
        }
        lines.forEach {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun GoalMenu(onEdit: () -> Unit, onClear: () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Действия") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Изменить цель") }, onClick = { open = false; onEdit() })
            DropdownMenuItem(text = { Text("Убрать цель") }, onClick = { open = false; onClear() })
        }
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier) {
    Column(modifier) {
        AnimatedNumber(value, numberStyle(28))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ChartCard(o: WeightOverview) {
    var range by rememberSaveable { mutableStateOf(Range.Month) }
    var selected by rememberSaveable(range) { mutableStateOf<Int?>(null) }
    val last = o.points.last().day
    val from = range.days?.let { last.minusDays(it - 1) }
    val indexes = o.points.indices.filter { from == null || o.points[it].day >= from }
    val points = indexes.map { o.points[it] }
    val smoothed = indexes.map { o.smoothed[it] }

    PanelCard {
        Text("Динамика", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Range.entries.forEach {
                FilterChip(selected = it == range, onClick = { range = it }, label = { Text(it.label) })
            }
        }
        val pick = selected?.takeIf { it in points.indices } ?: points.lastIndex
        Text(
            "${points[pick].day.format(fullDayFormat)}: ${kg(points[pick].kg)} кг · среднее за неделю ${kg(smoothed[pick])}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MetricChart(
            times = points.map { it.day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() },
            lines = listOf(
                ChartLine(points.map { it.kg.toFloat() }, Scale.copy(alpha = 0.4f), "вес"),
                ChartLine(smoothed.map { it.toFloat() }, Scale, "среднее", area = true),
            ),
            formatY = { String.format(ru, "%.1f", it) },
            rules = listOfNotNull(o.goal?.let { ChartRule(it.targetKg.toFloat(), "цель", ScaleDeep) }),
            selected = selected,
            onSelect = { selected = it },
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp),
        )
    }
}

@Composable
private fun HistoryCard(points: List<WeightPoint>, today: LocalDate, onOpen: (LocalDate) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val recent = points.asReversed()
    val shown = if (expanded) recent else recent.take(HISTORY_PREVIEW)
    PanelCard {
        Text("Взвешивания", style = MaterialTheme.typography.titleLarge)
        shown.forEachIndexed { i, point ->
            if (i > 0) HorizontalDivider()
            val previous = recent.getOrNull(i + 1)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(point.day) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(dayLabel(point.day, today), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                previous?.let {
                    Text(
                        signedKg(point.kg - it.kg),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 16.dp),
                    )
                }
                Text("${kg(point.kg)} кг", style = MaterialTheme.typography.titleMedium)
            }
        }
        if (recent.size > HISTORY_PREVIEW) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Свернуть" else "Показать все (${recent.size})")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordDialog(
    initialDay: LocalDate,
    points: List<WeightPoint>,
    today: LocalDate,
    onDismiss: () -> Unit,
    onSave: (LocalDate, Double) -> Unit,
    onDelete: (LocalDate) -> Unit,
) {
    var epochDay by rememberSaveable { mutableStateOf(initialDay.toEpochDay()) }
    val day = LocalDate.ofEpochDay(epochDay)
    val existing = points.firstOrNull { it.day == day }
    val prefill = (points.firstOrNull { it.day == initialDay } ?: points.lastOrNull())?.kg
    var text by rememberSaveable { mutableStateOf(prefill?.let(::kg).orEmpty()) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val value = WeightStats.parseKg(text)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing != null) "Вес за ${dayLabel(day, today)}" else "Записать вес") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    text, { text = it },
                    label = { Text("Вес, кг") },
                    isError = text.isNotBlank() && value == null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(-0.1, 0.1).forEach { step ->
                        OutlinedButton(
                            onClick = { (value ?: prefill)?.let { text = kg((it + step).coerceIn(WeightStats.MIN_KG, WeightStats.MAX_KG)) } },
                        ) {
                            Text(if (step < 0) "−0,1" else "+0,1")
                        }
                    }
                }
                TextButton(onClick = { picking = true }) { Text("Дата: ${day.format(fullDayFormat)}") }
                if (existing != null) {
                    Text(
                        "За этот день уже записано ${kg(existing.kg)} кг — новое значение его заменит",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = value != null, onClick = { onSave(day, value!!) }) { Text("Сохранить") }
        },
        dismissButton = {
            Row {
                if (existing != null) TextButton(onClick = { onDelete(day) }) { Text("Удалить") }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        },
    )

    if (picking) {
        DayPicker(
            initial = day,
            allow = { it <= today },
            onDismiss = { picking = false },
            onPick = {
                picking = false
                epochDay = it.toEpochDay()
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoalDialog(
    goal: WeightGoal?,
    current: Double,
    today: LocalDate,
    onDismiss: () -> Unit,
    onSave: (Double, LocalDate?) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(goal?.targetKg?.let(::kg).orEmpty()) }
    var deadline by rememberSaveable { mutableStateOf(goal?.deadline?.toEpochDay()) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val value = WeightStats.parseKg(text)
    val preview = value?.let { WeightStats.newGoal(goal, it, null, current) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Цель по весу") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Сейчас ${kg(current)} кг", style = MaterialTheme.typography.bodyLarge)
                OutlinedTextField(
                    text, { text = it },
                    label = { Text("Целевой вес, кг") },
                    isError = text.isNotBlank() && value == null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { picking = true }) {
                        Text(deadline?.let { "К сроку: ${LocalDate.ofEpochDay(it).format(fullDayFormat)}" } ?: "Добавить срок")
                    }
                    if (deadline != null) TextButton(onClick = { deadline = null }) { Text("Без срока") }
                }
                if (value != null && preview != null) {
                    val diff = value - current
                    Text(
                        when {
                            abs(diff) < 0.05 -> "Цель — удерживать текущий вес"
                            diff < 0 -> "Сбросить ${kg(-diff)} кг"
                            else -> "Набрать ${kg(diff)} кг"
                        } + if (goal != null && preview.startKg == goal.startKg) ", прогресс считается от ${kg(goal.startKg)} кг" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = value != null, onClick = { onSave(value!!, deadline?.let(LocalDate::ofEpochDay)) }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )

    if (picking) {
        DayPicker(
            initial = deadline?.let(LocalDate::ofEpochDay) ?: today.plusMonths(3),
            allow = { it > today },
            onDismiss = { picking = false },
            onPick = {
                picking = false
                deadline = it.toEpochDay()
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayPicker(initial: LocalDate, allow: (LocalDate) -> Boolean, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = allow(LocalDate.ofEpochDay(Math.floorDiv(utcTimeMillis, MS_PER_DAY)))
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedDateMillis != null,
                onClick = { onPick(LocalDate.ofEpochDay(Math.floorDiv(state.selectedDateMillis!!, MS_PER_DAY))) },
            ) { Text("Готово") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    ) {
        DatePicker(state)
    }
}

private fun dayLabel(day: LocalDate, today: LocalDate): String = when (day) {
    today -> "сегодня"
    today.minusDays(1) -> "вчера"
    else -> day.format(if (day.year == today.year) dayFormat else fullDayFormat)
}

private fun kg(value: Double) = String.format(ru, "%.1f", value)

private fun signedKg(value: Double) = when {
    value >= 0.05 -> "+${kg(value)}"
    value <= -0.05 -> "−${kg(-value)}"
    else -> "0,0"
}

private const val HISTORY_PREVIEW = 7
private const val SAFE_KG_PER_WEEK = 1.0
private const val MS_PER_DAY = 86_400_000L
