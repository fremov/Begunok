package ru.begunok.ui.history

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
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
import ru.begunok.ui.common.appear
import ru.begunok.ui.common.sharedBounds
import ru.begunok.ui.theme.StageColors
import ru.begunok.ui.theme.ZoneColors
import ru.begunok.ui.theme.numberStyle
import ru.begunok.workout.DayType
import ru.begunok.workout.Grouping
import ru.begunok.workout.HistoryGroup
import ru.begunok.workout.groupWorkouts
import ru.begunok.workout.plural
import java.time.LocalDate

class HistoryViewModel(repository: WorkoutRepository) : ViewModel() {
    val workouts: StateFlow<List<WorkoutEntity>?> = repository.observeFinished()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun HistoryScreen(viewModel: HistoryViewModel, onOpen: (Long) -> Unit) {
    val workouts by viewModel.workouts.collectAsStateWithLifecycle()
    var grouping by rememberSaveable { mutableStateOf(Grouping.Week) }
    var collapsed by rememberSaveable { mutableStateOf(listOf<String>()) }
    val list = workouts
    val groups = remember(list, grouping) { list?.let { groupWorkouts(it, grouping, LocalDate.now()) }.orEmpty() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "title") {
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("История", style = MaterialTheme.typography.displaySmall)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    Grouping.entries.forEachIndexed { i, g ->
                        SegmentedButton(
                            selected = g == grouping,
                            onClick = { grouping = g },
                            shape = SegmentedButtonDefaults.itemShape(i, Grouping.entries.size),
                            icon = {},
                        ) { Text(g.label) }
                    }
                }
            }
        }
        if (list != null && list.isEmpty()) {
            item(key = "empty") {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("Здесь появятся завершённые тренировки", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        var shown = 0
        groups.forEach { group ->
            val open = group.key !in collapsed
            val offset = shown
            stickyHeader(key = group.key) {
                GroupHeader(group, open, Modifier.animateItem()) {
                    collapsed = if (open) collapsed + group.key else collapsed - group.key
                }
            }
            if (open) {
                shown += group.workouts.size
                itemsIndexed(group.workouts, key = { _, w -> w.id }) { i, workout ->
                    WorkoutCard(workout, Modifier.animateItem().appear(offset + i)) { onOpen(workout.id) }
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(group: HistoryGroup, open: Boolean, modifier: Modifier = Modifier, onToggle: () -> Unit) {
    val rotation by animateFloatAsState(if (open) 0f else -90f, label = "chevron")
    Row(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .clickable(onClick = onToggle)
            .padding(top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(group.title, style = MaterialTheme.typography.headlineSmall)
                group.subtitle?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val parts = buildList {
                add("${group.workouts.size} ${plural(group.workouts.size, "тренировка", "тренировки", "тренировок")}")
                if (group.distanceM > 0) add("${Format.distanceKm(group.distanceM)} км")
                add(Format.duration(group.durationMs))
                if (group.strengthDays > 0) add("силовых ${group.strengthDays}")
            }
            Text(parts.joinToString(" · "), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Icon(
            Icons.Filled.ExpandMore,
            contentDescription = if (open) "Свернуть" else "Развернуть",
            modifier = Modifier.rotate(rotation),
        )
    }
}

@Composable
private fun WorkoutCard(workout: WorkoutEntity, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val (icon, color) = when (workout.dayType) {
        DayType.Strength.name -> Icons.Filled.FitnessCenter to StageColors.Warmup
        DayType.Run.name -> Icons.AutoMirrored.Filled.DirectionsRun to StageColors.Run
        else -> Icons.AutoMirrored.Filled.DirectionsRun to StageColors.Walk
    }
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .sharedBounds("workout-${workout.id}"),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = null, tint = color) }
            Column(Modifier.weight(1f)) {
                Text(Format.workoutTitle(workout) ?: "Пробежка", style = MaterialTheme.typography.titleMedium)
                Text(
                    Format.dateTime(workout.startTime),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
                    Text(Format.duration(workout.durationMs), style = MaterialTheme.typography.labelLarge)
                    Text("♥ ${Format.heartRate(workout.avgHr)}", style = MaterialTheme.typography.labelLarge, color = ZoneColors.Above)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(Format.distanceKm(workout.distanceM), style = numberStyle(30))
                Text("км", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
