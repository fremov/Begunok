package ru.begunok.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import ru.begunok.ui.common.BatteryOptimizationCard
import ru.begunok.ui.common.InfoCard
import ru.begunok.ui.common.PermissionGate
import ru.begunok.ui.common.SensorBadge
import ru.begunok.ui.map.LiveRouteMap
import ru.begunok.ui.theme.ZoneColors
import ru.begunok.workout.DayType
import ru.begunok.workout.HrZone
import ru.begunok.workout.Stage
import ru.begunok.workout.WorkoutPhase
import ru.begunok.workout.WorkoutState
import ru.begunok.workout.IntervalKind
import ru.begunok.workout.hrZone

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

    if (workout.isActive) {
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
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SensorBadge(sensor)
        if (sensor.status == SensorStatus.NotSelected) {
            Button(onClick = onOpenSensor) { Text("Выбрать датчик") }
        }
        HeartRateBox(sensor.heartRate, settings, paused = false)
        LimitStepper(settings.upperLimit, onChangeUpperLimit)
        DayTypePicker(settings.dayType, onSelectDayType)
        if (settings.dayType != DayType.Strength) ProgramPicker(settings, onSelectProgram)
        runProgress?.let { RunProgressCard(it) { next -> onSelectProgram(next.id) } }
        preview?.let { PlanCard(it, settings.selectedProgram) { onEditPlan(it.plan.type) } }

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
                    )
                }
                Button(
                    onClick = onStart,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                ) { Text("Старт", fontSize = 22.sp) }
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
    Text("GPS: ${gps.label}", color = color, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun HeartRateBox(heartRate: Int?, settings: AppSettings, paused: Boolean, compact: Boolean = false) {
    val zone = heartRate?.let { hrZone(it, settings.upperLimit, settings.lowerLimit) }
    val background = when (zone) {
        HrZone.Below -> ZoneColors.Below
        HrZone.Normal -> ZoneColors.Normal
        HrZone.Above -> ZoneColors.Above
        null -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = if (zone == null) MaterialTheme.colorScheme.onSurfaceVariant else Color.White
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(background)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (paused) Text("ПАУЗА", color = content, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(
                text = Format.heartRate(heartRate),
                color = content,
                fontSize = if (compact) 64.sp else 112.sp,
                lineHeight = if (compact) 68.sp else 116.sp,
                fontWeight = FontWeight.Bold,
            )
            val limits = buildString {
                append("уд/мин · граница ")
                settings.lowerLimit?.let { append("$it–") }
                append(settings.upperLimit)
            }
            Text(limits, color = content, style = MaterialTheme.typography.titleMedium)
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
    onFinish: () -> Unit,
) {
    val paused = state.phase == WorkoutPhase.Paused
    var confirmBreak by rememberSaveable { mutableStateOf(false) }
    if (state.stage != Stage.Run) KeepScreenOn()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (state.stage) {
            Stage.Run -> {
                IntervalBanner(state)
                HeartRateBox(sensor.heartRate, settings, paused)
                Row(Modifier.fillMaxWidth()) {
                    Metric("Время", Format.duration(state.activeMs), Modifier.weight(1f), large = true)
                    Metric("Дистанция, км", Format.distanceKm(state.distanceM), Modifier.weight(1f), large = true)
                }
                Row(Modifier.fillMaxWidth()) {
                    Metric("Скорость, км/ч", Format.speedKmh(state.speedMps), Modifier.weight(1f))
                    Metric("Темп, мин/км", Format.pace(state.currentPaceSecPerKm), Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth()) {
                    Metric("Ср. темп", Format.pace(state.avgPaceSecPerKm), Modifier.weight(1f))
                    Metric("Ср. пульс", Format.heartRate(state.avgHr), Modifier.weight(1f))
                    Metric("Макс. пульс", Format.heartRate(state.maxHr), Modifier.weight(1f))
                }
                LiveRouteMap(
                    route = state.route,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(16.dp)),
                )
                if (state.day != null) {
                    OutlinedButton(onClick = { confirmBreak = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Закончить бег и перейти к площадке")
                    }
                }
            }
            Stage.Break -> {
                BreakPanel(state, onStrength, onRunResult)
                HeartRateBox(sensor.heartRate, settings, paused, compact = true)
                Row(Modifier.fillMaxWidth()) {
                    Metric("Время", Format.duration(state.activeMs), Modifier.weight(1f))
                    Metric("Дистанция, км", Format.distanceKm(state.distanceM), Modifier.weight(1f))
                }
            }
            Stage.Strength -> {
                state.strength?.let { StrengthPanel(it, state.levelUps, onSetDone, onStartTimed, onSkipRest, onFinish) }
                HeartRateBox(sensor.heartRate, settings, paused, compact = true)
                Row(Modifier.fillMaxWidth()) {
                    Metric("Время", Format.duration(state.activeMs), Modifier.weight(1f))
                    Metric("Ср. пульс", Format.heartRate(state.avgHr), Modifier.weight(1f))
                    Metric("Макс. пульс", Format.heartRate(state.maxHr), Modifier.weight(1f))
                }
            }
        }
        SensorBadge(sensor)
        if (state.stage == Stage.Run) GpsLine(state.gps)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = if (paused) onResume else onPause,
                modifier = Modifier
                    .weight(1f)
                    .height(64.dp),
            ) { Text(if (paused) "Продолжить" else "Пауза", fontSize = 20.sp) }
            OutlinedButton(
                onClick = onStop,
                modifier = Modifier
                    .weight(1f)
                    .height(64.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Стоп", fontSize = 20.sp) }
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
private fun Metric(label: String, value: String, modifier: Modifier = Modifier, large: Boolean = false) {
    Column(modifier.padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = if (large) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LimitStepper(value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Верхняя граница", style = MaterialTheme.typography.titleMedium)
        FilledTonalIconButton(onClick = { onChange(value - 1) }) { Icon(Icons.Filled.Remove, contentDescription = "Меньше") }
        Text("$value", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        FilledTonalIconButton(onClick = { onChange(value + 1) }) { Icon(Icons.Filled.Add, contentDescription = "Больше") }
    }
}

@Composable
private fun ProgramPicker(settings: AppSettings, onSelect: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = settings.selectedProgram
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(selected?.name ?: "Свободная тренировка")
                Text(selected?.description ?: "без интервалов", style = MaterialTheme.typography.bodySmall)
            }
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
private fun IntervalBanner(state: WorkoutState) {
    val position = state.interval ?: return
    val (label, color) = when (position.kind) {
        IntervalKind.Warmup -> "РАЗМИНКА" to Color(0xFF1E88E5)
        IntervalKind.Run -> "БЕГ" to Color(0xFFFC4C02)
        IntervalKind.Walk -> "ШАГ" to Color(0xFF1E88E5)
        null -> "ПРОГРАММА ЗАВЕРШЕНА" to ZoneColors.Normal
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(color)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (!position.finished) {
            Text(
                "${Format.duration(position.remainingSec * 1000L)}  ${position.repeat}/${position.repeats}",
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
            )
        }
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
