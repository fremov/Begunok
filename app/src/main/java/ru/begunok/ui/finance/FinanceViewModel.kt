package ru.begunok.ui.finance

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.begunok.data.FinanceImportReport
import ru.begunok.data.FinanceRepository
import ru.begunok.finance.*

internal data class FinanceSelection(
    val filter: FinanceFilter = FinanceFilter(LocalDate.now().withDayOfMonth(1), LocalDate.now()),
    val allDates: Boolean = false,
    val granularity: FinanceGranularity = FinanceGranularity.Day,
    val showExcluded: Boolean = false,
    val direction: FinanceDirection = FinanceDirection.Any,
)

internal data class FinanceUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val transactions: List<FinanceTransaction> = emptyList(),
    val rules: List<FinanceRule> = emptyList(),
    val decisions: Map<Long, FinanceDecision> = emptyMap(),
    val selection: FinanceSelection = FinanceSelection(),
    val analysis: FinanceAnalysis? = null,
    val rows: List<FinanceTransaction> = emptyList(),
    val accounts: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    val currencies: List<String> = listOf("RUB"),
)

internal data class FinanceImportSummary(
    val preview: FinanceImportPreview,
    val currencies: List<String>,
    val totals: Map<String, FinanceTotals>,
    val excluded: Int,
    val start: LocalDate?,
    val end: LocalDate?,
)

internal data class FinanceActionState(
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val preview: FinanceImportSummary? = null,
    val report: FinanceImportReport? = null,
    val importedFailedCount: Int = 0,
)

class FinanceViewModel(private val repository: FinanceRepository, context: Context) : ViewModel() {
    private val resolver = context.applicationContext.contentResolver
    private val selection = MutableStateFlow(FinanceSelection())
    private val mutableState = MutableStateFlow(FinanceUiState())
    internal val state: StateFlow<FinanceUiState> = mutableState
    private val mutableAction = MutableStateFlow(FinanceActionState())
    internal val action: StateFlow<FinanceActionState> = mutableAction
    private var observation: Job? = null
    private var retryAction: (() -> Unit)? = null

    init { reload() }

    internal fun reload() {
        observation?.cancel()
        observation = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loading = true, error = null)
            try {
                combine(repository.observeTransactions(), repository.observeRules(), selection) { rows, rules, selected -> Triple(rows, rules, selected) }
                    .collectLatest { (rows, rules, requested) ->
                        try {
                            val next = withContext(Dispatchers.Default) {
                                val dates = rows.map { it.occurredAt.toLocalDate() }
                                val filter = if (requested.allDates && dates.isNotEmpty()) requested.filter.copy(
                                    start = dates.min().coerceAtLeast(LocalDate.of(1900, 1, 1)),
                                    endInclusive = dates.max().coerceAtMost(LocalDate.of(2100, 12, 31)),
                                ) else requested.filter
                                val days = ChronoUnit.DAYS.between(filter.start, filter.endInclusive) + 1
                                val granularity = when {
                                    requested.granularity == FinanceGranularity.Day && days > 366 -> FinanceGranularity.Month
                                    requested.granularity == FinanceGranularity.Week && days > 1830 -> FinanceGranularity.Month
                                    else -> requested.granularity
                                }
                                val effective = requested.copy(filter = filter, granularity = granularity)
                                val analysis = FinanceAnalytics.build(rows, rules, filter, granularity)
                                val decisions = rows.associate { it.id to FinanceRules.evaluate(it, rules) }
                                val shown = (if (requested.showExcluded) analysis.included + analysis.excluded else analysis.included)
                                    .filter { when (requested.direction) {
                                        FinanceDirection.Any -> true
                                        FinanceDirection.Income -> it.amountMinor > 0
                                        FinanceDirection.Expense -> it.amountMinor < 0
                                    } }
                                    .sortedWith(compareByDescending<FinanceTransaction> { it.occurredAt }.thenByDescending { it.id })
                                FinanceUiState(
                                    loading = false, transactions = rows, rules = rules, selection = effective,
                                    analysis = analysis, rows = shown, decisions = decisions,
                                    accounts = rows.map { it.account }.distinct().sorted(),
                                    categories = rows.map { it.category }.distinct().sorted(),
                                    currencies = (rows.map { it.currency } + filter.currency).distinct().sorted(),
                                )
                            }
                            mutableState.value = next
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { mutableState.value = mutableState.value.copy(loading = false, error = errorText(e)) }
                    }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutableState.value = mutableState.value.copy(loading = false, error = errorText(e)) }
        }
    }

    internal fun select(value: FinanceSelection) { selection.value = value }
    internal fun dismissNotice() {
        if (!mutableAction.value.busy) {
            retryAction = null
            mutableAction.value = mutableAction.value.copy(error = null, message = null, report = null)
        }
    }
    internal fun retry() { retryAction?.invoke() }
    internal fun closePreview() { if (!mutableAction.value.busy) mutableAction.value = mutableAction.value.copy(preview = null, error = null) }

    private fun perform(success: String? = null, onSuccess: () -> Unit = {}, operation: suspend () -> Unit) {
        if (mutableAction.value.busy) return
        retryAction = { perform(success, onSuccess, operation) }
        mutableAction.value = mutableAction.value.copy(busy = true, error = null, message = null, report = null)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { operation() }
                retryAction = null
                mutableAction.value = mutableAction.value.copy(busy = false, error = null, message = success)
                onSuccess()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutableAction.value = mutableAction.value.copy(busy = false, error = errorText(e)) }
        }
    }

    internal fun readCsv(uri: Uri) = perform {
        val fileName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Операции.csv"
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                require(output.size().toLong() + read <= 20L * 1024 * 1024) { "Файл больше 20 МиБ. Разделите выгрузку на несколько файлов." }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        } ?: error("Не удалось открыть файл. Выберите его ещё раз.")
        val preview = FinanceCsvParser.parse(bytes, fileName)
        val summary = withContext(Dispatchers.Default) {
            val currencies = preview.transactions.map { it.currency }.distinct().sorted()
            val totals = currencies.associateWith { currency ->
                var income = 0L
                var expense = 0L
                var count = 0
                preview.transactions.forEach { row ->
                    if (row.currency == currency && FinanceRules.evaluate(row, state.value.rules).included) {
                        if (row.amountMinor > 0) income = Math.addExact(income, row.amountMinor)
                        else expense = Math.subtractExact(expense, row.amountMinor)
                        count++
                    }
                }
                FinanceTotals(income, expense, Math.subtractExact(income, expense), count)
            }
            FinanceImportSummary(preview, currencies, totals,
                preview.transactions.count { !FinanceRules.evaluate(it, state.value.rules).included },
                preview.transactions.minOfOrNull { it.occurredAt.toLocalDate() },
                preview.transactions.maxOfOrNull { it.occurredAt.toLocalDate() })
        }
        mutableAction.value = mutableAction.value.copy(preview = summary)
    }

    internal fun confirmImport() {
        val preview = action.value.preview?.preview ?: return
        perform {
            val report = repository.importCsv(preview)
            mutableAction.value = mutableAction.value.copy(preview = null, report = report, importedFailedCount = preview.failedCount)
        }
    }
    internal fun saveManual(row: FinanceTransaction, onSuccess: () -> Unit) = perform("Операция сохранена", onSuccess) { repository.saveManual(row) }
    internal fun deleteManual(id: Long, onSuccess: () -> Unit) = perform("Операция удалена", onSuccess) { repository.deleteManual(id) }
    internal fun inclusion(id: Long, value: FinanceInclusion) = perform("Учёт операции обновлён") { repository.setInclusion(id, value) }
    internal fun category(id: Long, value: String, onSuccess: () -> Unit) = perform("Категория сохранена", onSuccess) { repository.setCategory(id, value) }
    internal fun saveRule(rule: FinanceRule, onSuccess: () -> Unit = {}) = perform("Правило сохранено", onSuccess) { repository.saveRule(rule) }
    internal fun deleteRule(id: Long, onSuccess: () -> Unit) = perform("Правило удалено", onSuccess) { repository.deleteRule(id) }
}

private fun errorText(error: Exception): String = error.message?.takeIf { it.isNotBlank() } ?: "Не удалось выполнить действие. Повторите попытку."
