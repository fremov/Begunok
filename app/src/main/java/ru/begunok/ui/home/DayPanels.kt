package ru.begunok.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.begunok.ui.Format
import ru.begunok.ui.common.AnimatedNumber
import ru.begunok.ui.common.RingTimer
import ru.begunok.ui.common.pressScale
import ru.begunok.ui.theme.StageColors
import ru.begunok.ui.theme.ZoneColors
import ru.begunok.ui.theme.numberStyle
import ru.begunok.workout.DayType
import ru.begunok.workout.Exercise
import ru.begunok.workout.ExerciseCatalog
import ru.begunok.workout.IntervalProgram
import ru.begunok.workout.LevelUp
import ru.begunok.workout.Progression
import ru.begunok.workout.StrengthView
import ru.begunok.workout.WorkoutState
import ru.begunok.workout.formatSpan

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
private fun PanelCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
internal fun PlanCard(preview: PlanPreview, program: IntervalProgram?, onEdit: () -> Unit) {
    val plan = preview.plan
    val strength = plan.strength
    PanelCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("План", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  Упражнения")
            }
        }
        val steps = buildList {
            if (plan.warmupSec > 0) add(StageColors.Warmup to "Разминка ${formatSpan(plan.warmupSec)}, быстрый шаг")
            add(
                StageColors.Run to (
                    plan.jog?.let { "${it.name} ${formatSpan(it.runSec)}" }
                        ?: program?.let { "${it.name}: ${it.description}" }
                        ?: "Бег без интервалов"
                    ),
            )
            add(StageColors.Rest to "Перерыв, площадка начинается по кнопке")
            add(StageColors.Done to strength.description)
        }
        steps.forEachIndexed { i, (color, text) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(color.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center,
                ) { Text("${i + 1}", color = color, style = MaterialTheme.typography.labelLarge) }
                Spacer(Modifier.width(10.dp))
                Text(text, style = MaterialTheme.typography.bodyLarge)
            }
        }
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

@Composable
private fun ExerciseLine(exercise: Exercise, target: Int, sets: Int?) {
    val (level, levels) = ExerciseCatalog.level(exercise.variationId) ?: (1 to 1)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(exercise.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text(
                if (sets == null) exercise.amount(target) else "$sets × ${exercise.amount(target)}",
                style = numberStyle(22),
                color = MaterialTheme.colorScheme.primary,
            )
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
    PanelCard {
        Text("Прогресс бега", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            repeat(needed) { i ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(
                            if (i < progress.streak) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.surfaceContainerHighest,
                        )
                )
            }
        }
        Text("${progress.program.name}: успешных тренировок подряд ${minOf(progress.streak, needed)} из $needed")
        val next = progress.next
        if (progress.levelUpReady && next != null) {
            Text("Пора на «${next.name}»: ${next.description}", style = MaterialTheme.typography.titleMedium)
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

@Composable
internal fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
private fun Hero(color: Color, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(32.dp))
            .background(Brush.linearGradient(listOf(color, color.copy(alpha = 0.6f))))
            .padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun HeroTitle(text: String, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth()) {
        Text(text, style = numberStyle(34), color = Color.White)
        subtitle?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.9f)) }
    }
}

@Composable
internal fun BreakPanel(state: WorkoutState, onStart: () -> Unit, onRunResult: (Boolean) -> Unit) {
    Hero(StageColors.Rest) {
        HeroTitle("ПЕРЕРЫВ", "отдохните и дойдите до площадки")
        AnimatedNumber(Format.duration(state.activeMs - state.stageStartMs), numberStyle(72), color = Color.White)
    }
    if (state.programName != null) {
        PanelCard {
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
    }
    StartButton("Начать площадку", "подходы с таймером отдыха", onStart)
}

@Composable
private fun ChoiceButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) {
            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(" $label", textAlign = TextAlign.Center)
        }
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
    onSkipExercise: () -> Unit,
    onFinish: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    var flash by remember { mutableIntStateOf(0) }
    val done: (Int, Boolean) -> Unit = { reps, success ->
        if (success) {
            flash++
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        }
        onDone(reps)
    }
    Box(contentAlignment = Alignment.Center) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AnimatedContent(
                targetState = view,
                contentKey = { it.stepIndex to it.finished },
                transitionSpec = { (fadeIn(tween(350)) + scaleIn(initialScale = 0.92f)) togetherWith fadeOut(tween(200)) },
                label = "step",
            ) { step -> StepContent(step, levelUps, done, onStartTimed, onSkipRest, onSkipExercise, onFinish) }
            DoneList(view)
        }
        SuccessFlash(flash)
    }
}

@Composable
private fun StepContent(
    view: StrengthView,
    levelUps: List<LevelUp>,
    onDone: (Int, Boolean) -> Unit,
    onStartTimed: () -> Unit,
    onSkipRest: () -> Unit,
    onSkipExercise: () -> Unit,
    onFinish: () -> Unit,
) {
    val exercise = view.exercise
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (exercise == null) {
            Hero(StageColors.Done) {
                Icon(Icons.Filled.EmojiEvents, contentDescription = null, tint = Color.White, modifier = Modifier.size(56.dp))
                HeroTitle("ПЛОЩАДКА ВЫПОЛНЕНА", "отличная работа!")
            }
            levelUps.forEach {
                PanelCard {
                    Text("Новая ступень", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
                    Text("${it.from.name} → ${it.to.name}", style = MaterialTheme.typography.titleLarge)
                }
            }
            StartButton("Завершить", "сохранить тренировку", onFinish)
            return@Column
        }
        val progress = if (view.circuit) {
            "круг ${view.set} из ${view.sets} · упражнение ${view.exerciseNumber} из ${view.exerciseCount}"
        } else {
            "упражнение ${view.exerciseNumber} из ${view.exerciseCount} · подход ${view.set} из ${view.sets}"
        }
        if (view.resting) {
            Hero(StageColors.Rest) {
                HeroTitle("ОТДЫХ", progress)
                val total = view.remainingSec?.let { maxOf(it, 1) } ?: 1
                val full = remember(view.stepIndex) { total }
                RingTimer(
                    progress = (view.remainingSec ?: 0).toFloat() / full,
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.25f),
                    modifier = Modifier.size(200.dp),
                ) { Text(Format.duration((view.remainingSec ?: 0) * 1000L), style = numberStyle(56), color = Color.White) }
                Text(
                    "Далее: ${exercise.name}, ${exercise.amount(view.target)}",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
            }
            OutlinedButton(onClick = onSkipRest, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Icon(Icons.Filled.SkipNext, contentDescription = null)
                Text("  Пропустить отдых")
            }
        } else {
            Hero(StageColors.Work) {
                HeroTitle(exercise.name.uppercase(), progress)
                Text(exercise.hint, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f))
                if (exercise.timed && view.timedRunning) {
                    RingTimer(
                        progress = (view.remainingSec ?: 0).toFloat() / view.target.coerceAtLeast(1),
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.25f),
                        modifier = Modifier.size(200.dp),
                    ) { Text(Format.duration((view.remainingSec ?: 0) * 1000L), style = numberStyle(56), color = Color.White) }
                } else {
                    Text(exercise.amount(view.target), style = numberStyle(88), color = Color.White)
                }
            }
            WorkButtons(view, exercise, onDone, onStartTimed)
            SkipExerciseButton(exercise, onSkipExercise)
        }
    }
}

@Composable
private fun WorkButtons(view: StrengthView, exercise: Exercise, onDone: (Int, Boolean) -> Unit, onStartTimed: () -> Unit) {
    if (exercise.timed && view.timedRunning) {
        OutlinedButton(
            onClick = { onDone(0, false) },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) { Text("Не выдержал, стоп", fontSize = 18.sp) }
        return
    }
    var failed by rememberSaveable(view.stepIndex) { mutableStateOf(false) }
    var value by rememberSaveable(view.stepIndex) { mutableIntStateOf((view.target - exercise.step).coerceAtLeast(0)) }
    if (!failed) {
        val interaction = remember { MutableInteractionSource() }
        Button(
            onClick = { if (exercise.timed) onStartTimed() else onDone(view.target, true) },
            interactionSource = interaction,
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .pressScale(interaction),
            shape = RoundedCornerShape(36.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ZoneColors.Normal, contentColor = Color.White),
        ) {
            Icon(if (exercise.timed) Icons.Filled.PlayArrow else Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (exercise.timed) "Старт таймера" else "Получилось", fontSize = 22.sp)
        }
        OutlinedButton(
            onClick = { failed = true },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) { Text("Не получилось", fontSize = 18.sp) }
    } else {
        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    if (exercise.timed) "Сколько секунд продержались?" else "Сколько получилось сделать?",
                    style = MaterialTheme.typography.titleMedium,
                )
                AmountStepper(exercise, value) { value = it }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { failed = false }, modifier = Modifier.weight(1f).height(56.dp)) { Text("Назад") }
                    Button(onClick = { onDone(value, false) }, modifier = Modifier.weight(1f).height(56.dp)) { Text("Сохранить") }
                }
            }
        }
    }
}

@Composable
private fun SkipExerciseButton(exercise: Exercise, onSkip: () -> Unit) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Filled.SkipNext, contentDescription = null, modifier = Modifier.size(18.dp))
        Text("  Пропустить упражнение")
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Пропустить «${exercise.name}»?") },
            text = { Text("Оставшиеся подходы этого упражнения не будут выполнены. Цель на следующую тренировку не изменится.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    onSkip()
                }) { Text("Пропустить") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun SuccessFlash(key: Int) {
    if (key == 0) return
    val progress = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { progress.animateTo(1f, tween(900)) }
    val p = progress.value
    if (p >= 1f) return
    val scale = if (p < 0.35f) 0.4f + p / 0.35f * 0.8f else 1.2f - (p - 0.35f) * 0.3f
    Box(
        Modifier
            .size(140.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (p < 0.6f) 1f else (1f - p) / 0.4f
            }
            .clip(CircleShape)
            .background(ZoneColors.Normal),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(88.dp)) }
}

@Composable
private fun AmountStepper(exercise: Exercise, value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        FilledTonalIconButton(onClick = { onChange((value - exercise.step).coerceAtLeast(0)) }, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Filled.Remove, contentDescription = "Меньше")
        }
        AnimatedNumber(if (exercise.timed) "$value с" else "$value", numberStyle(56))
        FilledTonalIconButton(onClick = { onChange(value + exercise.step) }, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Filled.Add, contentDescription = "Больше")
        }
    }
}

@Composable
private fun DoneList(view: StrengthView) {
    if (view.done.isEmpty() && view.skipped.isEmpty()) return
    val sets = view.done.groupBy { it.exerciseId }
    PanelCard {
        Text("Сделано", style = MaterialTheme.typography.titleMedium)
        view.exercises.filter { it.id in sets || it.id in view.skipped }.forEach { exercise ->
            val done = sets[exercise.id].orEmpty()
            val skipped = exercise.id in view.skipped
            val ok = !skipped && done.all { it.reps >= it.target }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                ok -> ZoneColors.Normal
                                skipped -> MaterialTheme.colorScheme.outline
                                else -> MaterialTheme.colorScheme.error
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (ok) "✓" else if (skipped) "–" else "✗", color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(10.dp))
                Text(exercise.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Text(done.joinToString(" · ") { exercise.amount(it.reps) }, style = numberStyle(20))
                if (skipped) {
                    Text(
                        if (done.isEmpty()) "пропущено" else " · пропущено",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
