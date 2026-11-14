package ru.begunok.ui.math

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private enum class LessonFilter(val label: String) {
    All("Все"), NotDone("Не пройдены"), Done("Пройдены"),
}

private enum class MathPage(val label: String) {
    Catalog("Уроки"), Review("Повторение"),
}

@Composable
fun MathScreen(viewModel: MathViewModel, onOpenLesson: (String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val today by rememberMathToday()
    var reviewId by rememberSaveable { mutableStateOf<String?>(null) }
    var page by rememberSaveable { mutableStateOf(MathPage.Catalog) }
    var search by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(LessonFilter.All) }
    if (state.loading || state.loadError || state.catalog == null) {
        MathLoadState(state, viewModel::reload)
        return
    }
    val catalog = state.catalog ?: return
    val completed = state.progress.completedIds
    val filtered = remember(catalog, completed, search, filter) {
        catalog.lessons.filter { lesson ->
            (search.isBlank() || lesson.title.contains(search.trim(), ignoreCase = true) ||
                lesson.order.toString() == search.trim()) && when (filter) {
                LessonFilter.All -> true
                LessonFilter.NotDone -> lesson.id !in completed
                LessonFilter.Done -> lesson.id in completed
            }
        }
    }
    val resume = catalog.lessons.firstOrNull { it.id == state.progress.lastLessonId } ?: catalog.lessons.first()
    val dueCount = state.progress.reviews.count { (id, review) ->
        id in completed && review.due <= today && review.lastReviewed != today
    }
    reviewId?.let { id ->
        catalog.lessons.firstOrNull { it.id == id }?.let { lesson ->
            MathReviewDialog(
                state = state,
                lesson = lesson,
                today = today,
                onDismiss = { reviewId = null },
                onOpenMaterial = { viewModel.openLesson(lesson.id, onOpenLesson) },
                onRate = { rating -> viewModel.rateReview(lesson.id, rating) { reviewId = null } },
                onRetry = viewModel::retryProgress,
            )
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MathPage.entries.forEach { value ->
                FilterChip(
                    selected = page == value,
                    onClick = { page = value },
                    label = { Text(if (value == MathPage.Review) "${value.label} · $dueCount" else value.label) },
                )
            }
        }
        if (state.saving || state.saveError) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                MathSaveStatus(state, viewModel::retryProgress)
            }
        }
        state.notice?.let { notice ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(notice, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = viewModel::dismissNotice) { Text("Понятно") }
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (page) {
                MathPage.Review -> item { MathReviewQueue(state, today, onReview = { reviewId = it }) }
                MathPage.Catalog -> {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(catalog.title, style = MaterialTheme.typography.headlineSmall)
                                Text("Пройдено ${completed.size} из ${catalog.lessons.size}", style = MaterialTheme.typography.titleMedium)
                                LinearProgressIndicator(
                                    progress = { completed.size.toFloat() / catalog.lessons.size },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text(
                                    "Полные уроки Spacemath: теория, задания, изображения и скрытые ответы доступны без интернета.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Button(
                                    onClick = { viewModel.openLesson(resume.id, onOpenLesson) },
                                    enabled = !state.saving,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(if (state.progress.lastLessonId == null) "Начать: ${resume.title}" else "Продолжить: ${resume.title}")
                                }
                            }
                        }
                    }
                    item {
                        OutlinedTextField(
                            value = search,
                            onValueChange = { search = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Название или номер урока") },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                            singleLine = true,
                        )
                    }
                    item {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            LessonFilter.entries.forEach { value ->
                                FilterChip(selected = value == filter, onClick = { filter = value }, label = { Text(value.label) })
                            }
                        }
                    }
                    item { Text("Уроки · ${filtered.size}", style = MaterialTheme.typography.titleMedium) }
                    if (filtered.isEmpty()) {
                        item {
                            Text(
                                if (search.isNotBlank()) "Уроки не найдены. Измените поиск или фильтр." else "В этом фильтре пока нет уроков.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(filtered, key = { it.id }) { lesson ->
                        Column {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(
                                    Modifier.weight(1f).clickable(enabled = !state.saving) {
                                        viewModel.openLesson(lesson.id, onOpenLesson)
                                    }.padding(vertical = 12.dp, horizontal = 4.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text("${lesson.order}. ${lesson.title}", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        if (lesson.id in completed) "Пройден" else "Не пройден",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Checkbox(
                                        checked = lesson.id in completed,
                                        onCheckedChange = { viewModel.setCompleted(lesson.id, it) },
                                        enabled = !state.saving,
                                        modifier = Modifier.mathCompletionDescription(lesson.title),
                                    )
                                    Text("Пройден", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun MathLoadState(state: MathUiState, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        if (state.loading) {
            CircularProgressIndicator()
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Не удалось загрузить каталог или сохранённый прогресс.")
                Button(onClick = onRetry) { Text("Повторить") }
            }
        }
    }
}

@Composable
internal fun MathSaveStatus(state: MathUiState, onRetry: () -> Unit) {
    if (state.saving) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
    } else if (state.saveError) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Не удалось сохранить действие. Если дата повторения изменилась, закройте окно и выберите тему заново.", color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onRetry) { Text("Повторить сохранение") }
        }
    }
}
