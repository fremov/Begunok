package ru.begunok.ui.mental

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.begunok.mental.MentalResult
import ru.begunok.mental.MentalTest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dateFormat = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("ru"))
private val timeFormat = DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", Locale.forLanguageTag("ru"))

@Composable
fun MentalScreen(viewModel: MentalViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val save by viewModel.saveState.collectAsStateWithLifecycle()
    val deletion by viewModel.deleteState.collectAsStateWithLifecycle()
    var draftName by rememberSaveable { mutableStateOf<String?>(null) }
    var answers by rememberSaveable { mutableStateOf(intArrayOf()) }
    var question by rememberSaveable { mutableStateOf(0) }
    var discarding by rememberSaveable { mutableStateOf(false) }
    var completedName by rememberSaveable { mutableStateOf<String?>(null) }
    var completedAnswers by rememberSaveable { mutableStateOf(intArrayOf()) }
    var completedAt by rememberSaveable { mutableStateOf(0L) }
    var reviewing by rememberSaveable { mutableStateOf<Long?>(null) }
    var deleting by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(save.completion) {
        save.completion?.let { completion ->
            completedName = completion.test.name
            completedAnswers = completion.answers.toIntArray()
            completedAt = completion.completedAt
            draftName = null
            answers = intArrayOf()
            discarding = false
            viewModel.resetSave()
        }
    }
    LaunchedEffect(deletion.deletedId) {
        deletion.deletedId?.let { id ->
            if (reviewing == id) reviewing = null
            deleting = null
            viewModel.resetDelete()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Heading("Самочувствие")
                Text("Отметьте состояние сейчас или пройдите подробный опросник.")
                Text("Ответы и история сохраняются на этом устройстве.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { QuickCheckInPanel(viewModel, state) }
        if (state.loading) {
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text("Загружаем результаты…", Modifier.padding(top = 8.dp))
                }
            }
        }
        state.error?.let { error ->
            item {
                MentalCard {
                    ErrorText(error)
                    OutlinedButton(onClick = viewModel::retry) { Text("Повторить загрузку") }
                }
            }
        }
        item { Heading("Опросники") }
        items(MentalTest.entries, key = { "test_${it.name}" }) { test ->
            TestCard(
                test = test,
                latest = state.results.firstOrNull { it.test == test },
                available = !state.loading && state.error == null,
                onStart = {
                    viewModel.resetSave()
                    answers = IntArray(test.questions.size) { -1 }
                    question = 0
                    draftName = test.name
                },
            )
        }
        item { Heading("История опросников") }
        if (!state.loading && state.error == null && state.results.isEmpty()) {
            item {
                Text(
                    "Пока нет сохранённых результатов. Выберите опросник — здесь появится ваша история, отдельно для каждого теста.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(state.results, key = { "result_${it.id}" }) { result ->
            MentalCard {
                Text(result.test.title, style = MaterialTheme.typography.titleMedium)
                Text(formatTime(result.completedAt), style = MaterialTheme.typography.bodyMedium)
                Text("${result.score} из ${result.test.maxScore}", style = MaterialTheme.typography.titleLarge)
                Text(result.test.interpretation(result.score), style = MaterialTheme.typography.bodyMedium)
                if (needsSupport(result.test, result.answers)) SupportNotice()
                TextButton(onClick = { reviewing = result.id }) { Text("Ответы и подробности") }
                TextButton(onClick = {
                    viewModel.resetDelete()
                    deleting = result.id
                }) { Text("Удалить запись") }
            }
        }
    }

    val draft = draftName?.let { MentalTest.valueOf(it) }
    val completedTest = completedName?.let { MentalTest.valueOf(it) }
    if (draft != null || completedTest != null) {
        FullScreenSheet(scrollKey = draftName to question, onDismiss = {
            if (completedTest != null) completedName = null
            else if (!save.saving) discarding = true
        }) {
            if (draft != null) {
                Questionnaire(
                    test = draft,
                    answers = answers,
                    question = question,
                    saving = save.saving,
                    error = save.error,
                    onAnswer = { index ->
                        answers = answers.copyOf().also { it[question] = index }
                        viewModel.resetSave()
                    },
                    onBack = { question -= 1 },
                    onNext = { question += 1 },
                    onSave = { viewModel.save(draft, answers.toList()) },
                    onClose = { discarding = true },
                )
            } else if (completedTest != null) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Heading("Результат сохранён")
                    ResultDetails(completedTest, completedAnswers.toList(), completedAt)
                    Button(onClick = { completedName = null }, modifier = Modifier.fillMaxWidth()) { Text("К списку тестов") }
                }
            }
        }
    }
    if (discarding && draft != null) {
        AlertDialog(
            onDismissRequest = { discarding = false },
            title = { Text("Выйти из опросника?") },
            text = { Text("Незавершённые ответы не попадут в историю. Если выйти, этот черновик будет удалён.") },
            confirmButton = {
                TextButton(onClick = {
                    draftName = null
                    answers = intArrayOf()
                    discarding = false
                    viewModel.resetSave()
                }) { Text("Выйти без сохранения") }
            },
            dismissButton = { TextButton(onClick = { discarding = false }) { Text("Продолжить") } },
        )
    }
    state.results.firstOrNull { it.id == reviewing }?.let { result ->
        FullScreenSheet(onDismiss = { reviewing = null }) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                TextButton(onClick = { reviewing = null }) { Text("Закрыть историю") }
                ResultDetails(result.test, result.answers, result.completedAt)
                Heading("Ваши ответы")
                result.test.questions.forEachIndexed { index, text ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${index + 1}. $text", style = MaterialTheme.typography.titleSmall)
                        Text(result.test.options[result.answers[index]], color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                OutlinedButton(onClick = {
                    viewModel.resetDelete()
                    deleting = result.id
                }, modifier = Modifier.fillMaxWidth()) { Text("Удалить запись") }
            }
        }
    }
    if (deleting != null) {
        AlertDialog(
            onDismissRequest = { if (!deletion.deleting) deleting = null },
            title = { Text("Удалить результат?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Ответы и результат будут удалены из истории. Отменить удаление нельзя; дата следующего повтора пересчитается по оставшимся записям.")
                    deletion.error?.let { ErrorText(it) }
                    if (deletion.deleting) Text("Удаляем…")
                }
            },
            confirmButton = {
                TextButton(onClick = { deleting?.let(viewModel::delete) }, enabled = !deletion.deleting) {
                    Text(if (deletion.error != null) "Повторить удаление" else "Удалить")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }, enabled = !deletion.deleting) { Text("Отмена") }
            },
        )
    }
}

@Composable
private fun TestCard(test: MentalTest, latest: MentalResult?, available: Boolean, onStart: () -> Unit) {
    MentalCard {
        Text(test.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        Text(test.subtitle, style = MaterialTheme.typography.bodyLarge)
        Text("Период оценки: ${test.period}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (latest != null) {
            Text("${latest.score} из ${test.maxScore}", style = MaterialTheme.typography.headlineMedium)
            Text(test.interpretation(latest.score))
            Text("Последний результат: ${formatDate(latest.completedAt)}", style = MaterialTheme.typography.bodyMedium)
            DueDate(test, latest.completedAt)
            if (needsSupport(test, latest.answers)) SupportNotice()
        } else if (available) {
            Text("Ещё не проходили", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FilledTonalButton(onClick = onStart, enabled = available, modifier = Modifier.fillMaxWidth()) {
            Text(if (latest == null) "Начать ${test.title}" else "Пройти ${test.title} снова")
        }
        SourceLink(test)
    }
}

@Composable
private fun Questionnaire(
    test: MentalTest,
    answers: IntArray,
    question: Int,
    saving: Boolean,
    error: String?,
    onAnswer: (Int) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
) {
    val index = question.coerceIn(test.questions.indices)
    val answered = answers.count { it >= 0 }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TextButton(onClick = onClose, enabled = !saving) { Text("Закрыть опросник") }
        Heading(test.title)
        Text(test.subtitle)
        Text("Период оценки: ${test.period}", style = MaterialTheme.typography.titleMedium)
        Text("Выберите один ответ на каждый вопрос. Здесь нет правильных или неправильных ответов.", style = MaterialTheme.typography.bodyMedium)
        LinearProgressIndicator(progress = { answered.toFloat() / test.questions.size }, modifier = Modifier.fillMaxWidth())
        Text("Вопрос ${index + 1} из ${test.questions.size} · Ответов: $answered", style = MaterialTheme.typography.labelLarge)
        Text(test.questions[index], style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            test.options.forEachIndexed { option, label ->
                Surface(
                    color = if (answers.getOrNull(index) == option) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .selectable(selected = answers.getOrNull(index) == option, enabled = !saving, role = Role.RadioButton, onClick = { onAnswer(option) })
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(selected = answers.getOrNull(index) == option, onClick = null, enabled = !saving)
                        Text(label, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        if (needsSupport(test, answers.toList())) SupportNotice()
        error?.let { ErrorText(it) }
        if (saving) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text("Сохраняем результат… Не закрывайте опросник.")
        }
        if (index == test.questions.lastIndex) {
            Button(
                onClick = onSave,
                enabled = !saving && answers.size == test.questions.size && answers.all { it in test.options.indices },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (error != null) "Повторить сохранение" else "Завершить и сохранить") }
        } else {
            Button(onClick = onNext, enabled = !saving && (answers.getOrNull(index) ?: -1) >= 0, modifier = Modifier.fillMaxWidth()) { Text("Далее") }
        }
        if (index > 0) {
            OutlinedButton(onClick = onBack, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text("Предыдущий вопрос") }
        }
        SourceLink(test)
    }
}

@Composable
private fun ResultDetails(test: MentalTest, answers: List<Int>, completedAt: Long) {
    val score = test.score(answers)
    Heading(test.title)
    Text(test.subtitle)
    Text("Период оценки: ${test.period}")
    Text(formatTime(completedAt), color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text("$score из ${test.maxScore}", style = MaterialTheme.typography.headlineLarge)
    Text(test.interpretation(score), style = MaterialTheme.typography.bodyLarge)
    if (needsSupport(test, answers)) SupportNotice()
    DueDate(test, completedAt)
    SourceLink(test)
}

@Composable
private fun DueDate(test: MentalTest, completedAt: Long) {
    val due = test.nextDue(completedAt)
    val today = LocalDate.now()
    Text(
        when {
            due.isBefore(today) -> "Пора повторить · рекомендованная дата: ${dateFormat.format(due)}"
            due == today -> "Можно повторить сегодня · ${dateFormat.format(due)}"
            else -> "Следующий повтор: ${dateFormat.format(due)}"
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun SupportNotice() {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Поддержка сейчас", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text("Вы отметили мысли о смерти или причинении себе вреда. Это важно независимо от суммы баллов.")
            Text("Если есть непосредственная опасность или вы можете навредить себе, звоните 112 или в местную экстренную службу. По возможности не оставайтесь одни: свяжитесь с человеком, которому доверяете, и обратитесь к специалисту.")
        }
    }
}

@Composable
private fun SourceLink(test: MentalTest) {
    val uriHandler = LocalUriHandler.current
    TextButton(onClick = { uriHandler.openUri(test.sourceUrl) }) { Text("Источник и описание ${test.title}") }
}

@Composable
internal fun MentalCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
internal fun FullScreenSheet(scrollKey: Any? = Unit, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val scroll = rememberScrollState()
    LaunchedEffect(scrollKey) { scroll.scrollTo(0) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(scroll).padding(20.dp)) { content() }
        }
    }
}

@Composable
internal fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
}

@Composable
internal fun ErrorText(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}

private fun needsSupport(test: MentalTest, answers: List<Int>): Boolean = test == MentalTest.PHQ9 && (answers.getOrNull(8) ?: 0) > 0

private fun formatDate(time: Long): String = dateFormat.format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()))
internal fun formatTime(time: Long): String = timeFormat.format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()))

