package ru.begunok.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.begunok.ui.Format
import ru.begunok.ui.theme.ZoneColors
import ru.begunok.workout.DayType
import ru.begunok.workout.Exercise
import ru.begunok.workout.ExerciseCatalog
import ru.begunok.workout.IntervalProgram
import ru.begunok.workout.LevelUp
import ru.begunok.workout.Progression
import ru.begunok.workout.StrengthView
import ru.begunok.workout.WorkoutState
import ru.begunok.workout.formatSpan

private val WorkColor = Color(0xFFFC4C02)
private val RestColor = Color(0xFF1E88E5)

@Composable
internal fun DayTypePicker(selected: DayType?, onSelect: (DayType?) -> Unit) {
    val options = listOf(null to "Бег", DayType.Run to DayType.Run.label, DayType.Strength to DayType.Strength.label)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (type, label) ->
            SegmentedButton(
                selected = selected == type,
                onClick = { onSelect(type) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                icon = {},
            ) { Text(label, textAlign = TextAlign.Center, maxLines = 2) }
        }
    }
}

@Composable
internal fun PlanCard(preview: PlanPreview, program: IntervalProgram?, onEdit: () -> Unit) {
    val plan = preview.plan
    val strength = plan.strength
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("План", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = null)
                    Text("  Упражнения")
                }
            }
            var n = 1
            if (plan.warmupSec > 0) Text("${n++}. Разминка ${formatSpan(plan.warmupSec)}, быстрый шаг")
            val run = plan.jog?.let { "${it.name} ${formatSpan(it.runSec)}" }
                ?: program?.let { "${it.name}: ${it.description}" }
                ?: "Бег без интервалов"
            Text("${n++}. $run")
            Text("${n++}. Перерыв, площадка начинается по кнопке")
            Text("${n}. ${strength.description}")
            if (strength.exercises.isEmpty()) {
                Text("Упражнения не выбраны", color = MaterialTheme.colorScheme.error)
            }
            strength.exercises.forEach { exercise ->
                val target = preview.targets[exercise.id] ?: exercise.startTarget
                ExerciseLine(exercise, target, if (strength.circuit) null else strength.sets)
            }
            Text(
                "Сделали все подходы — цель +1. Дошли до верхней границы ступени — следующая тренировка начнётся " +
                    "со следующей, более сложной ступени.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ExerciseLine(exercise: Exercise, target: Int, sets: Int?) {
    val (level, levels) = ExerciseCatalog.level(exercise.variationId) ?: (1 to 1)
    Column(Modifier.fillMaxWidth().padding(start = 12.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(exercise.name, Modifier.weight(1f))
            Text(if (sets == null) exercise.amount(target) else "$sets × ${exercise.amount(target)}", fontWeight = FontWeight.SemiBold)
        }
        val span = (exercise.maxTarget - exercise.startTarget).coerceAtLeast(1)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "ступень $level из $levels",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(
                progress = { ((target - exercise.startTarget).toFloat() / span).coerceIn(0f, 1f) },
                modifier = Modifier.weight(1f),
            )
            Text(
                "до ${exercise.amount(exercise.maxTarget)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun RunProgressCard(progress: RunProgress, onLevelUp: (IntervalProgram) -> Unit) {
    val needed = Progression.LEVEL_UP_SESSIONS
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Прогресс бега", style = MaterialTheme.typography.titleMedium)
            Text("${progress.program.name}: успешных тренировок подряд ${minOf(progress.streak, needed)} из $needed")
            LinearProgressIndicator(
                progress = { (progress.streak.toFloat() / needed).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            val next = progress.next
            if (progress.levelUpReady && next != null) {
                Text("Пора на «${next.name}»: ${next.description}")
                Button(onClick = { onLevelUp(next) }) { Text("Перейти на ${next.name}") }
            } else {
                Text(
                    if (next == null) "Это последний уровень" else "Дальше: «${next.name}» — ${next.description}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
internal fun StageBanner(label: String, color: Color, trailing: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(color)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        trailing?.let { Text(it, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
internal fun BreakPanel(state: WorkoutState, onStart: () -> Unit, onRunResult: (Boolean) -> Unit) {
    StageBanner("ПЕРЕРЫВ", RestColor, Format.duration(state.activeMs - state.stageStartMs))
    if (state.programName != null) {
        Text("Беговая часть получилась?", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceButton("Да, всё пробежал", state.runCompleted == true, Modifier.weight(1f)) { onRunResult(true) }
            ChoiceButton("Нет, не всё", state.runCompleted == false, Modifier.weight(1f)) { onRunResult(false) }
        }
        Text(
            "От этого зависит, когда предложить следующий уровень бега.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Text("Отдохните, попейте воды и дойдите до площадки.", style = MaterialTheme.typography.bodyLarge)
    Button(
        onClick = onStart,
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp),
    ) { Text("Начать тренировку на площадке", fontSize = 20.sp, textAlign = TextAlign.Center) }
}

@Composable
private fun ChoiceButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) { Text(label, textAlign = TextAlign.Center) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text(label, textAlign = TextAlign.Center) }
    }
}

@Composable
internal fun StrengthPanel(
    view: StrengthView,
    levelUps: List<LevelUp>,
    onDone: (Int) -> Unit,
    onStartTimed: () -> Unit,
    onSkipRest: () -> Unit,
    onFinish: () -> Unit,
) {
    val exercise = view.exercise
    if (exercise == null) {
        StageBanner("ПЛОЩАДКА ВЫПОЛНЕНА", ZoneColors.Normal)
        levelUps.forEach {
            Text(
                "Новая ступень: ${it.from.name} → ${it.to.name}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        DoneList(view)
        Button(
            onClick = onFinish,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
        ) { Text("Завершить тренировку", fontSize = 20.sp) }
        return
    }
    val progress = if (view.circuit) {
        "Круг ${view.set} из ${view.sets} · упражнение ${view.exerciseNumber} из ${view.exerciseCount}"
    } else {
        "Упражнение ${view.exerciseNumber} из ${view.exerciseCount} · подход ${view.set} из ${view.sets}"
    }
    if (view.resting) {
        StageBanner("ОТДЫХ", RestColor, Format.duration((view.remainingSec ?: 0) * 1000L))
        Text(progress, style = MaterialTheme.typography.titleMedium)
        Text(
            "Далее: ${exercise.name}, ${exercise.amount(view.target)}",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        OutlinedButton(onClick = onSkipRest, modifier = Modifier.fillMaxWidth()) { Text("Пропустить отдых") }
    } else {
        StageBanner(exercise.name.uppercase(), WorkColor)
        Text(progress, style = MaterialTheme.typography.titleMedium)
        Text(exercise.hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        WorkStep(view, exercise, onDone, onStartTimed)
    }
    DoneList(view)
}

@Composable
private fun WorkStep(view: StrengthView, exercise: Exercise, onDone: (Int) -> Unit, onStartTimed: () -> Unit) {
    if (exercise.timed && view.timedRunning) {
        Text(Format.duration((view.remainingSec ?: 0) * 1000L), fontSize = 72.sp, fontWeight = FontWeight.Bold)
        OutlinedButton(
            onClick = { onDone(0) },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) { Text("Не выдержал, стоп", fontSize = 18.sp) }
        return
    }
    var failed by rememberSaveable(view.stepIndex) { mutableStateOf(false) }
    var value by rememberSaveable(view.stepIndex) { mutableIntStateOf((view.target - exercise.step).coerceAtLeast(0)) }
    Text(exercise.amount(view.target), fontSize = 64.sp, fontWeight = FontWeight.Bold)
    if (!failed) {
        Button(
            onClick = if (exercise.timed) onStartTimed else ({ onDone(view.target) }),
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ZoneColors.Normal, contentColor = Color.White),
        ) { Text(if (exercise.timed) "Старт таймера" else "Получилось", fontSize = 20.sp) }
        OutlinedButton(
            onClick = { failed = true },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) { Text("Не получилось", fontSize = 18.sp) }
    } else {
        Text(if (exercise.timed) "Сколько секунд продержались?" else "Сколько получилось сделать?", style = MaterialTheme.typography.bodyLarge)
        AmountStepper(exercise, value) { value = it }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { failed = false }, modifier = Modifier.weight(1f).height(56.dp)) { Text("Назад") }
            Button(onClick = { onDone(value) }, modifier = Modifier.weight(1f).height(56.dp)) { Text("Сохранить") }
        }
    }
}

@Composable
private fun AmountStepper(exercise: Exercise, value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        FilledTonalIconButton(onClick = { onChange((value - exercise.step).coerceAtLeast(0)) }) {
            Icon(Icons.Filled.Remove, contentDescription = "Меньше")
        }
        Text(if (exercise.timed) "$value с" else "$value", fontSize = 48.sp, fontWeight = FontWeight.Bold)
        FilledTonalIconButton(onClick = { onChange(value + exercise.step) }) {
            Icon(Icons.Filled.Add, contentDescription = "Больше")
        }
    }
}

@Composable
private fun DoneList(view: StrengthView) {
    if (view.done.isEmpty()) return
    val exercises = view.exercises.associateBy { it.id }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Сделано", style = MaterialTheme.typography.titleSmall)
        view.done.groupBy { it.exerciseId }.forEach { (id, sets) ->
            val exercise = exercises[id] ?: return@forEach
            val ok = sets.all { it.reps >= it.target }
            Text(
                "${if (ok) "✓" else "✗"} ${exercise.name}: ${sets.joinToString(" · ") { exercise.amount(it.reps) }}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
            )
        }
    }
}
