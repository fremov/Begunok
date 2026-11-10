package ru.begunok.ui.finance

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.begunok.finance.*
import java.time.format.DateTimeFormatter

internal val financeDayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.uuuu")
internal val financeTimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm")

@Composable
fun FinanceScreen(viewModel: FinanceViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val action by viewModel.action.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf("Аналитика") }
    var manual by rememberSaveable { mutableStateOf<ArrayList<String>?>(null) }
    var manualOpen by rememberSaveable { mutableStateOf(false) }
    var rule by rememberSaveable { mutableStateOf<ArrayList<String>?>(null) }
    var ruleOpen by rememberSaveable { mutableStateOf(false) }
    var detailId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deleteManualId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deleteRuleId by rememberSaveable { mutableStateOf<Long?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::readCsv) }
    fun openManual(income: Boolean) {
        if (manual == null) manual = manualDraft(income = income)
        manualOpen = true
        viewModel.dismissNotice()
    }
    fun openRule(row: FinanceTransaction? = null) {
        if (rule == null) rule = ruleDraft(row = row)
        ruleOpen = true
        viewModel.dismissNotice()
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Финансы", style = MaterialTheme.typography.headlineMedium)
            Text("Личная история · только на устройстве", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.dismissNotice(); picker.launch(arrayOf("text/*", "application/octet-stream", "*/*")) }, enabled = !action.busy && !state.loading) { Text("Импорт CSV") }
                OutlinedButton(onClick = { openManual(true) }, enabled = !action.busy) { Text("Доход") }
                OutlinedButton(onClick = { openManual(false) }, enabled = !action.busy) { Text("Расход") }
            }
            if (manual != null && !manualOpen) {
                TextButton(onClick = { manualOpen = true }, enabled = !action.busy) { Text("Продолжить черновик операции") }
                TextButton(onClick = { manual = null }, enabled = !action.busy) { Text("Удалить черновик операции") }
            }
        }
        item { FinanceChoiceRow(listOf("Аналитика", "Операции", "Правила"), tab) { tab = it } }
        if (action.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Выполняем действие…", style = MaterialTheme.typography.bodySmall) }
        if (action.error != null || action.message != null || action.report != null) item {
            FinanceNotice(action, viewModel::retry, viewModel::dismissNotice)
        }
        if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Загрузка истории…") }
        state.error?.let { error -> item {
            Text(error, color = MaterialTheme.colorScheme.error)
            Button(onClick = viewModel::reload) { Text("Повторить загрузку") }
        } }
        if (tab != "Правила") item { FinanceFilters(state, viewModel::select) }
        when (tab) {
            "Аналитика" -> financeAnalytics(state, onSelection = viewModel::select, onOpen = { detailId = it.id })
            "Операции" -> {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Показывать исключённые", modifier = Modifier.weight(1f).padding(top = 12.dp))
                        Switch(checked = state.selection.showExcluded, onCheckedChange = { viewModel.select(state.selection.copy(showExcluded = it)) }, modifier = Modifier.semantics { contentDescription = "Показывать исключённые операции" })
                    }
                    FinanceChoiceRow(listOf("Любое направление", "Доход", "Расход"), directionLabel(state.selection.direction)) { viewModel.select(state.selection.copy(direction = labelDirection(it))) }
                    Text("Операций: ${state.rows.size}", style = MaterialTheme.typography.labelLarge)
                }
                if (!state.loading && state.rows.isEmpty()) item { FinanceEmpty("Нет операций по выбранным условиям", "Измените период и фильтры, включите исключённые операции или импортируйте CSV.") }
                items(state.rows, key = { "operation_${it.id}" }) { row -> FinanceTransactionRow(row, state.decisions[row.id]?.included == false, { detailId = row.id }) }
            }
            "Правила" -> {
                item {
                    Text("Правила учёта", style = MaterialTheme.typography.titleLarge)
                    Text("Сначала применяются новые правила. Все условия внутри правила соединены «И». Ручное решение по операции важнее правила, ошибка банка всегда исключает операцию.", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { openRule() }, enabled = !action.busy) { Text("Создать правило") }
                    if (rule != null && !ruleOpen) {
                        TextButton(onClick = { ruleOpen = true }, enabled = !action.busy) { Text("Продолжить черновик правила") }
                        TextButton(onClick = { rule = null }, enabled = !action.busy) { Text("Удалить черновик правила") }
                    }
                }
                if (state.rules.isEmpty() && !state.loading) item { FinanceEmpty("Правил пока нет", "Например, исключите переводы по описанию и счёту. История останется на месте, аналитика пересчитается.") }
                items(state.rules, key = { "rule_${it.id}" }) { item ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f)) {
                                Text(item.name, style = MaterialTheme.typography.titleMedium)
                                Text(if (item.action == FinanceInclusion.Include) "Учитывать" else "Не учитывать", style = MaterialTheme.typography.labelLarge)
                            }
                            Switch(checked = item.enabled, onCheckedChange = { viewModel.saveRule(item.copy(enabled = it)) }, enabled = !action.busy, modifier = Modifier.semantics { contentDescription = "Правило «${item.name}» включено" })
                        }
                        Text(ruleDescription(item), style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick = { rule = ruleDraft(item); ruleOpen = true; viewModel.dismissNotice() }, enabled = !action.busy) { Text("Изменить") }
                            TextButton(onClick = { deleteRuleId = item.id; viewModel.dismissNotice() }, enabled = !action.busy) { Text("Удалить") }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (manualOpen && manual != null) {
        val original = state.transactions.find { it.id == manual!![0].toLongOrNull() }
        ManualEditor(manual!!, original, action.copy(busy = action.busy || state.loading), state.accounts, state.categories, { manual = it }, { manualOpen = false }, { row ->
            viewModel.saveManual(row) { manual = null; manualOpen = false }
        })
    }
    if (ruleOpen && rule != null) RuleEditor(rule!!, state.transactions, action, state.accounts, state.categories, { rule = it }, { ruleOpen = false }, { value ->
        viewModel.saveRule(value) { rule = null; ruleOpen = false }
    })
    detailId?.let { id -> state.transactions.find { it.id == id }?.let { row ->
        FinanceDetails(row, state.decisions[id], action, state.categories, onDismiss = { detailId = null },
            onInclusion = { viewModel.inclusion(id, it) }, onCategory = { category, saved -> viewModel.category(id, category, saved) },
            onEdit = { manual = manualDraft(row); manualOpen = true; detailId = null; viewModel.dismissNotice() },
            onDelete = { deleteManualId = id; viewModel.dismissNotice() },
            onRule = { rule = ruleDraft(row = row); detailId = null; ruleOpen = true; viewModel.dismissNotice() })
    } }
    deleteManualId?.let { id -> FinanceDeleteDialog("Удалить ручную операцию?", "Операция будет удалена из истории и аналитики.", action,
        { deleteManualId = null }, { viewModel.deleteManual(id) { deleteManualId = null; detailId = null } }) }
    deleteRuleId?.let { id -> FinanceDeleteDialog("Удалить правило?", "История сохранится. Учёт операций пересчитается по оставшимся правилам и настройкам банка.", action,
        { deleteRuleId = null }, { viewModel.deleteRule(id) { deleteRuleId = null } }) }
    action.preview?.let { summary -> FinanceImportDialog(summary, action, viewModel::closePreview, viewModel::confirmImport) }
}

@Composable
internal fun FinanceChoiceRow(options: List<String>, selected: String, enabled: Boolean = true, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { label -> FilterChip(selected = label == selected, onClick = { onSelect(label) }, enabled = enabled, label = { Text(label, maxLines = 1) }) }
    }
}

@Composable
internal fun FinanceTransactionRow(row: FinanceTransaction, excluded: Boolean = false, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(row.category, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(FinanceMoney.format(row.amountMinor, row.currency), style = MaterialTheme.typography.titleSmall,
                color = if (excluded) MaterialTheme.colorScheme.onSurfaceVariant else if (row.amountMinor > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        }
        Text(row.description.ifBlank { "Без описания" }, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
        Text("${row.occurredAt.format(financeTimeFormat)} · ${row.account}" + if (excluded) " · исключено" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(Modifier.padding(top = 8.dp))
    }
}

@Composable
internal fun FinanceEmpty(title: String, description: String) {
    Column(Modifier.padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FinanceNotice(action: FinanceActionState, onRetry: () -> Unit, onDismiss: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        action.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        action.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        action.report?.let { Text("${it.fileName}\nДобавлено: ${it.added} · Уже в истории: ${it.duplicates} · Всего в файле: ${it.total}\nБанковских операций с ошибкой в файле: ${action.importedFailedCount}. Они сохранены, но не учитываются в аналитике.") }
        Row {
            if (action.error != null) TextButton(onClick = onRetry, enabled = !action.busy) { Text("Повторить") }
            TextButton(onClick = onDismiss, enabled = !action.busy) { Text("Скрыть") }
        }
    }
}

@Composable
internal fun FinanceReadDialog(title: String, busy: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                TextButton(onClick = onDismiss, enabled = !busy) { Text("Закрыть") }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall)
                    content()
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun FinanceImportDialog(summary: FinanceImportSummary, action: FinanceActionState, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    FinanceReadDialog("Проверка CSV", action.busy, onDismiss) {
        Text(summary.preview.fileName, style = MaterialTheme.typography.titleMedium)
        Text("Операций: ${summary.preview.transactions.size}\nСо статусом «Ок»: ${summary.preview.transactions.size - summary.preview.failedCount}\nСо статусом ошибки: ${summary.preview.failedCount}\nИсключено по текущим правилам и флагам банка: ${summary.excluded}")
        if (summary.start != null && summary.end != null) Text("${summary.start.format(financeDayFormat)} — ${summary.end.format(financeDayFormat)}")
        summary.currencies.forEach { currency -> summary.totals[currency]?.let { totals ->
            Text(currency, style = MaterialTheme.typography.titleMedium)
            Text("Учитываемые доходы: ${FinanceMoney.format(totals.incomeMinor, currency)}\nУчитываемые расходы: ${FinanceMoney.format(totals.expenseMinor, currency)}")
        } }
        Text("Повторные операции не создаются заново. Их статус и флаг банка обновятся, категории и ручные решения сохранятся. Итоги предпросмотра включают повторы; результат покажет количество добавленных записей.")
        action.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = onConfirm, enabled = !action.busy && summary.preview.transactions.isNotEmpty()) { Text(if (action.busy) "Импортируем…" else "Подтвердить импорт") }
    }
}

@Composable
private fun FinanceDetails(row: FinanceTransaction, decision: FinanceDecision?, action: FinanceActionState, categories: List<String>, onDismiss: () -> Unit, onInclusion: (FinanceInclusion) -> Unit, onCategory: (String, () -> Unit) -> Unit, onEdit: () -> Unit, onDelete: () -> Unit, onRule: () -> Unit) {
    var category by rememberSaveable(row.id) { mutableStateOf(row.category) }
    var categorySaved by rememberSaveable(row.id) { mutableStateOf(false) }
    FinanceReadDialog("Операция", action.busy, onDismiss) {
        Text(FinanceMoney.format(row.amountMinor, row.currency), style = MaterialTheme.typography.headlineMedium)
        Text(row.description.ifBlank { "Без описания" }, style = MaterialTheme.typography.titleMedium)
        Text("Дата: ${row.occurredAt.format(financeTimeFormat)}\nСчёт: ${row.account}\nКарта: ${row.card.ifBlank { "не указана" }}\nКатегория: ${row.category}\nИсточник: ${if (row.source == FinanceSource.Manual) "ручная запись" else "CSV банка"}\nСтатус банка: ${row.status}\nФлаг банка: ${if (row.bankIncluded) "учитывать" else "не учитывать"}")
        if (row.note.isNotBlank()) Text("Заметка: ${row.note}")
        Text(if (decision?.included == true) "Учитывается в аналитике" else "Не учитывается в аналитике", style = MaterialTheme.typography.titleMedium)
        Text(decision?.reason ?: "Решение пересчитывается…")
        if (!row.status.equals("Ок", ignoreCase = true)) Text("Операция не завершена банком. Принудительно включить её нельзя.", color = MaterialTheme.colorScheme.error)
        FinanceChoiceRow(listOf("Авто", "Учитывать", "Исключить"), when (row.inclusion) { FinanceInclusion.Auto -> "Авто"; FinanceInclusion.Include -> "Учитывать"; FinanceInclusion.Exclude -> "Исключить" }, enabled = !action.busy && row.status.equals("Ок", true)) {
            onInclusion(when (it) { "Учитывать" -> FinanceInclusion.Include; "Исключить" -> FinanceInclusion.Exclude; else -> FinanceInclusion.Auto })
        }
        Text("«Авто» использует правила и флаг банка. Ручной выбор выше любого правила.", style = MaterialTheme.typography.bodySmall)
        FinanceSuggestedField("Изменить категорию", category, categories, !action.busy) { category = it; categorySaved = false }
        Button(onClick = { onCategory(category.trim()) { categorySaved = true } }, enabled = !action.busy && category.isNotBlank() && category.trim() != row.category) { Text("Сохранить категорию") }
        if (categorySaved) Text("Категория сохранена")
        OutlinedButton(onClick = onRule, enabled = !action.busy) { Text("Создать правило из операции") }
        if (row.source == FinanceSource.Manual) {
            OutlinedButton(onClick = onEdit, enabled = !action.busy) { Text("Изменить операцию") }
            TextButton(onClick = onDelete, enabled = !action.busy) { Text("Удалить операцию") }
        }
        action.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun FinanceDeleteDialog(title: String, text: String, action: FinanceActionState, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = { if (!action.busy) onDismiss() }, title = { Text(title) },
        text = { Column { Text(text); action.error?.let { Text(it, color = MaterialTheme.colorScheme.error) } } },
        confirmButton = { TextButton(onClick = onConfirm, enabled = !action.busy) { Text(if (action.busy) "Удаление…" else "Удалить") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !action.busy) { Text("Отмена") } })
}

private fun ruleDescription(rule: FinanceRule): String = buildList {
    if (rule.direction != FinanceDirection.Any) add(directionLabel(rule.direction))
    rule.account?.let { add("Счёт: $it") }; rule.category?.let { add("Категория: $it") }
    if (rule.descriptionContains.isNotBlank()) add("Описание содержит: ${rule.descriptionContains}")
    rule.minAmountMinor?.let { add("От ${FinanceMoney.format(it, rule.currency ?: "RUB")}") }
    rule.maxAmountMinor?.let { add("До ${FinanceMoney.format(it, rule.currency ?: "RUB")}") }
    rule.currency?.let { add("Валюта: $it") }
}.joinToString(" · ")
