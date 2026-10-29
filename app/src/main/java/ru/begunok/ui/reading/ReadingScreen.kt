package ru.begunok.ui.reading

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.begunok.data.db.BookEntity
import ru.begunok.ui.common.AnimatedNumber
import ru.begunok.ui.common.RingTimer
import ru.begunok.ui.common.appear
import ru.begunok.ui.theme.numberStyle
import ru.begunok.workout.plural
import java.text.SimpleDateFormat
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale

private val Ink = Color(0xFF0FA38E)
private val InkDeep = Color(0xFF0B6E73)
private val ru = Locale.forLanguageTag("ru")

@Composable
fun ReadingScreen(viewModel: ReadingViewModel) {
    val overview by viewModel.overview.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf<Long?>(null) }
    var logging by rememberSaveable { mutableStateOf<Long?>(null) }
    var deleting by rememberSaveable { mutableStateOf<Long?>(null) }
    val o = overview ?: return
    val books = o.reading.map { it.book } + o.finished

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.appear(0)) { TodayHero(o, onGoal = viewModel::setGoal) }
        Box(Modifier.appear(1)) { WeekCard(o) }
        Row(Modifier.appear(2).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Читаю сейчас", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            FilledTonalButton(onClick = { adding = true }) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(" Книга")
            }
        }
        if (o.reading.isEmpty()) {
            EmptyBooks { adding = true }
        }
        o.reading.forEachIndexed { i, progress ->
            Box(Modifier.appear(3 + i)) {
                BookCard(
                    progress = progress,
                    onLog = { logging = progress.book.id },
                    onEdit = { editing = progress.book.id },
                    onDelete = { deleting = progress.book.id },
                )
            }
        }
        if (o.finished.isNotEmpty()) FinishedCard(o.finished, onEdit = { editing = it }, onDelete = { deleting = it })
    }

    if (adding) {
        BookDialog(
            book = null,
            onDismiss = { adding = false },
            onSave = { title, author, pages, current ->
                adding = false
                viewModel.addBook(title, author, pages, current)
            },
        )
    }
    books.firstOrNull { it.id == editing }?.let { book ->
        BookDialog(
            book = book,
            onDismiss = { editing = null },
            onSave = { title, author, pages, _ ->
                editing = null
                viewModel.editBook(book.id, title, author, pages)
            },
        )
    }
    books.firstOrNull { it.id == logging }?.let { book ->
        LogDialog(
            book = book,
            onDismiss = { logging = null },
            onSave = { page ->
                logging = null
                viewModel.setPage(book.id, page)
            },
        )
    }
    books.firstOrNull { it.id == deleting }?.let { book ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Удалить «${book.title}»?") },
            text = { Text("Книга и все записи о её чтении будут удалены, статистика пересчитается.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    viewModel.deleteBook(book.id)
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun TodayHero(o: ReadingOverview, onGoal: (Int) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(32.dp))
            .background(Brush.linearGradient(listOf(Ink, InkDeep)))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("СЕГОДНЯ", style = numberStyle(28), color = Color.White)
                Text(
                    when {
                        o.today >= o.goal -> "дневная цель выполнена"
                        o.today > 0 -> "осталось ${o.goal - o.today} ${pagesWord(o.goal - o.today)}"
                        else -> "ещё не читали"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.9f),
                )
                Text(
                    "${o.streak} ${plural(o.streak, "день", "дня", "дней")} подряд",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.85f),
                )
            }
            RingTimer(
                progress = o.today.toFloat() / o.goal.coerceAtLeast(1),
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.25f),
                modifier = Modifier.size(132.dp),
                stroke = 11.dp,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AnimatedNumber("${o.today}", numberStyle(40), color = Color.White)
                    Text("из ${o.goal} стр.", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.85f))
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White.copy(alpha = 0.14f))
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Цель в день", color = Color.White, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = { onGoal(o.goal - ReadingViewModel.GOAL_STEP) }) {
                Icon(Icons.Filled.Remove, contentDescription = "Меньше", tint = Color.White)
            }
            Text("${o.goal}", style = numberStyle(24), color = Color.White)
            IconButton(onClick = { onGoal(o.goal + ReadingViewModel.GOAL_STEP) }) {
                Icon(Icons.Filled.Add, contentDescription = "Больше", tint = Color.White)
            }
        }
    }
}

@Composable
private fun PanelCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

@Composable
private fun WeekCard(o: ReadingOverview) {
    PanelCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat("${o.weekTotal}", "стр. за 7 дней", Modifier.weight(1f))
            Stat(String.format(ru, "%.0f", o.pagesPerDay), "стр. в день в среднем", Modifier.weight(1f))
        }
        val max = maxOf(o.goal, o.week.maxOf { it.second }).coerceAtLeast(1)
        Row(
            Modifier
                .fillMaxWidth()
                .height(96.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            o.week.forEach { (day, pages) ->
                val fraction by animateFloatAsState(pages.toFloat() / max, tween(600), label = "bar")
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    if (pages > 0) Text("$pages", style = MaterialTheme.typography.labelSmall)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(BAR_HEIGHT * fraction.coerceAtLeast(0.04f))
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (pages >= o.goal) Ink else Ink.copy(alpha = if (pages > 0) 0.45f else 0.15f)),
                    )
                    Text(
                        day.dayOfWeek.getDisplayName(TextStyle.SHORT, ru),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier) {
    Column(modifier) {
        AnimatedNumber(value, numberStyle(34))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyBooks(onAdd: () -> Unit) {
    PanelCard {
        Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, tint = Ink, modifier = Modifier.size(40.dp))
        Text("Добавьте книгу, которую читаете", style = MaterialTheme.typography.titleMedium)
        Text(
            "После чтения отмечайте страницу, на которой остановились, — приложение посчитает прочитанные страницы, " +
                "серию дней и сколько осталось до конца книги.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onAdd, colors = ButtonDefaults.buttonColors(containerColor = Ink)) { Text("Добавить книгу") }
    }
}

@Composable
private fun BookCard(progress: BookProgress, onLog: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val book = progress.book
    PanelCard {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(book.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                book.author?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            BookMenu(onEdit, onDelete)
        }
        val fraction by animateFloatAsState(progress.fraction, tween(700), label = "book")
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp),
            color = Ink,
            trackColor = Ink.copy(alpha = 0.15f),
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "стр. ${book.currentPage} из ${book.totalPages} · ${(progress.fraction * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                )
                progress.daysLeft?.let {
                    Text(
                        "≈ $it ${plural(it, "день", "дня", "дней")} до конца в вашем темпе",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Button(onClick = onLog, colors = ButtonDefaults.buttonColors(containerColor = Ink)) { Text("Записать") }
        }
    }
}

@Composable
private fun BookMenu(onEdit: () -> Unit, onDelete: () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Действия") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Изменить") }, onClick = { open = false; onEdit() })
            DropdownMenuItem(text = { Text("Удалить") }, onClick = { open = false; onDelete() })
        }
    }
}

@Composable
private fun FinishedCard(books: List<BookEntity>, onEdit: (Long) -> Unit, onDelete: (Long) -> Unit) {
    val format = SimpleDateFormat("d MMMM yyyy", ru)
    PanelCard {
        Text("Прочитано", style = MaterialTheme.typography.titleLarge)
        books.forEach { book ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = Ink, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(book.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        listOfNotNull(
                            book.author,
                            "${book.totalPages} стр.",
                            book.finishedAt?.let { format.format(Date(it)) },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BookMenu(onEdit = { onEdit(book.id) }, onDelete = { onDelete(book.id) })
            }
        }
    }
}

@Composable
private fun BookDialog(book: BookEntity?, onDismiss: () -> Unit, onSave: (String, String?, Int, Int) -> Unit) {
    var title by rememberSaveable { mutableStateOf(book?.title.orEmpty()) }
    var author by rememberSaveable { mutableStateOf(book?.author.orEmpty()) }
    var pages by rememberSaveable { mutableStateOf(book?.totalPages?.toString().orEmpty()) }
    var current by rememberSaveable { mutableStateOf("") }
    val pageCount = pages.trim().toIntOrNull()?.takeIf { it in 1..MAX_PAGES }
    val currentPage = if (current.isBlank()) 0 else current.trim().toIntOrNull()?.takeIf { it in 0..(pageCount ?: MAX_PAGES) }
    val valid = title.isNotBlank() && pageCount != null && currentPage != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (book == null) "Новая книга" else "Книга") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Название") }, singleLine = true)
                OutlinedTextField(author, { author = it }, label = { Text("Автор (необязательно)") }, singleLine = true)
                OutlinedTextField(
                    pages, { pages = it },
                    label = { Text("Страниц в книге") },
                    isError = pages.isNotBlank() && pageCount == null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                if (book == null) {
                    OutlinedTextField(
                        current, { current = it },
                        label = { Text("Уже на странице (если начали)") },
                        isError = currentPage == null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onSave(title.trim(), author.trim().ifEmpty { null }, pageCount!!, currentPage!!) },
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun LogDialog(book: BookEntity, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var page by rememberSaveable { mutableStateOf(book.currentPage.toString()) }
    val value = page.trim().toIntOrNull()?.takeIf { it in 0..book.totalPages }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("На какой странице остановились?", style = MaterialTheme.typography.bodyLarge)
                OutlinedTextField(
                    page, { page = it },
                    label = { Text("Страница, из ${book.totalPages}") },
                    isError = value == null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(10, 20, 30).forEach { add ->
                        SuggestionChip(
                            onClick = { page = ((value ?: book.currentPage) + add).coerceAtMost(book.totalPages).toString() },
                            label = { Text("+$add") },
                        )
                    }
                }
                val diff = (value ?: book.currentPage) - book.currentPage
                Text(
                    when {
                        value == book.totalPages -> "Книга будет отмечена прочитанной"
                        diff > 0 -> "+$diff ${pagesWord(diff)} к сегодняшнему чтению"
                        diff < 0 -> "Исправление: страница станет меньше, прочитанное за день не изменится"
                        else -> "Сейчас на странице ${book.currentPage}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = value != null, onClick = { onSave(value!!) }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

private fun pagesWord(n: Int) = plural(n, "страница", "страницы", "страниц")

private const val MAX_PAGES = 10_000
private val BAR_HEIGHT = 64.dp
