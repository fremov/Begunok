package ru.begunok.ui.finance

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.max
import ru.begunok.finance.*

internal fun LazyListScope.financeAnalytics(state: FinanceUiState, onSelection: (FinanceSelection) -> Unit, onOpen: (FinanceTransaction) -> Unit) {
    val analysis = state.analysis ?: return
    val filter = state.selection.filter
    val currency = filter.currency
    item(key = "finance_totals") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Итоги · $currency", style = MaterialTheme.typography.titleLarge)
            FinanceTotal("Доходы", analysis.current.incomeMinor, analysis.previous.incomeMinor, currency)
            FinanceTotal("Расходы", analysis.current.expenseMinor, analysis.previous.expenseMinor, currency)
            FinanceTotal("Разница доходов и расходов", analysis.current.netMinor, analysis.previous.netMinor, currency)
            Text("Разница — не баланс счёта: импорт может содержать лишь часть истории.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val days = ChronoUnit.DAYS.between(filter.start, filter.endInclusive) + 1
            Text("Сравнение: ${filter.start.minusDays(days).format(financeDayFormat)} — ${filter.start.minusDays(1).format(financeDayFormat)}. Те же валюта, фильтры и правила.", style = MaterialTheme.typography.bodySmall)
            Text("Средний расход в день: ${FinanceMoney.format(analysis.averageDailyExpenseMinor, currency)}")
            Text("Доля сохранённого дохода: " + (analysis.savingsRatePercent?.let { "${String.format(Locale.forLanguageTag("ru"), "%.1f", it)} %" } ?: "нет доходов для расчёта"))
            Text("Учтено: ${analysis.current.count} · Исключено: ${analysis.excluded.size}", style = MaterialTheme.typography.labelLarge)
            HorizontalDivider()
        }
    }
    if (analysis.included.isEmpty()) item(key = "finance_no_analysis") {
        FinanceEmpty("Нет учитываемых операций", "Выберите другой период, валюту или фильтры. Исключённые записи доступны во вкладке «Операции».")
    }
    item(key = "finance_trend") {
        val days = ChronoUnit.DAYS.between(filter.start, filter.endInclusive) + 1
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Доходы и расходы во времени", style = MaterialTheme.typography.titleLarge)
            FinanceChoiceRow(listOf("День", "Неделя", "Месяц"), granularityLabel(state.selection.granularity)) { label ->
                val value = when (label) { "День" -> FinanceGranularity.Day; "Неделя" -> FinanceGranularity.Week; else -> FinanceGranularity.Month }
                onSelection(state.selection.copy(granularity = value))
            }
            if (days > 366) Text("Для длинного периода дневной график заменяется месячным; недельный доступен до 5 лет. Сузьте период для подробностей.", style = MaterialTheme.typography.bodySmall)
            FinanceTrendChart(analysis.trend, currency, "${filter.start}/${filter.endInclusive}/${state.selection.granularity}")
            Text("Неделя начинается в понедельник. Первый столбец может начинаться до выбранного периода; суммы включают только выбранные даты.", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
        }
    }
    financeBreakdown("Категории расходов", "expense_categories", analysis.expenseCategories, analysis.current.expenseMinor, currency)
    financeBreakdown("Категории доходов", "income_categories", analysis.incomeCategories, analysis.current.incomeMinor, currency)
    financeBreakdown("Расходы по счетам", "expense_accounts", analysis.expenseAccounts, analysis.current.expenseMinor, currency)
    financeBreakdown("Доходы по счетам", "income_accounts", analysis.incomeAccounts, analysis.current.incomeMinor, currency)
    financeBreakdown("Получатели расходов · по описанию", "merchants", analysis.merchants, analysis.current.expenseMinor, currency)
    item(key = "finance_largest_expense_title") { Text("Крупнейшие расходы", style = MaterialTheme.typography.titleLarge) }
    if (analysis.largestExpenses.isEmpty()) item { Text("Нет расходов", style = MaterialTheme.typography.bodyMedium) }
    items(analysis.largestExpenses, key = { "large_expense_${it.id}" }) { row -> FinanceTransactionRow(row, onOpen = { onOpen(row) }) }
    item(key = "finance_largest_income_title") { Text("Крупнейшие доходы", style = MaterialTheme.typography.titleLarge) }
    if (analysis.largestIncome.isEmpty()) item { Text("Нет доходов", style = MaterialTheme.typography.bodyMedium) }
    items(analysis.largestIncome, key = { "large_income_${it.id}" }) { row -> FinanceTransactionRow(row, onOpen = { onOpen(row) }) }
}

@Composable
private fun FinanceTotal(label: String, current: Long, previous: Long, currency: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(FinanceMoney.format(current, currency), style = MaterialTheme.typography.headlineSmall)
        // BigInteger avoids overflow when comparing two otherwise valid Long totals.
        val difference = java.math.BigInteger.valueOf(current).subtract(java.math.BigInteger.valueOf(previous))
        val differenceText = java.math.BigDecimal(difference, 2).toPlainString().replace('.', ',')
        Text("Ранее: ${FinanceMoney.format(previous, currency)} · изменение: ${if (difference.signum() > 0) "+" else ""}$differenceText $currency", style = MaterialTheme.typography.bodySmall)
    }
}

private fun LazyListScope.financeBreakdown(title: String, key: String, rows: List<FinanceBreakdown>, total: Long, currency: String) {
    item(key = "${key}_title") { Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp)) }
    if (rows.isEmpty()) item(key = "${key}_empty") { Text("Нет операций", style = MaterialTheme.typography.bodyMedium) }
    items(rows, key = { "${key}_${it.label}" }) { row ->
        val fraction = if (total > 0) (row.amountMinor.toDouble() / total.toDouble()).coerceIn(0.0, 1.0) else 0.0
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(row.label.ifBlank { "Без названия" }, style = MaterialTheme.typography.titleSmall)
            Text("${FinanceMoney.format(row.amountMinor, currency)} · ${String.format(Locale.forLanguageTag("ru"), "%.1f", fraction * 100)} % · операций: ${row.count}", style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(progress = { fraction.toFloat() }, modifier = Modifier.fillMaxWidth(), gapSize = 0.dp, drawStopIndicator = {})
        }
    }
    item(key = "${key}_divider") { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
}

@Composable
private fun FinanceTrendChart(trend: List<FinanceTrend>, currency: String, periodKey: String) {
    if (trend.isEmpty()) { Text("Нет точек за период"); return }
    val pageSize = 30
    var first by rememberSaveable(periodKey) { mutableStateOf(max(0, trend.size - pageSize)) }
    var selected by rememberSaveable(periodKey) { mutableStateOf(trend.lastIndex) }
    val begin = first.coerceIn(0, max(0, trend.size - 1))
    val end = (begin + pageSize).coerceAtMost(trend.size)
    val visible = trend.subList(begin, end)
    val maxValue = visible.maxOf { maxOf(it.incomeMinor, it.expenseMinor) }.coerceAtLeast(1L)
    val incomeColor = MaterialTheme.colorScheme.primary
    val expenseColor = MaterialTheme.colorScheme.tertiary
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    val point = trend[selected.coerceIn(trend.indices)]
    val accessible = "${point.start.format(financeDayFormat)}: доходы ${FinanceMoney.format(point.incomeMinor, currency)}, расходы ${FinanceMoney.format(point.expenseMinor, currency)}"
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Доходы — основной цвет · Расходы — дополнительный", style = MaterialTheme.typography.labelSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Доходы", color = incomeColor, style = MaterialTheme.typography.labelLarge)
            Text("Расходы", color = expenseColor, style = MaterialTheme.typography.labelLarge)
        }
        Text("Верхняя отметка: ${FinanceMoney.format(maxValue, currency)}", style = MaterialTheme.typography.bodySmall)
        Canvas(Modifier.fillMaxWidth().height(180.dp).semantics { contentDescription = accessible }
            .pointerInput(begin, end) { detectTapGestures { offset ->
                selected = (begin + (offset.x / size.width * visible.size).toInt()).coerceIn(begin, end - 1)
            } }) {
            val slot = size.width / visible.size
            val bar = (slot * 0.33f).coerceAtLeast(1f)
            drawLine(lineColor, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
            visible.forEachIndexed { index, bucket ->
                val incomeHeight = (bucket.incomeMinor.toDouble() / maxValue * (size.height - 4.dp.toPx())).toFloat()
                val expenseHeight = (bucket.expenseMinor.toDouble() / maxValue * (size.height - 4.dp.toPx())).toFloat()
                val x = slot * index + slot * 0.1f
                drawRect(incomeColor, Offset(x, size.height - incomeHeight), Size(bar, incomeHeight))
                drawRect(expenseColor, Offset(x + bar + slot * 0.08f, size.height - expenseHeight), Size(bar, expenseHeight))
                if (begin + index == selected) drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(visible.first().start.format(financeDayFormat), style = MaterialTheme.typography.labelSmall)
            Text(visible.last().start.format(financeDayFormat), style = MaterialTheme.typography.labelSmall)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { first = max(0, begin - pageSize); selected = first }, enabled = begin > 0) { Text("← Раньше") }
            TextButton(onClick = { first = end; selected = end }, enabled = end < trend.size) { Text("Позже →") }
        }
        Text(accessible, style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { selected = (selected - 1).coerceAtLeast(0); if (selected < begin) first = selected }, enabled = selected > 0, modifier = Modifier.weight(1f)) { Text("Предыдущая точка") }
            TextButton(onClick = { selected = (selected + 1).coerceAtMost(trend.lastIndex); if (selected >= end) first = selected }, enabled = selected < trend.lastIndex, modifier = Modifier.weight(1f)) { Text("Следующая точка") }
        }
    }
}

private fun granularityLabel(value: FinanceGranularity): String = when (value) { FinanceGranularity.Day -> "День"; FinanceGranularity.Week -> "Неделя"; FinanceGranularity.Month -> "Месяц" }
