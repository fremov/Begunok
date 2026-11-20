package ru.begunok.ui.math

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.begunok.math.MathActivityKind

@Composable
fun MathReaderScreen(
    viewModel: MathViewModel,
    lessonId: String,
    onOpenLesson: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val catalog = state.catalog
    val index = catalog?.lessons?.indexOfFirst { it.id == lessonId } ?: -1
    val lessonIds = remember(catalog) { catalog?.lessons.orEmpty().map { it.id }.toSet() }
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    if (state.loading || state.loadError || catalog == null) {
        MathLoadState(state, viewModel::reload)
    } else if (index < 0) {
        Box(Modifier.fillMaxSize().padding(16.dp)) { Text("Урок отсутствует в каталоге.") }
    } else {
        val previous = catalog.lessons.getOrNull(index - 1)?.id
        val next = catalog.lessons.getOrNull(index + 1)?.id
        val completed = lessonId in state.progress.completedIds
        val tasks = remember(state.progress.events, lessonId) {
            state.progress.events.count { it.lessonId == lessonId && it.kind == MathActivityKind.Task }
        }
        val openLesson: (String) -> Unit = { id -> viewModel.openLesson(id, onOpenLesson) }
        MathWebView(
            lessonId = lessonId,
            lessonIds = lessonIds,
            isDark = isDark,
            controls = MathReaderControls(previous != null, next != null, completed, tasks, state.saving, state.saveError),
            onOpenLesson = openLesson,
            onAction = { action ->
                if (!state.saving) {
                    when (action) {
                        MathReaderAction.Previous -> previous?.let(openLesson)
                        MathReaderAction.Next -> next?.let(openLesson)
                        MathReaderAction.Complete -> viewModel.setCompleted(lessonId, !completed)
                        MathReaderAction.Task -> viewModel.recordTask(lessonId)
                        MathReaderAction.Retry -> viewModel.retryProgress()
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
