package ru.begunok.ui.finance

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

@Composable
internal fun FinanceFilters(state: FinanceUiState, onSelect: (FinanceSelection) -> Unit) {
    val selected = state.selection
    val filter = selected.filter
    var period by rememberSaveable { mutableStateOf("Месяц") }
    var custom by rememberSaveable { mutableStateOf(false) }
    var start by rememberSaveable(filter.start.toString()) { mutableStateOf(filter.start.toString()) }
    var end by rememberSaveable(filter.endInclusive.toString()) { mutableStateOf(filter.endInclusive.toString()) }
    var dateError by rememberSaveable { mutableStateOf<String?>(null) }
    var search by rememberSaveable { mutableStateOf(filter.search) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val activeCount = listOf(filter.account != null, filter.category != null, search.isNotEmpty()).count { it }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FinanceChoiceRow(listOf("Месяц", "30 дней", "Год", "Всё", "Период"), period) { value ->
            period = value
            custom = value == "Период"
            dateError = null
            val today = LocalDate.now()
            val dates = when (value) {
                "Месяц" -> today.withDayOfMonth(1) to today
                "30 дней" -> today.minusDays(29) to today
                "Год" -> today.withDayOfYear(1) to today
                else -> null
            }
            if (value == "Всё") onSelect(selected.copy(allDates = true))
            else if (dates != null) onSelect(selected.copy(allDates = false, filter = filter.copy(start = dates.first, endInclusive = dates.second)))
        }
        Text("${filter.start.format(financeDayFormat)} — ${filter.endInclusive.format(financeDayFormat)}", style = MaterialTheme.typography.labelLarge)
        if (custom) {
            FinanceField("Начало · ГГГГ-ММ-ДД", start) { start = it; dateError = null }
            FinanceField("Конец включительно · ГГГГ-ММ-ДД", end) { end = it; dateError = null }
            dateError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedButton(onClick = {
                runCatching {
                    val parser = DateTimeFormatter.ISO_LOCAL_DATE.withResolverStyle(ResolverStyle.STRICT)
                    val from = LocalDate.parse(start.trim(), parser)
                    val to = LocalDate.parse(end.trim(), parser)
                    require(from.year in 1900..2100 && to.year in 1900..2100) { "Годы должны быть от 1900 до 2100." }
                    require(from <= to) { "Начало периода должно быть не позже конца." }
                    onSelect(selected.copy(allDates = false, filter = filter.copy(start = from, endInclusive = to)))
                }.onFailure { dateError = it.message ?: "Введите даты в формате ГГГГ-ММ-ДД." }
            }) { Text("Применить период") }
        }
        FinanceSelect("Валюта", filter.currency, state.currencies, allowAll = false) { code -> code?.let { onSelect(selected.copy(filter = filter.copy(currency = it))) } }
        TextButton(onClick = { expanded = !expanded }) { Text("${if (expanded) "Скрыть фильтры" else "Фильтры"}${if (activeCount > 0) " · активно: $activeCount" else ""}") }
        if (expanded) {
            FinanceSelect("Счёт", filter.account, state.accounts) { onSelect(selected.copy(filter = filter.copy(account = it))) }
            FinanceSelect("Категория", filter.category, state.categories) { onSelect(selected.copy(filter = filter.copy(category = it))) }
            FinanceField("Поиск по описанию, заметке, категории, счёту", search) { text ->
                search = text
                onSelect(selected.copy(filter = filter.copy(search = text)))
            }
        }
        if (filter.account != null || filter.category != null || search.isNotEmpty()) TextButton(onClick = {
            search = ""
            onSelect(selected.copy(filter = filter.copy(account = null, category = null, search = "")))
        }) { Text("Сбросить счёт, категорию и поиск") }
        HorizontalDivider()
    }
}

@Composable
private fun FinanceSelect(label: String, selected: String?, values: List<String>, allowAll: Boolean = true, onSelect: (String?) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text("$label: ${selected ?: "Все"}", modifier = Modifier.fillMaxWidth())
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 360.dp)) {
            if (allowAll) DropdownMenuItem(text = { Text("Все") }, onClick = { expanded = false; onSelect(null) })
            values.forEach { value -> DropdownMenuItem(text = { Text(value) }, onClick = { expanded = false; onSelect(value) }) }
        }
    }
}
