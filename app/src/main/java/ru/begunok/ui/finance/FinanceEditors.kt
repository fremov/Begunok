package ru.begunok.ui.finance

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Currency
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import ru.begunok.finance.*

internal fun manualDraft(row: FinanceTransaction? = null, income: Boolean = false): ArrayList<String> = arrayListOf(
    (row?.id ?: 0).toString(), (row?.amountMinor?.let { it > 0 } ?: income).toString(),
    row?.amountMinor?.let { BigDecimal.valueOf(it, 2).abs().toPlainString() } ?: "",
    (row?.occurredAt ?: LocalDateTime.now().withSecond(0).withNano(0)).format(DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")),
    row?.currency ?: "RUB", row?.account ?: "", row?.category ?: "", row?.description ?: "", row?.note ?: "",
)

internal fun ruleDraft(rule: FinanceRule? = null, row: FinanceTransaction? = null): ArrayList<String> = arrayListOf(
    (rule?.id ?: 0).toString(), rule?.name ?: row?.category?.let { "Не учитывать: $it" } ?: "",
    (rule?.enabled ?: true).toString(), (rule?.action ?: FinanceInclusion.Exclude).name,
    (rule?.direction ?: row?.let { if (it.amountMinor > 0) FinanceDirection.Income else FinanceDirection.Expense } ?: FinanceDirection.Any).name,
    rule?.account ?: row?.account ?: "", rule?.category ?: "", rule?.descriptionContains ?: row?.description ?: "",
    rule?.minAmountMinor?.let { BigDecimal.valueOf(it, 2).toPlainString() } ?: "",
    rule?.maxAmountMinor?.let { BigDecimal.valueOf(it, 2).toPlainString() } ?: "", rule?.currency ?: row?.currency ?: "",
)

internal fun replaceDraft(draft: ArrayList<String>, index: Int, text: String): ArrayList<String> = ArrayList(draft).also { it[index] = text }

private fun validCurrency(text: String): String {
    val code = text.trim().uppercase(java.util.Locale.ROOT)
    require(runCatching { Currency.getInstance(code).defaultFractionDigits == 2 }.getOrDefault(false)) { "Укажите ISO-код валюты с двумя знаками после запятой, например RUB, USD или EUR." }
    return code
}

private fun manualFromDraft(d: List<String>, original: FinanceTransaction?): FinanceTransaction {
    val amount = FinanceMoney.parseMinor(d[2])
    require(amount > 0) { "Сумма должна быть больше нуля. Знак выберите кнопкой «Доход» или «Расход»." }
    val date = LocalDateTime.parse(d[3].trim(), DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(ResolverStyle.STRICT))
    require(date.year in 1900..2100) { "Год должен быть от 1900 до 2100." }
    require(d[5].isNotBlank()) { "Укажите счёт." }
    require(d[6].isNotBlank()) { "Укажите категорию." }
    return (original ?: FinanceTransaction(occurredAt = date, amountMinor = amount, account = "", category = "", description = "")).copy(
        id = d[0].toLong(), occurredAt = date, amountMinor = if (d[1].toBoolean()) amount else Math.negateExact(amount),
        currency = validCurrency(d[4]), account = d[5].trim(), category = d[6].trim(), description = d[7].trim(), note = d[8].trim(),
    )
}

internal fun ruleFromDraft(d: List<String>): FinanceRule {
    val min = d[8].takeIf { it.isNotBlank() }?.let(FinanceMoney::parseMinor)
    val max = d[9].takeIf { it.isNotBlank() }?.let(FinanceMoney::parseMinor)
    require(min == null || min > 0) { "Минимальная сумма должна быть больше нуля." }
    require(max == null || max > 0) { "Максимальная сумма должна быть больше нуля." }
    require(min == null || max == null || min <= max) { "Минимальная сумма больше максимальной." }
    val currency = d[10].takeIf { it.isNotBlank() }?.let(::validCurrency)
    require((min == null && max == null) || currency != null) { "Для ограничения суммы обязательно укажите валюту." }
    require(d[1].isNotBlank()) { "Укажите название правила." }
    val direction = FinanceDirection.valueOf(d[4])
    require(direction != FinanceDirection.Any || listOf(d[5], d[6], d[7], d[8], d[9], d[10]).any { it.isNotBlank() }) { "Добавьте хотя бы одно условие, чтобы не изменить все операции." }
    return FinanceRule(
        id = d[0].toLong(), name = d[1].trim(), enabled = d[2].toBoolean(), action = FinanceInclusion.valueOf(d[3]), direction = direction,
        account = d[5].trim().takeIf { it.isNotEmpty() }, category = d[6].trim().takeIf { it.isNotEmpty() },
        descriptionContains = d[7].trim(), minAmountMinor = min, maxAmountMinor = max, currency = currency,
    )
}

@Composable
internal fun FinanceEditorFrame(title: String, busy: Boolean, error: String?, onDismiss: () -> Unit, onSave: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onDismiss, enabled = !busy) { Text("Закрыть") }
                    Button(onClick = onSave, enabled = !busy) { Text(if (busy) "Сохранение…" else "Сохранить") }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    content()
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
internal fun ManualEditor(draft: ArrayList<String>, original: FinanceTransaction?, action: FinanceActionState, accounts: List<String>, categories: List<String>, onChange: (ArrayList<String>) -> Unit, onDismiss: () -> Unit, onSave: (FinanceTransaction) -> Unit) {
    var validation by rememberSaveable { mutableStateOf<String?>(null) }
    fun update(index: Int, value: String) { validation = null; onChange(replaceDraft(draft, index, value)) }
    FinanceEditorFrame(if (draft[0] == "0") "Новая операция" else "Изменить операцию", action.busy, validation ?: action.error, onDismiss, {
        runCatching { manualFromDraft(draft, original) }.onSuccess(onSave).onFailure { validation = "Проверьте поля: ${it.message ?: "неверный формат даты или суммы"}" }
    }) {
        FinanceChoiceRow(listOf("Доход", "Расход"), if (draft[1].toBoolean()) "Доход" else "Расход", enabled = !action.busy) { update(1, (it == "Доход").toString()) }
        FinanceField("Сумма без знака", draft[2], !action.busy, KeyboardType.Decimal) { update(2, it) }
        FinanceField("Дата и время · ГГГГ-ММ-ДД ЧЧ:ММ", draft[3], !action.busy) { update(3, it) }
        FinanceField("Валюта · ISO-код", draft[4], !action.busy) { update(4, it) }
        FinanceSuggestedField("Счёт", draft[5], accounts, !action.busy) { update(5, it) }
        FinanceSuggestedField("Категория", draft[6], categories, !action.busy) { update(6, it) }
        FinanceField("Описание", draft[7], !action.busy, singleLine = false) { update(7, it) }
        FinanceField("Заметка", draft[8], !action.busy, singleLine = false) { update(8, it) }
        Text("Черновик сохраняется при закрытии и восстановлении экрана. Ошибка сохранения не удаляет введённые поля.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun RuleEditor(draft: ArrayList<String>, rows: List<FinanceTransaction>, action: FinanceActionState, accounts: List<String>, categories: List<String>, onChange: (ArrayList<String>) -> Unit, onDismiss: () -> Unit, onSave: (FinanceRule) -> Unit) {
    var validation by rememberSaveable { mutableStateOf<String?>(null) }
    val matching by produceState<Int?>(null, draft, rows) {
        value = null
        delay(150)
        value = withContext(Dispatchers.Default) {
            runCatching {
                val rule = ruleFromDraft(draft).copy(id = Long.MAX_VALUE, enabled = true)
                rows.count { FinanceRules.evaluate(it.copy(inclusion = FinanceInclusion.Auto), listOf(rule)).ruleId == rule.id }
            }.getOrNull()
        }
    }
    fun update(index: Int, value: String) { validation = null; onChange(replaceDraft(draft, index, value)) }
    FinanceEditorFrame(if (draft[0] == "0") "Новое правило" else "Изменить правило", action.busy, validation ?: action.error, onDismiss, {
        runCatching { ruleFromDraft(draft) }.onSuccess(onSave).onFailure { validation = it.message ?: "Проверьте условия правила." }
    }) {
        FinanceField("Название", draft[1], !action.busy) { update(1, it) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Правило включено")
            Switch(checked = draft[2].toBoolean(), onCheckedChange = { update(2, it.toString()) }, enabled = !action.busy, modifier = Modifier.semantics { contentDescription = "Правило включено" })
        }
        Text("Действие", style = MaterialTheme.typography.titleSmall)
        FinanceChoiceRow(listOf("Не учитывать", "Учитывать"), if (draft[3] == FinanceInclusion.Exclude.name) "Не учитывать" else "Учитывать", enabled = !action.busy) { update(3, if (it == "Учитывать") FinanceInclusion.Include.name else FinanceInclusion.Exclude.name) }
        Text("Все заполненные условия соединяются «И». Пустое поле не ограничивает выборку. Более новые правила важнее старых; ручное решение по операции важнее любого правила.", style = MaterialTheme.typography.bodyMedium)
        FinanceChoiceRow(listOf("Любое направление", "Доход", "Расход"), directionLabel(FinanceDirection.valueOf(draft[4])), enabled = !action.busy) { update(4, labelDirection(it).name) }
        FinanceSuggestedField("Счёт · точное совпадение", draft[5], accounts, !action.busy) { update(5, it) }
        FinanceSuggestedField("Категория · точное совпадение", draft[6], categories, !action.busy) { update(6, it) }
        FinanceField("Описание содержит · без учёта регистра", draft[7], !action.busy, singleLine = false) { update(7, it) }
        FinanceField("Сумма от · без знака, включительно", draft[8], !action.busy, KeyboardType.Decimal) { update(8, it) }
        FinanceField("Сумма до · без знака, включительно", draft[9], !action.busy, KeyboardType.Decimal) { update(9, it) }
        FinanceField("Валюта · ISO-код, необязательно без суммы", draft[10], !action.busy) { update(10, it) }
        Text(matching?.let { "Под условия подходит операций: $it" } ?: "Заполните корректные условия для предпросмотра", style = MaterialTheme.typography.titleMedium)
        Text("Предпросмотр по всей истории, без ручных переопределений и конкурирующих правил. Операции с ошибкой банка всегда исключены.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun FinanceField(label: String, value: String, enabled: Boolean = true, keyboard: KeyboardType = KeyboardType.Text, singleLine: Boolean = true, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, enabled = enabled, singleLine = singleLine,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard), modifier = Modifier.fillMaxWidth())
}

@Composable
internal fun FinanceSuggestedField(label: String, value: String, suggestions: List<String>, enabled: Boolean = true, onChange: (String) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    FinanceField(label, value, enabled, onChange = onChange)
    if (suggestions.isNotEmpty()) {
        Box {
            TextButton(onClick = { expanded = true }, enabled = enabled) { Text("Выбрать из истории") }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 320.dp)) {
                suggestions.forEach { suggestion -> DropdownMenuItem(text = { Text(suggestion) }, onClick = { expanded = false; onChange(suggestion) }) }
            }
        }
    }
}

internal fun directionLabel(value: FinanceDirection): String = when (value) { FinanceDirection.Any -> "Любое направление"; FinanceDirection.Income -> "Доход"; FinanceDirection.Expense -> "Расход" }
internal fun labelDirection(value: String): FinanceDirection = when (value) { "Доход" -> FinanceDirection.Income; "Расход" -> FinanceDirection.Expense; else -> FinanceDirection.Any }
