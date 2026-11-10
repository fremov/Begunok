package ru.begunok.ui.mental

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.begunok.mental.QuickCheckIn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Composable
internal fun QuickCheckInPanel(viewModel: MentalViewModel, state: MentalUiState) {
    val save by viewModel.checkInSaveState.collectAsStateWithLifecycle()
    val deletion by viewModel.checkInDeleteState.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf(false) }
    var mood by rememberSaveable { mutableStateOf(-1) }
    var tension by rememberSaveable { mutableStateOf(-1) }
    var energy by rememberSaveable { mutableStateOf(-1) }
    var context by rememberSaveable { mutableStateOf("") }
    var discarding by rememberSaveable { mutableStateOf(false) }
    var saved by rememberSaveable { mutableStateOf(false) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    var reviewing by rememberSaveable { mutableStateOf<Long?>(null) }
    var deleting by rememberSaveable { mutableStateOf<Long?>(null) }

    LaunchedEffect(save.completed) {
        if (save.completed) {
            open = false
            discarding = false
            mood = -1
            tension = -1
            energy = -1
            context = ""
            saved = true
            // Keep the completion guard until the user explicitly starts another entry.
        }
    }
    LaunchedEffect(deletion.deletedId) {
        deletion.deletedId?.let { id ->
            if (reviewing == id) reviewing = null
            deleting = null
            viewModel.resetCheckInDelete()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MentalCard {
            Heading("Как вы сейчас?")
            Text("Настроение, напряжение и энергия · 15–20 секунд")
            Text("Можно отмечать несколько раз в день.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = {
                viewModel.resetCheckInSave()
                saved = false
                open = true
            }, enabled = !save.saving, modifier = Modifier.fillMaxWidth()) { Text("Отметить состояние сейчас") }
            if (saved) Text("Отметка сохранена", color = MaterialTheme.colorScheme.primary)
            val today = LocalDate.now()
            val latestToday = state.checkIns.firstOrNull {
                Instant.ofEpochMilli(it.completedAt).atZone(ZoneId.systemDefault()).toLocalDate() == today
            }
            if (latestToday != null) {
                Text("Последняя отметка сегодня", style = MaterialTheme.typography.titleSmall)
                CheckInDetails(latestToday)
            } else if (!state.loading && state.error == null) {
                Text("Сегодня ещё нет отметок", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Heading("История коротких отметок")
        if (!state.loading && state.error == null && state.checkIns.isEmpty()) {
            Text("Здесь появятся время и три оценки каждой отметки.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val visible = if (showAll) state.checkIns else state.checkIns.take(5)
        visible.forEach { entry ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                CheckInDetails(entry)
                TextButton(onClick = { reviewing = entry.id }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Text("Открыть отметку")
                }
                HorizontalDivider()
            }
        }
        if (state.checkIns.size > 5) {
            TextButton(onClick = { showAll = !showAll }) {
                Text(if (showAll) "Показать последние 5" else "Все отметки (${state.checkIns.size})")
            }
        }
    }

    val requestClose = {
        if (!save.saving) {
            if (mood >= 0 || tension >= 0 || energy >= 0 || context.isNotBlank()) discarding = true
            else open = false
        }
    }
    if (open) {
        FullScreenSheet(onDismiss = requestClose) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = requestClose, enabled = !save.saving) { Text("Закрыть") }
                Heading("Состояние сейчас")
                Text("Выберите от 0 до 10 для каждой оценки.", style = MaterialTheme.typography.bodyMedium)
                CheckInMeasure("Настроение", "очень плохо", "отлично", mood, !save.saving) {
                    mood = it
                    viewModel.resetCheckInSave()
                }
                CheckInMeasure("Напряжение", "нет напряжения", "крайне сильное", tension, !save.saving) {
                    tension = it
                    viewModel.resetCheckInSave()
                }
                CheckInMeasure("Энергия", "совсем нет сил", "много сил", energy, !save.saving) {
                    energy = it
                    viewModel.resetCheckInSave()
                }
                OutlinedTextField(
                    value = context,
                    onValueChange = { context = it; viewModel.resetCheckInSave() },
                    enabled = !save.saving,
                    label = { Text("Контекст · необязательно") },
                    placeholder = { Text("Например: после прогулки, работа, дома") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                )
                save.error?.let { ErrorText(it) }
                if (save.saving) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Button(
                    onClick = { viewModel.saveCheckIn(mood, tension, energy, context) },
                    enabled = !save.saving && mood in 0..10 && tension in 0..10 && energy in 0..10,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (save.saving) "Сохраняем…" else if (save.error != null) "Повторить сохранение" else "Сохранить отметку") }
            }
        }
    }
    if (discarding) {
        AlertDialog(
            onDismissRequest = { discarding = false },
            title = { Text("Удалить черновик отметки?") },
            text = { Text("Выбранные оценки и контекст не сохранятся.") },
            confirmButton = {
                TextButton(onClick = {
                    open = false
                    discarding = false
                    mood = -1
                    tension = -1
                    energy = -1
                    context = ""
                    viewModel.resetCheckInSave()
                }) { Text("Удалить черновик") }
            },
            dismissButton = { TextButton(onClick = { discarding = false }) { Text("Продолжить") } },
        )
    }
    state.checkIns.firstOrNull { it.id == reviewing }?.let { entry ->
        AlertDialog(
            onDismissRequest = { reviewing = null },
            title = { Text("Отметка состояния") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { CheckInDetails(entry) } },
            confirmButton = { TextButton(onClick = { reviewing = null }) { Text("Закрыть") } },
            dismissButton = {
                TextButton(onClick = { viewModel.resetCheckInDelete(); deleting = entry.id }) { Text("Удалить") }
            },
        )
    }
    if (deleting != null) {
        AlertDialog(
            onDismissRequest = { if (!deletion.deleting) deleting = null },
            title = { Text("Удалить отметку?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Три оценки и контекст будут удалены. Отменить это действие нельзя.")
                    deletion.error?.let { ErrorText(it) }
                    if (deletion.deleting) Text("Удаляем…")
                }
            },
            confirmButton = {
                TextButton(onClick = { deleting?.let(viewModel::deleteCheckIn) }, enabled = !deletion.deleting) {
                    Text(if (deletion.error != null) "Повторить удаление" else "Удалить")
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }, enabled = !deletion.deleting) { Text("Отмена") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CheckInMeasure(title: String, low: String, high: String, value: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$title сейчас · ${if (value < 0) "выберите оценку" else "$value из 10"}", style = MaterialTheme.typography.titleSmall)
        Text("0 — $low · 10 — $high", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(
            modifier = Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            maxItemsInEachRow = 6,
        ) {
            (0..10).forEach { score ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (value == score) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Text(
                        text = score.toString(),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            .selectable(selected = value == score, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(score) })
                            .semantics { contentDescription = "$title сейчас: $score из 10" }
                            .padding(horizontal = 12.dp, vertical = 14.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CheckInDetails(entry: QuickCheckIn) {
    Text(formatTime(entry.completedAt), style = MaterialTheme.typography.titleSmall)
    Text("Настроение ${entry.mood}/10 · Напряжение ${entry.tension}/10 · Энергия ${entry.energy}/10", style = MaterialTheme.typography.bodyMedium)
    entry.context?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}
