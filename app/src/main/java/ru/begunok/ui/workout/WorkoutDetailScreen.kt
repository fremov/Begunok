package ru.begunok.ui.workout

import android.content.Context
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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.begunok.data.WorkoutRepository
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
)

class WorkoutDetailViewModel(
    private val workoutId: Long,
    private val repository: WorkoutRepository,
    private val appContext: Context,
) : ViewModel() {
    val detail: StateFlow<WorkoutDetail?> = repository.observeWorkout(workoutId)
        .filterNotNull()
        .map { workout ->
            val samples = repository.samples(workout.id)
            WorkoutDetail(
                workout = workout,
                segments = buildRouteSegments(samples, workout.upperLimit),
                heartRate = heartRateSeries(samples),
                timeAbove = timeAboveFraction(samples, workout.upperLimit),
                gpsCoverage = gpsCoverage(samples),
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    suspend fun exportGpx(): Uri? = withContext(Dispatchers.IO) {
        val workout = detail.value?.workout ?: return@withContext null
        val samples = repository.samples(workoutId)
        val title = workout.programName ?: "Бег"
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
fun WorkoutDetailScreen(viewModel: WorkoutDetailViewModel, onBack: () -> Unit, onHome: () -> Unit) {
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

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") }
            Column(Modifier.weight(1f)) {
                Text(detail?.workout?.programName ?: "Тренировка", style = MaterialTheme.typography.titleLarge)
                detail?.let { Text(Format.dateTime(it.workout.startTime), style = MaterialTheme.typography.bodySmall) }
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
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            RouteShape(
                d.segments,
                Modifier
                    .fillMaxWidth()
                    .height(320.dp),
            )
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Stat("Дистанция, км", Format.distanceKm(d.workout.distanceM), Modifier.weight(1f), large = true)
                    Stat("Время", Format.duration(d.workout.durationMs), Modifier.weight(1f), large = true)
                    Stat(
                        "Ср. темп",
                        Format.pace(paceSecPerKm(d.workout.durationMs, d.workout.distanceM)),
                        Modifier.weight(1f),
                        large = true,
                    )
                }
                Row(Modifier.fillMaxWidth()) {
                    Stat("Ср. пульс", Format.heartRate(d.workout.avgHr), Modifier.weight(1f))
                    Stat("Макс. пульс", Format.heartRate(d.workout.maxHr), Modifier.weight(1f))
                    Stat(
                        "Выше ${d.workout.upperLimit}",
                        d.timeAbove?.let { "${(it * 100).roundToInt()}%" } ?: "—",
                        Modifier.weight(1f),
                    )
                }
                Text(
                    "Цвет линии: зелёный — ниже границы, жёлтый — у границы, красный — выше",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text("Пульс", style = MaterialTheme.typography.titleMedium)
                if (d.heartRate.any { it.bpm != null }) {
                    HeartRateChart(
                        series = d.heartRate,
                        upperLimit = d.workout.upperLimit,
                        lowerLimit = d.workout.lowerLimit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                    )
                } else {
                    Text("Нет данных о пульсе", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                d.gpsCoverage?.let {
                    Text(
                        "GPS: точные координаты в ${(it * 100).roundToInt()}% времени тренировки",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = ::share, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Share, contentDescription = null)
                    Text("  Экспорт в GPX")
                }
                Button(
                    onClick = onHome,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                ) { Text("На главную", style = MaterialTheme.typography.titleMedium) }
            }
        }
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
private fun Stat(label: String, value: String, modifier: Modifier = Modifier, large: Boolean = false) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = if (large) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
