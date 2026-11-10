package ru.begunok.ui.math

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.begunok.math.MathActivityKind

@Composable
fun MathReaderScreen(
    viewModel: MathViewModel,
    lessonId: String,
    onBack: () -> Unit,
    onOpenLesson: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val catalog = state.catalog
    val lesson = catalog?.lessons?.firstOrNull { it.id == lessonId }
    val lessonIds = remember(catalog) { catalog?.lessons.orEmpty().map { it.id }.toSet() }
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val headerLimit = maxHeight * 0.35f
        val controlsLimit = maxHeight * 0.35f
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = headerLimit).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "К каталогу математики")
                    }
                    Text("Spacemath · офлайн", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                }
                if (lesson != null) {
                    Text("${lesson.order}. ${lesson.title}", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Теория, задания, изображения и скрытые ответы сохранены на устройстве. Интернет не требуется.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider()
            if (state.loading || state.loadError || catalog == null) {
                Column(Modifier.weight(1f)) { MathLoadState(state, viewModel::reload) }
            } else if (lesson == null) {
                Text("Урок отсутствует в каталоге.", modifier = Modifier.padding(16.dp))
            } else {
                MathWebView(
                    lessonId = lesson.id,
                    lessonIds = lessonIds,
                    isDark = isDark,
                    onOpenLesson = { id -> viewModel.openLesson(id, onOpenLesson) },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                HorizontalDivider()
                Column(
                    Modifier.fillMaxWidth().heightIn(max = controlsLimit)
                        .verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val index = catalog.lessons.indexOf(lesson)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(
                            onClick = { viewModel.openLesson(catalog.lessons[index - 1].id, onOpenLesson) },
                            enabled = !state.saving && index > 0,
                            modifier = Modifier.weight(1f),
                        ) { Text("Предыдущий") }
                        Text("${index + 1} / ${catalog.lessons.size}", modifier = Modifier.align(Alignment.CenterVertically))
                        TextButton(
                            onClick = { viewModel.openLesson(catalog.lessons[index + 1].id, onOpenLesson) },
                            enabled = !state.saving && index < catalog.lessons.lastIndex,
                            modifier = Modifier.weight(1f),
                        ) { Text("Следующий") }
                    }
                    MathSaveStatus(state, viewModel::retryProgress)
                    state.notice?.let { notice ->
                        Text(notice, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = viewModel::dismissNotice) { Text("Понятно") }
                    }
                    val completed = lesson.id in state.progress.completedIds
                    FilledTonalButton(
                        onClick = { viewModel.setCompleted(lesson.id, !completed) },
                        enabled = !state.saving,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (completed) "Пройден · снять отметку" else "Отметить урок пройденным") }
                    val tasks = state.progress.events.count { it.lessonId == lesson.id && it.kind == MathActivityKind.Task }
                    OutlinedButton(
                        onClick = { viewModel.recordTask(lesson.id) },
                        enabled = !state.saving,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Решил(а) одну задачу · +1 (отмечено: $tasks)") }
                    Text(
                        "Задачи отмечаете вы: раскрытие ответа не засчитывается. Отметка урока относится только к «${lesson.title}».",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
