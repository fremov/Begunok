package ru.begunok.ui.math

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.begunok.data.MathLesson
import ru.begunok.math.MathRating
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val reviewDateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy")

@Composable
internal fun MathReviewQueue(state: MathUiState, today: LocalDate, onReview: (String) -> Unit) {
    val lessons = state.catalog?.lessons.orEmpty()
    val due = lessons.filter { lesson ->
        val review = state.progress.reviews[lesson.id]
        lesson.id in state.progress.completedIds && review != null && review.due <= today && review.lastReviewed != today
    }.sortedWith(compareBy<MathLesson> { state.progress.reviews.getValue(it.id).due }.thenBy { it.order })
    val upcoming = state.progress.reviews.filterKeys { it in state.progress.completedIds }
        .values.filter { it.due > today }.minOfOrNull { it.due }
    var showAll by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("К повторению · ${due.size}", style = MaterialTheme.typography.titleLarge)
            Text(
                "Интервалы на основе SM-2: после завершения темы первое повторение завтра. Сначала вспомните правило или решите задачу без подсказок, затем оцените себя. Метод не гарантирует запоминание.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (due.isEmpty()) {
                Text(if (upcoming != null) "Следующее повторение: ${upcoming.format(reviewDateFormat)}" else "Здесь появятся повторения пройденных тем.")
            }
            (if (showAll) due else due.take(5)).forEach { lesson ->
                val review = state.progress.reviews.getValue(lesson.id)
                OutlinedButton(onClick = { onReview(lesson.id) }, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) {
                    Text("${lesson.order}. ${lesson.title} · ${review.due.format(reviewDateFormat)}")
                }
            }
            if (due.size > 5) {
                TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Свернуть очередь" else "Все повторения (${due.size})") }
            }
        }
    }
}

@Composable
internal fun MathReviewDialog(
    state: MathUiState,
    lesson: MathLesson,
    today: LocalDate,
    onDismiss: () -> Unit,
    onOpenMaterial: () -> Unit,
    onRate: (MathRating) -> Unit,
    onRetry: () -> Unit,
) {
    val review = state.progress.reviews[lesson.id]
    val eligible = lesson.id in state.progress.completedIds && review != null && review.due <= today && review.lastReviewed != today
    AlertDialog(
        onDismissRequest = { if (!state.saving) onDismiss() },
        title = { Text("Повторение: ${lesson.order}. ${lesson.title}") },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Не открывайте материал сразу. Вспомните правило и объясните его своими словами или решите упражнение без подсказок. Затем проверьте себя и честно оцените вспоминание.")
                Text("Оценка — один раз в день, только для наступившего повторения. При «Снова» или «Трудно» перечитайте материал и потренируйтесь сейчас; это не создаёт второе повторение за день.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onOpenMaterial, enabled = !state.saving) { Text("Открыть материал") }
                if (!eligible) Text("Сегодня эта тема недоступна для оценки. Вернитесь в очередь в назначенный день.")
                MathRating.entries.forEach { rating ->
                    Button(onClick = { onRate(rating) }, enabled = eligible && !state.saving, modifier = Modifier.fillMaxWidth()) {
                        Text(rating.label)
                    }
                }
                MathSaveStatus(state, onRetry)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, enabled = !state.saving) { Text("Закрыть") } },
    )
}
