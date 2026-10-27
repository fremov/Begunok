package ru.begunok.ui.plan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.begunok.data.SettingsRepository
import ru.begunok.workout.DayConfig
import ru.begunok.workout.DayType
import ru.begunok.workout.ExerciseCatalog
import ru.begunok.workout.Ladder
import ru.begunok.workout.Slot
import ru.begunok.workout.formatSpan

class PlanEditorViewModel(val type: DayType, private val settings: SettingsRepository) : ViewModel() {
    val config: StateFlow<DayConfig> = settings.settings
        .map { it.dayConfig(type) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, DayConfig.default(type))

    fun update(transform: (DayConfig) -> DayConfig) {
        viewModelScope.launch { settings.setDayConfig(type, transform(config.value)) }
    }

    fun reset() {
        viewModelScope.launch { settings.setDayConfig(type, null) }
    }
}

@Composable
fun PlanEditorScreen(viewModel: PlanEditorViewModel, onBack: () -> Unit) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val circuit = viewModel.type == DayType.Run

    fun updateSlots(transform: (MutableList<Slot>) -> Unit) =
        viewModel.update { c -> c.copy(slots = c.slots.toMutableList().also(transform)) }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") }
            Text("${viewModel.type.label}: упражнения", style = MaterialTheme.typography.titleLarge)
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Только турник, брусья и пол. У каждого движения есть ступени от лёгкой к сложной. " +
                    "Выберите ступень, с которой начать: когда цель дойдёт до верхней границы, приложение само " +
                    "переведёт на следующую.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            config.slots.forEachIndexed { index, slot ->
                val ladder = ExerciseCatalog.ladder(slot.ladderId) ?: return@forEachIndexed
                SlotCard(
                    ladder = ladder,
                    selected = slot.variationId,
                    canMoveUp = index > 0,
                    canMoveDown = index < config.slots.lastIndex,
                    onSelect = { id -> updateSlots { it[index] = slot.copy(variationId = id) } },
                    onMove = { delta -> updateSlots { it.add(index + delta, it.removeAt(index)) } },
                    onRemove = { updateSlots { it.removeAt(index) } },
                )
            }
            AddExercise(config) { ladder -> updateSlots { it += Slot(ladder.id, ladder.variations.first().id) } }

            Text("Подходы и отдых", style = MaterialTheme.typography.titleMedium)
            Stepper(if (circuit) "Кругов" else "Подходов", config.sets.toString(),
                onMinus = { viewModel.update { it.copy(sets = (it.sets - 1).coerceAtLeast(1)) } },
                onPlus = { viewModel.update { it.copy(sets = (it.sets + 1).coerceAtMost(MAX_SETS)) } },
            )
            Stepper(if (circuit) "Отдых между упражнениями" else "Отдых между подходами", restLabel(config.restBetweenSetsSec),
                onMinus = { viewModel.update { it.copy(restBetweenSetsSec = (it.restBetweenSetsSec - REST_STEP).coerceAtLeast(0)) } },
                onPlus = { viewModel.update { it.copy(restBetweenSetsSec = (it.restBetweenSetsSec + REST_STEP).coerceAtMost(MAX_REST)) } },
            )
            Stepper(if (circuit) "Отдых между кругами" else "Отдых между упражнениями", restLabel(config.restBetweenExercisesSec),
                onMinus = { viewModel.update { it.copy(restBetweenExercisesSec = (it.restBetweenExercisesSec - REST_STEP).coerceAtLeast(0)) } },
                onPlus = { viewModel.update { it.copy(restBetweenExercisesSec = (it.restBetweenExercisesSec + REST_STEP).coerceAtMost(MAX_REST)) } },
            )
            TextButton(onClick = viewModel::reset) { Text("Сбросить к стандартному плану") }
        }
    }
}

@Composable
private fun SlotCard(
    ladder: Ladder,
    selected: String,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onSelect: (String) -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ladder.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Выше")
                }
                IconButton(onClick = { onMove(1) }, enabled = canMoveDown) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Ниже")
                }
                IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, contentDescription = "Убрать") }
            }
            ladder.variations.forEachIndexed { i, variation ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(variation.id) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = variation.id == selected, onClick = { onSelect(variation.id) })
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${i + 1}. ${variation.name}",
                            fontWeight = if (variation.id == selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                        Text(
                            "${variation.amount(variation.startTarget)} → ${variation.amount(variation.maxTarget)} · ${variation.hint}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AddExercise(config: DayConfig, onAdd: (Ladder) -> Unit) {
    val available = ExerciseCatalog.ladders.filter { l -> config.slots.none { it.ladderId == l.id } }
    if (available.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text("  Добавить упражнение")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            available.forEach { ladder ->
                DropdownMenuItem(
                    text = { Text(ladder.name) },
                    onClick = {
                        expanded = false
                        onAdd(ladder)
                    },
                )
            }
        }
    }
}

@Composable
private fun Stepper(title: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.weight(1f))
        FilledTonalIconButton(onClick = onMinus) { Icon(Icons.Filled.Remove, contentDescription = "Меньше") }
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        FilledTonalIconButton(onClick = onPlus) { Icon(Icons.Filled.Add, contentDescription = "Больше") }
    }
}

private fun restLabel(sec: Int): String = if (sec == 0) "нет" else formatSpan(sec)

private const val MAX_SETS = 6
private const val REST_STEP = 15
private const val MAX_REST = 300
