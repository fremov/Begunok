package ru.begunok.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.begunok.data.AppSettings
import ru.begunok.data.db.WorkoutEntity
import ru.begunok.location.GpsStatus
import ru.begunok.sensor.SensorState
import ru.begunok.sensor.SensorStatus
import ru.begunok.system.Permissions
import ru.begunok.system.SystemChecks
import ru.begunok.ui.Format
import ru.begunok.ui.common.AnimatedNumber
import ru.begunok.ui.common.BatteryOptimizationCard
import ru.begunok.ui.common.BeatingHeart
import ru.begunok.ui.common.Confetti
import ru.begunok.ui.common.InfoCard
import ru.begunok.ui.common.LocalNavAnimatedScope
import ru.begunok.ui.common.LocalSharedTransitionScope
import ru.begunok.ui.common.PermissionGate
import ru.begunok.ui.common.RingTimer
import ru.begunok.ui.common.SensorBadge
import ru.begunok.ui.common.appear
import ru.begunok.ui.common.pressScale
import ru.begunok.ui.common.sharedBounds
import ru.begunok.ui.map.LiveRouteMap
import ru.begunok.ui.theme.StageColors
import ru.begunok.ui.theme.ZoneColors
import ru.begunok.ui.theme.numberStyle
import ru.begunok.workout.DayType
import ru.begunok.workout.HrZone
import ru.begunok.workout.IntervalKind
import ru.begunok.workout.IntervalPosition
import ru.begunok.workout.IntervalProgram
import ru.begunok.workout.Stage
import ru.begunok.workout.WorkoutPhase
import ru.begunok.workout.WorkoutState
import ru.begunok.workout.hrZone
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    stopRequested: Boolean,
    onStopRequestHandled: () -> Unit,
    onOpenSensor: () -> Unit,
    onOpenWorkout: (Long) -> Unit,
    onEditPlan: (DayType) -> Unit,
) {
    val sensor by viewModel.sensor.collectAsStateWithLifecycle()
    val workout by viewModel.workout.collectAsStateWithLifecycle()
    val settings by viewModel.appSettings.collectAsStateWithLifecycle()
    val recovery by viewModel.recovery.collectAsStateWithLifecycle()
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val runProgress by viewModel.runProgress.collectAsStateWithLifecycle()
    var confirmStop by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(summary) {
        summary?.let {
            viewModel.dismissSummary()
            onOpenWorkout(it.workoutId)
        }
    }

    LaunchedEffect(stopRequested) {
        if (stopRequested) {
            if (workout.isActive) confirmStop = true
            onStopRequestHandled()
        }
    }

    SharedTransitionLayout {
        AnimatedContent(
            targetState = workout.isActive,
            transitionSpec = { (fadeIn(tween(350)) + scaleIn(initialScale = 0.96f)) togetherWith fadeOut(tween(250)) },
            label = "home",
        ) { active ->
            CompositionLocalProvider(
                LocalSharedTransitionScope provides this@SharedTransitionLayout,
                LocalNavAnimatedScope provides this,
            ) {
                if (active) {
                    ActiveWorkout(
                        state = workout,
                        sensor = sensor,
                        settings = settings,
                        onPause = viewModel::pause,
                        onResume = viewModel::resume,
                        onStop = { confirmStop = true },
                        onBreak = viewModel::toBreak,
                        onStrength = viewModel::toStrength,
                        onRunResult = viewModel::setRunResult,
                        onSetDone = viewModel::setDone,
                        onStartTimed = viewModel::startTimed,
                        onSkipRest = viewModel::skipRest,
                        onSkipExercise = viewModel::skipExercise,
                        onSkipWarmup = viewModel::skipWarmup,
                        onFinish = viewModel::stop,
                    )
                } else {
                    val gps by viewModel.gps.collectAsStateWithLifecycle()
                    PreStart(
                        sensor = sensor,
                        gps = gps,
                        settings = settings,
                        preview = preview,
                        runProgress = runProgress,
                        onSelectDayType = viewModel::setDayType,
                        onEditPlan = onEditPlan,
                        onStart = viewModel::start,
                        onOpenSensor = onOpenSensor,
                        onChangeUpperLimit = viewModel::setUpperLimit,
                        onSelectProgram = viewModel::selectProgram,
                        onDismissBatteryHint = viewModel::dismissBatteryHint,
                    )
                }
            }
        }
    }

    if (confirmStop && workout.isActive) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("Завершить тренировку?") },
            text = { Text("Тренировка будет остановлена и сохранена.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    viewModel.stop()
                }) { Text("Завершить") }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("Отмена") } },
        )
    }

    recovery?.let { RecoveryDialog(it, onSave = viewModel::saveRecovered, onDiscard = viewModel::discardRecovered) }
}

private val ru = Locale.forLanguageTag("ru")

@Composable
private fun PreStart(
    sensor: SensorState,
    gps: GpsStatus,
    settings: AppSettings,
    preview: PlanPreview?,
    runProgress: RunProgress?,
    onSelectDayType: (DayType?) -> Unit,
    onEditPlan: (DayType) -> Unit,
    onStart: () -> Unit,
    onOpenSensor: () -> Unit,
    onChangeUpperLimit: (Int) -> Unit,
    onSelectProgram: (String?) -> Unit,
    onDismissBatteryHint: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.appear(0)) {
            val today = remember { SimpleDateFormat("EEEE, d MMMM", ru).format(Date()).replaceFirstChar { it.titlecase(ru) } }
            Text(today, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                when (settings.dayType) {
                    DayType.Run -> "Беговой день"
                    DayType.Strength -> "Силовой день"
                    null -> "Готов к пробежке?"
                },
                style = MaterialTheme.typography.displaySmall,
            )
        }
        Row(Modifier.appear(1), verticalAlignment = Alignment.CenterVertically) {
            SensorBadge(sensor, Modifier.weight(1f, fill = false))
            if (sensor.status == SensorStatus.NotSelected) {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onOpenSensor) { Text("Выбрать датчик") }
            }
        }
        HeartHero(sensor.heartRate, settings, paused = false, compact = false, modifier = Modifier.appear(2))
        LimitStepper(settings.upperLimit, onChangeUpperLimit, Modifier.appear(3))
        Box(Modifier.appear(4)) { DayTypePicker(settings.dayType, onSelectDayType) }
        if (settings.dayType != DayType.Strength) {
            Box(Modifier.appear(5)) { ProgramPicker(settings, onSelectProgram) }
        }
        runProgress?.let { Box(Modifier.appear(6)) { RunProgressCard(it) { next -> onSelectProgram(next.id) } } }
        preview?.let { Box(Modifier.appear(7)) { PlanCard(it, settings.selectedProgram) { onEditPlan(it.plan.type) } } }

        PermissionGate(
            permissions = Permissions.location,
            title = "Доступ к геолокации",
            rationale = "Нужен, чтобы считать дистанцию, скорость и темп и записывать маршрут. " +
                "Геолокация используется только во время тренировки.",
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GpsLine(gps)
                if (gps is GpsStatus.Disabled) LocationDisabledCard()
                if (gps !is GpsStatus.Ready) {
                    Text(
                        "Можно стартовать и без GPS — тогда дистанция не будет считаться.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val subtitle = when (settings.dayType) {
                    DayType.Strength -> "трусца, затем площадка"
                    else -> settings.selectedProgram?.let { "${it.name} · ${it.description}" } ?: "свободная пробежка"
                }
                StartButton("Старт", subtitle, onStart)
            }
        }

        if (Permissions.notifications.isNotEmpty()) {
            PermissionGate(
                permissions = Permissions.notifications,
                title = "Уведомления",
                rationale = "Во время тренировки в шторке показывается время, пульс и дистанция, " +
                    "а также кнопки «Пауза» и «Стоп». Без разрешения тренировка тоже запишется, но уведомления не будет видно.",
            ) {}
        }

        if (!settings.batteryHintDismissed) BatteryOptimizationCard(onDismiss = onDismissBatteryHint)
    }
}

@Composable
internal fun StartButton(label: String, subtitle: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val primary = MaterialTheme.colorScheme.primary
    Button(
        onClick = onClick,
        interactionSource = interaction,
        modifier = modifier
            .fillMaxWidth()
            .height(76.dp)
            .pressScale(interaction),
        shape = RoundedCornerShape(38.dp),
        contentPadding = ButtonDefaults.ContentPadding,
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(38.dp))
                .background(Brush.horizontalGradient(listOf(primary, Color(0xFFFF9A3D)))),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(36.dp))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(label, style = numberStyle(28), color = Color.White)
                    subtitle?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.85f), maxLines = 1) }
                }
            }
        }
    }
}

@Composable
private fun LocationDisabledCard() {
    val context = LocalContext.current
    InfoCard(
        title = "Геолокация выключена",
        text = "Включите геолокацию, чтобы считать дистанцию и записывать маршрут.",
    ) {
        Button(onClick = { SystemChecks.openLocationSettings(context) }) { Text("Включить") }
    }
}

@Composable
private fun GpsLine(gps: GpsStatus) {
    val color = when (gps) {
        is GpsStatus.Ready -> ZoneColors.Normal
        is GpsStatus.Searching, is GpsStatus.Weak -> Color(0xFFFFA000)
        else -> MaterialTheme.colorScheme.error
    }
    Row(
        Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(8.dp))
        Text("GPS: ${gps.label}", color = color, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
internal fun HeartHero(heartRate: Int?, settings: AppSettings, paused: Boolean, compact: Boolean, modifier: Modifier = Modifier) {
    val zone = heartRate?.let { hrZone(it, settings.upperLimit, settings.lowerLimit) }
    val target = when (zone) {
        HrZone.Below -> ZoneColors.Below
        HrZone.Normal -> ZoneColors.Normal
        HrZone.Above -> ZoneColors.Above
        null -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val color by animateColorAsState(target, tween(600), label = "zone")
    val surface = MaterialTheme.colorScheme.surfaceContainer
    val content = if (zone == null) MaterialTheme.colorScheme.onSurfaceVariant else Color.White
    Box(
        modifier = modifier
            .sharedBounds("hr-card")
            .fillMaxWidth()
            .clip(RoundedCornerShape(32.dp))
            .background(Brush.verticalGradient(listOf(color, if (zone == null) surface else color.copy(alpha = 0.55f))))
            .padding(horizontal = 20.dp, vertical = if (compact) 10.dp else 18.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BeatingHeart(heartRate, content, if (compact) 52.dp else 84.dp)
                Spacer(Modifier.width(if (compact) 10.dp else 14.dp))
                AnimatedNumber(Format.heartRate(heartRate), numberStyle(if (compact) 72 else 116), color = content)
            }
            val label = when {
                paused -> "ПАУЗА"
                zone == HrZone.Above -> "выше границы — сбавь темп"
                zone == HrZone.Below -> "ниже зоны"
                zone == HrZone.Normal -> "в зоне"
                else -> "ожидаю пульс"
            }
            Text(label.uppercase(), color = content, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            val limits = buildString {
                append("уд/мин · граница ")
                settings.lowerLimit?.let { append("$it–") }
                append(settings.upperLimit)
            }
            Text(limits, color = content.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ActiveWorkout(
    state: WorkoutState,
    sensor: SensorState,
    settings: AppSettings,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onBreak: () -> Unit,
    onStrength: () -> Unit,
    onRunResult: (Boolean) -> Unit,
    onSetDone: (Int) -> Unit,
    onStartTimed: () -> Unit,
    onSkipRest: () -> Unit,
    onSkipExercise: () -> Unit,
    onSkipWarmup: () -> Unit,
    onFinish: () -> Unit,
) {
    val paused = state.phase == WorkoutPhase.Paused
    var confirmBreak by rememberSaveable { mutableStateOf(false) }
    if (state.stage != Stage.Run) KeepScreenOn()
    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AnimatedContent(
                targetState = state.stage,
                transitionSpec = {
                    (slideInVertically { it / 3 } + fadeIn(tween(400))) togetherWith
                        (slideOutVertically { -it / 3 } + fadeOut(tween(250)))
                },
                label = "stage",
            ) { stage ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when (stage) {
                        Stage.Run -> {
                            StageHero(state, settings)
                            if (state.interval?.kind == IntervalKind.Warmup) {
                                OutlinedButton(onClick = onSkipWarmup, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                                    Icon(Icons.Filled.SkipNext, contentDescription = null)
                                    Text("  Пропустить разминку")
                                }
                            }
                            HeartHero(sensor.heartRate, settings, paused, compact = true)
                            MetricGrid(
                                listOf(
                                    "Время" to Format.duration(state.activeMs),
                                    "Дистанция, км" to Format.distanceKm(state.distanceM),
                                    "Темп, мин/км" to Format.pace(state.currentPaceSecPerKm),
                                    "Скорость, км/ч" to Format.speedKmh(state.speedMps),
                                    "Ср. темп" to Format.pace(state.avgPaceSecPerKm),
                                    "Ср. пульс" to Format.heartRate(state.avgHr),
                                ),
                            )
                            LiveRouteMap(
                                route = state.route,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp)
                                    .clip(RoundedCornerShape(24.dp)),
                            )
                            if (state.day != null) {
                                OutlinedButton(onClick = { confirmBreak = true }, modifier = Modifier.fillMaxWidth()) {
                                    Text("Закончить бег и перейти к площадке")
                                }
                            }
                        }
                        Stage.Break -> {
                            BreakPanel(state, onStrength, onRunResult)
                            HeartHero(sensor.heartRate, settings, paused, compact = true)
                            MetricGrid(
                                listOf(
                                    "Время" to Format.duration(state.activeMs),
                                    "Дистанция, км" to Format.distanceKm(state.distanceM),
                                ),
                            )
                        }
                        Stage.Strength -> {
                            state.strength?.let { StrengthPanel(it, state.levelUps, onSetDone, onStartTimed, onSkipRest, onSkipExercise, onFinish) }
                            HeartHero(sensor.heartRate, settings, paused, compact = true)
                            MetricGrid(
                                listOf(
                                    "Время" to Format.duration(state.activeMs),
                                    "Ср. пульс" to Format.heartRate(state.avgHr),
                                    "Макс. пульс" to Format.heartRate(state.maxHr),
                                ),
                            )
                        }
                    }
                }
            }
            SensorBadge(sensor)
            if (state.stage == Stage.Run) GpsLine(state.gps)
            Controls(paused, onPause, onResume, onStop)
        }
        if (state.stage == Stage.Strength && state.strength?.finished == true) {
            Confetti(trigger = state.workoutId, modifier = Modifier.fillMaxSize())
        }
    }

    if (confirmBreak) {
        AlertDialog(
            onDismissRequest = { confirmBreak = false },
            title = { Text("Закончить бег?") },
            text = { Text("Начнётся перерыв, а потом тренировка на площадке. Вернуться к бегу будет нельзя.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmBreak = false
                    onBreak()
                }) { Text("К площадке") }
            },
            dismissButton = { TextButton(onClick = { confirmBreak = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun Controls(paused: Boolean, onPause: () -> Unit, onResume: () -> Unit, onStop: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val pauseInteraction = remember { MutableInteractionSource() }
        Button(
            onClick = if (paused) onResume else onPause,
            interactionSource = pauseInteraction,
            modifier = Modifier
                .weight(1f)
                .height(68.dp)
                .pressScale(pauseInteraction),
            shape = RoundedCornerShape(34.dp),
        ) {
            Icon(if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (paused) "Продолжить" else "Пауза", fontSize = 20.sp)
        }
        val stopInteraction = remember { MutableInteractionSource() }
        OutlinedButton(
            onClick = onStop,
            interactionSource = stopInteraction,
            modifier = Modifier
                .weight(1f)
                .height(68.dp)
                .pressScale(stopInteraction),
            shape = RoundedCornerShape(34.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            Icon(Icons.Filled.Stop, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Стоп", fontSize = 20.sp)
        }
    }
}

@Composable
internal fun MetricGrid(items: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.chunked(if (items.size == 3) 3 else 2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { (label, value) ->
                    Card(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    ) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            AnimatedNumber(value, numberStyle(34))
                            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LimitStepper(value: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Верхняя граница", style = MaterialTheme.typography.titleMedium)
                Text("сигнал, если пульс выше", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilledTonalIconButton(onClick = { onChange(value - 1) }) { Icon(Icons.Filled.Remove, contentDescription = "Меньше") }
            AnimatedNumber("$value", numberStyle(32))
            FilledTonalIconButton(onClick = { onChange(value + 1) }) { Icon(Icons.Filled.Add, contentDescription = "Больше") }
        }
    }
}

@Composable
private fun ProgramPicker(settings: AppSettings, onSelect: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = settings.selectedProgram
    Box {
        Card(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Программа бега", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(selected?.name ?: "Свободная тренировка", style = MaterialTheme.typography.titleLarge)
                    Text(selected?.description ?: "без интервалов", style = MaterialTheme.typography.bodyMedium)
                }
                Icon(Icons.Filled.ExpandMore, contentDescription = null)
            }
            selected?.let { ProgramBar(it, null, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Свободная тренировка") },
                onClick = {
                    expanded = false
                    onSelect(null)
                },
            )
            settings.programs.forEach { program ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(program.name)
                            Text(program.description, style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(program.id)
                    },
                )
            }
        }
    }
}

@Composable
internal fun ProgramBar(program: IntervalProgram, position: IntervalPosition?, modifier: Modifier = Modifier) {
    val segments = buildList {
        if (program.warmupSec > 0) add(IntervalKind.Warmup to program.warmupSec)
        repeat(program.repeats) {
            add(IntervalKind.Run to program.runSec)
            if (program.walkSec > 0) add(IntervalKind.Walk to program.walkSec)
        }
    }
    val current = position?.let { p ->
        when {
            p.finished -> segments.size
            p.kind == IntervalKind.Warmup -> 0
            else -> (if (program.warmupSec > 0) 1 else 0) + if (program.walkSec > 0) p.segmentIndex else p.repeat - 1
        }
    }
    val total = segments.sumOf { it.second }.coerceAtLeast(1)
    Row(modifier.fillMaxWidth().height(10.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        segments.forEachIndexed { i, (kind, sec) ->
            val base = when (kind) {
                IntervalKind.Warmup -> StageColors.Warmup
                IntervalKind.Run -> StageColors.Run
                IntervalKind.Walk -> StageColors.Walk
            }
            val alpha = when {
                current == null -> 0.85f
                i < current -> 0.35f
                i == current -> 1f
                else -> 0.7f
            }
            Box(
                Modifier
                    .weight(sec.toFloat() / total)
                    .fillMaxSize()
                    .clip(RoundedCornerShape(5.dp))
                    .background(base.copy(alpha = alpha))
            ) {
                if (i == current && position != null) {
                    val fraction = 1f - position.remainingSec.toFloat() / sec
                    Box(
                        Modifier
                            .fillMaxSize(fraction.coerceIn(0f, 1f))
                            .background(Color.White.copy(alpha = 0.55f))
                    )
                }
            }
        }
    }
}

@Composable
private fun StageHero(state: WorkoutState, settings: AppSettings) {
    val position = state.interval
    val program = settings.dayType?.let { settings.dayPlan(it).runProgram(settings.selectedProgram) } ?: settings.selectedProgram
    val (label, target) = when (position?.kind) {
        IntervalKind.Warmup -> "РАЗМИНКА" to StageColors.Warmup
        IntervalKind.Run -> "БЕГ" to StageColors.Run
        IntervalKind.Walk -> "ШАГ" to StageColors.Walk
        null -> if (position == null) "БЕГ" to StageColors.Run else "ПРОГРАММА ВЫПОЛНЕНА" to StageColors.Done
    }
    val color by animateColorAsState(target, tween(700), label = "stage")
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(32.dp))
            .background(Brush.linearGradient(listOf(color, color.copy(alpha = 0.6f))))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                AnimatedContent(
                    targetState = label,
                    transitionSpec = { (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut()) },
                    label = "stageLabel",
                ) { Text(it, style = numberStyle(36), color = Color.White) }
                if (position != null && !position.finished && position.kind != IntervalKind.Warmup) {
                    Text("интервал ${position.repeat} из ${position.repeats}", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.titleMedium)
                }
                if (position == null) {
                    AnimatedNumber(Format.duration(state.activeMs), numberStyle(44), color = Color.White)
                }
            }
            if (position != null && !position.finished && program != null) {
                val segment = when (position.kind) {
                    IntervalKind.Warmup -> program.warmupSec
                    IntervalKind.Run -> program.runSec
                    IntervalKind.Walk -> program.walkSec
                    null -> 1
                }.coerceAtLeast(1)
                RingTimer(
                    progress = position.remainingSec.toFloat() / segment,
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.25f),
                    modifier = Modifier.size(118.dp),
                    stroke = 10.dp,
                ) {
                    Text(Format.duration(position.remainingSec * 1000L), style = numberStyle(30), color = Color.White)
                }
            }
        }
        if (program != null && position != null) ProgramBar(program, position)
    }
}

@Composable
private fun RecoveryDialog(workout: WorkoutEntity, onSave: () -> Unit, onDiscard: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Незавершённая тренировка") },
        text = {
            Text("Тренировка от ${Format.dateTime(workout.startTime)} была прервана. Сохранить то, что успело записаться?")
        },
        confirmButton = { TextButton(onClick = onSave) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDiscard) { Text("Удалить") } },
    )
}
