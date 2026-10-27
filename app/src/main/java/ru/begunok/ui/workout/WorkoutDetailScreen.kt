package ru.begunok.ui.workout

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import ru.begunok.ui.common.Confetti
import ru.begunok.ui.common.appear
import ru.begunok.ui.common.rememberCountUp
import ru.begunok.ui.common.sharedBounds
import ru.begunok.ui.theme.ZoneColors
import ru.begunok.ui.theme.numberStyle
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.begunok.data.WorkoutRepository
import ru.begunok.data.db.ExerciseSetEntity
import ru.begunok.data.db.WorkoutEntity
import ru.begunok.ui.Format
import ru.begunok.workout.GpxWriter
import ru.begunok.workout.RouteSegment
import ru.begunok.workout.buildRouteSegments
import ru.begunok.workout.gpsCoverage
import ru.begunok.workout.paceSecPerKm
import ru.begunok.workout.timeAboveFraction
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

data class WorkoutDetail(
    val workout: WorkoutEntity,
    val segments: List<RouteSegment>,
    val heartRate: List<HrPoint>,
    val timeAbove: Double?,
    val gpsCoverage: Double?,
    val sets: List<ExerciseSetEntity>,
)

class WorkoutDetailViewModel(
    private val workoutId: Long,
    private val repository: WorkoutRepository,
    private val appContext: Context,
) : ViewModel() {
    val detail: StateFlow<WorkoutDetail?> = repository.observeWorkout(workoutId)
        .filterNotNull()
        .combine(repository.observeSets(workoutId)) { workout, sets -> workout to sets }
        .map { (workout, sets) ->
            val samples = repository.samples(workout.id)
            WorkoutDetail(
                workout = workout,
                segments = buildRouteSegments(samples, workout.upperLimit),
                heartRate = heartRateSeries(samples),
                timeAbove = timeAboveFraction(samples, workout.upperLimit),
                gpsCoverage = gpsCoverage(samples),
                sets = sets,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    suspend fun exportGpx(): Uri? = withContext(Dispatchers.IO) {
        val workout = detail.value?.workout ?: return@withContext null
        val samples = repository.samples(workoutId)
        val title = Format.workoutTitle(workout) ?: "Бег"
        val gpx = GpxWriter.write(workout, samples, "$title ${Format.dateTime(workout.startTime)}")
        val dir = File(appContext.cacheDir, "gpx").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date(workout.startTime))
        val file = File(dir, "begunok_$stamp.gpx")
        file.writeText(gpx)
        FileProvider.getUriForFile(appContext, "${appContext.packageName}.files", file)
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            repository.delete(workoutId)
            onDeleted()
        }
    }
}

@Composable
fun WorkoutDetailScreen(
    workoutId: Long,
    celebrate: Boolean,
    viewModel: WorkoutDetailViewModel,
    onBack: () -> Unit,
    onHome: () -> Unit,
) {
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }

    fun share() {
        scope.launch {
            val uri = viewModel.exportGpx() ?: return@launch
            val send = Intent(Intent.ACTION_SEND)
                .setType("application/gpx+xml")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(send, "Экспорт GPX"))
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .sharedBounds("workout-$workoutId")
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") }
                Column(Modifier.weight(1f)) {
                    Text(detail?.workout?.let(Format::workoutTitle) ?: "Тренировка", style = MaterialTheme.typography.titleLarge)
                    detail?.let {
                        Text(
                            Format.dateTime(it.workout.startTime),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (detail != null) {
                    IconButton(onClick = ::share) { Icon(Icons.Filled.Share, contentDescription = "Экспорт GPX") }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, contentDescription = "Удалить") }
                }
            }
            val d = detail
            if (d == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(48.dp))
                }
                return@Column
            }
            DetailContent(d, celebrate, onShare = ::share, onHome = onHome)
        }
        if (celebrate && detail != null) Confetti(trigger = workoutId, modifier = Modifier.fillMaxSize())
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить тренировку?") },
            text = { Text("Маршрут, пульс и все записи этой тренировки будут удалены без возможности восстановления.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete(onBack)
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun DetailContent(d: WorkoutDetail, celebrate: Boolean, onShare: () -> Unit, onHome: () -> Unit) {
    val route = remember { Animatable(0f) }
    LaunchedEffect(Unit) { route.animateTo(1f, tween(1_800, easing = FastOutSlowInEasing)) }
    val w = d.workout
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (celebrate) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .appear(0)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, Color(0xFFFF9A3D))))
                    .padding(18.dp),
            ) {
                Text("ТРЕНИРОВКА ЗАВЕРШЕНА", style = numberStyle(30), color = Color.White)
                Text("Отличная работа! Вот что получилось:", color = Color.White.copy(alpha = 0.9f))
            }
        }
        RouteShape(
            d.segments,
            Modifier
                .fillMaxWidth()
                .height(300.dp)
                .appear(1)
                .clip(RoundedCornerShape(28.dp)),
            progress = route.value,
        )
        Row(Modifier.fillMaxWidth().appear(2), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val km = rememberCountUp(w.distanceM.toFloat())
            val ms = rememberCountUp(w.durationMs.toFloat())
            StatCard("Дистанция, км", Format.distanceKm(km.toDouble()), Modifier.weight(1f), large = true)
            StatCard("Время", Format.duration(ms.toLong()), Modifier.weight(1f), large = true)
        }
        Row(Modifier.fillMaxWidth().appear(3), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val avg = rememberCountUp((w.avgHr ?: 0).toFloat())
            val max = rememberCountUp((w.maxHr ?: 0).toFloat())
            StatCard("Ср. темп", Format.pace(paceSecPerKm(w.durationMs, w.distanceM)), Modifier.weight(1f))
            StatCard("Ср. пульс", if (w.avgHr == null) "—" else avg.roundToInt().toString(), Modifier.weight(1f))
            StatCard("Макс.", if (w.maxHr == null) "—" else max.roundToInt().toString(), Modifier.weight(1f))
        }
        d.timeAbove?.let { above ->
            Column(Modifier.appear(4), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Выше ${w.upperLimit} уд/мин: ${(above * 100).roundToInt()}% времени",
                    style = MaterialTheme.typography.titleMedium,
                )
                LinearProgressIndicator(
                    progress = { (above * route.value).toFloat() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp),
                    color = ZoneColors.Above,
                    trackColor = ZoneColors.Normal.copy(alpha = 0.6f),
                )
                Text(
                    "Цвет линии маршрута: зелёный — ниже границы, жёлтый — у границы, красный — выше",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        DetailCard("Пульс", Modifier.appear(5)) {
            if (d.heartRate.any { it.bpm != null }) {
                HeartRateChart(
                    series = d.heartRate,
                    upperLimit = w.upperLimit,
                    lowerLimit = w.lowerLimit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    progress = route.value,
                )
            } else {
                Text("Нет данных о пульсе", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (d.sets.isNotEmpty()) DetailCard("Площадка", Modifier.appear(6)) { ExerciseSets(d.sets) }

        d.gpsCoverage?.let {
            Text(
                "GPS: точные координаты в ${(it * 100).roundToInt()}% времени тренировки",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = onShare, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Icon(Icons.Filled.Share, contentDescription = null)
            Text("  Экспорт в GPX")
        }
        Button(
            onClick = onHome,
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
            shape = RoundedCornerShape(30.dp),
        ) { Text("На главную", style = MaterialTheme.typography.titleMedium) }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun DetailCard(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

@Composable
private fun ExerciseSets(sets: List<ExerciseSetEntity>) {
    sets.groupBy { it.exerciseId }.values.forEach { group ->
        val first = group.first()
        val target = group.maxOf { it.target }
        val unit = if (first.timed) " с" else ""
        val ok = group.all { it.reps >= it.target }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(if (ok) ZoneColors.Normal else MaterialTheme.colorScheme.error),
                contentAlignment = Alignment.Center,
            ) { Text(if (ok) "✓" else "✗", color = Color.White, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(first.exerciseName, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "цель $target$unit" + if (ok) " · выполнено" else " · не всё получилось",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(group.joinToString(" · ") { "${it.reps}$unit" }, style = numberStyle(22))
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier, large: Boolean = false) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(value, style = numberStyle(if (large) 40 else 28))
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
