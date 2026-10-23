package ru.begunok.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import ru.begunok.data.WorkoutRepository
import ru.begunok.data.db.WorkoutEntity
import ru.begunok.ui.Format

class HistoryViewModel(repository: WorkoutRepository) : ViewModel() {
    val workouts: StateFlow<List<WorkoutEntity>?> = repository.observeFinished()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun HistoryScreen(viewModel: HistoryViewModel, onOpen: (Long) -> Unit) {
    val workouts by viewModel.workouts.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        Text("История", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp))
        val list = workouts ?: return@Column
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Здесь появятся завершённые тренировки",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }
        LazyColumn {
            items(list, key = { it.id }) { workout ->
                WorkoutRow(workout, onClick = { onOpen(workout.id) })
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun WorkoutRow(workout: WorkoutEntity, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(Format.dateTime(workout.startTime), style = MaterialTheme.typography.titleMedium)
            Text(
                listOfNotNull(workout.programName, Format.duration(workout.durationMs)).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "${Format.distanceKm(workout.distanceM)} км",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "♥ ${Format.heartRate(workout.avgHr)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
