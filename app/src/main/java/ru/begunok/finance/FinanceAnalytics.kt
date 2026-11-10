package ru.begunok.finance

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

data class FinanceFilter(
    val start: LocalDate,
    val endInclusive: LocalDate,
    val currency: String = "RUB",
    val account: String? = null,
    val category: String? = null,
    val search: String = "",
)

data class FinanceTotals(
    val incomeMinor: Long,
    val expenseMinor: Long,
    val netMinor: Long,
    val count: Int,
)

data class FinanceBreakdown(val label: String, val amountMinor: Long, val count: Int)
data class FinanceTrend(val start: LocalDate, val incomeMinor: Long, val expenseMinor: Long)
enum class FinanceGranularity { Day, Week, Month }

data class FinanceAnalysis(
    val current: FinanceTotals,
    val previous: FinanceTotals,
    val expenseCategories: List<FinanceBreakdown>,
    val incomeCategories: List<FinanceBreakdown>,
    val expenseAccounts: List<FinanceBreakdown>,
    val incomeAccounts: List<FinanceBreakdown>,
    val merchants: List<FinanceBreakdown>,
    val trend: List<FinanceTrend>,
    val largestExpenses: List<FinanceTransaction>,
    val largestIncome: List<FinanceTransaction>,
    val included: List<FinanceTransaction>,
    val excluded: List<FinanceTransaction>,
    val averageDailyExpenseMinor: Long,
    val savingsRatePercent: Double?,
)

object FinanceAnalytics {
    // Covers even daily charts throughout the UI's supported 1900..2100 range.
    private const val MAX_TREND_BUCKETS = 100_000L
    private val newestFirst = compareByDescending<FinanceTransaction> { it.occurredAt }
        .thenByDescending { it.id }

    fun matches(transaction: FinanceTransaction, filter: FinanceFilter): Boolean {
        requireValidPeriod(filter)
        return matchesFilter(transaction, filter)
    }

    fun build(
        transactions: List<FinanceTransaction>,
        rules: List<FinanceRule>,
        filter: FinanceFilter,
        granularity: FinanceGranularity = FinanceGranularity.Month,
    ): FinanceAnalysis {
        requireValidPeriod(filter)
        val buckets = emptyBuckets(filter, granularity)
        val days = Math.addExact(ChronoUnit.DAYS.between(filter.start, filter.endInclusive), 1L)
        val previousFilter = filter.copy(
            start = filter.start.minusDays(days),
            endInclusive = filter.start.minusDays(1),
        )
        val included = mutableListOf<FinanceTransaction>()
        val excluded = mutableListOf<FinanceTransaction>()
        val current = TotalsAccumulator()
        val previous = TotalsAccumulator()

        for (transaction in transactions) {
            if (matchesFilter(transaction, filter)) {
                if (FinanceRules.evaluate(transaction, rules).included) {
                    included.add(transaction)
                    current.add(transaction.amountMinor)
                    buckets.getValue(bucketStart(transaction.occurredAt.toLocalDate(), granularity))
                        .add(transaction.amountMinor)
                } else {
                    excluded.add(transaction)
                }
            } else if (matchesFilter(transaction, previousFilter) &&
                FinanceRules.evaluate(transaction, rules).included
            ) {
                previous.add(transaction.amountMinor)
            }
        }

        included.sortWith(newestFirst)
        excluded.sortWith(newestFirst)
        val expenses = included.filter { it.amountMinor < 0 }
        val income = included.filter { it.amountMinor > 0 }
        val currentTotals = current.snapshot()
        return FinanceAnalysis(
            current = currentTotals,
            previous = previous.snapshot(),
            expenseCategories = breakdown(expenses) { it.category },
            incomeCategories = breakdown(income) { it.category },
            expenseAccounts = breakdown(expenses) { it.account },
            incomeAccounts = breakdown(income) { it.account },
            merchants = breakdown(expenses) { it.description },
            trend = buckets.map { (start, totals) -> FinanceTrend(start, totals.income, totals.expense) },
            largestExpenses = expenses.sortedWith(
                compareBy<FinanceTransaction> { it.amountMinor }.then(newestFirst),
            ).take(10),
            largestIncome = income.sortedWith(
                compareByDescending<FinanceTransaction> { it.amountMinor }.then(newestFirst),
            ).take(10),
            included = included,
            excluded = excluded,
            averageDailyExpenseMinor = currentTotals.expenseMinor / days,
            savingsRatePercent = if (currentTotals.incomeMinor > 0) {
                currentTotals.netMinor.toDouble() / currentTotals.incomeMinor.toDouble() * 100.0
            } else {
                null
            },
        )
    }

    private fun requireValidPeriod(filter: FinanceFilter) {
        require(!filter.endInclusive.isBefore(filter.start)) { "Начало периода должно быть не позже конца" }
    }

    private fun matchesFilter(transaction: FinanceTransaction, filter: FinanceFilter): Boolean {
        val date = transaction.occurredAt.toLocalDate()
        if (date.isBefore(filter.start) || date.isAfter(filter.endInclusive)) return false
        if (transaction.currency != filter.currency) return false
        if (filter.account != null && transaction.account != filter.account) return false
        if (filter.category != null && transaction.category != filter.category) return false
        return filter.search.isBlank() ||
            transaction.description.contains(filter.search, ignoreCase = true) ||
            transaction.note.contains(filter.search, ignoreCase = true) ||
            transaction.category.contains(filter.search, ignoreCase = true) ||
            transaction.account.contains(filter.search, ignoreCase = true)
    }

    private fun breakdown(
        transactions: List<FinanceTransaction>,
        label: (FinanceTransaction) -> String,
    ): List<FinanceBreakdown> {
        val totals = linkedMapOf<String, TotalsAccumulator>()
        for (transaction in transactions) {
            totals.getOrPut(label(transaction)) { TotalsAccumulator() }.add(transaction.amountMinor)
        }
        return totals.map { (name, total) ->
            FinanceBreakdown(name, Math.addExact(total.income, total.expense), total.count)
        }.sortedWith(compareByDescending<FinanceBreakdown> { it.amountMinor }.thenBy { it.label })
    }

    private fun bucketStart(date: LocalDate, granularity: FinanceGranularity): LocalDate =
        when (granularity) {
            FinanceGranularity.Day -> date
            FinanceGranularity.Week -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            FinanceGranularity.Month -> date.withDayOfMonth(1)
        }

    private fun emptyBuckets(
        filter: FinanceFilter,
        granularity: FinanceGranularity,
    ): LinkedHashMap<LocalDate, TotalsAccumulator> {
        val first = bucketStart(filter.start, granularity)
        val last = bucketStart(filter.endInclusive, granularity)
        val unit = when (granularity) {
            FinanceGranularity.Day -> ChronoUnit.DAYS
            FinanceGranularity.Week -> ChronoUnit.WEEKS
            FinanceGranularity.Month -> ChronoUnit.MONTHS
        }
        val count = Math.addExact(unit.between(first, last), 1L)
        require(count <= MAX_TREND_BUCKETS) { "Слишком много точек графика: выберите более крупный шаг" }
        val buckets = linkedMapOf<LocalDate, TotalsAccumulator>()
        var start = first
        repeat(count.toInt()) { index ->
            buckets[start] = TotalsAccumulator()
            if (index < count - 1) start = start.plus(1, unit)
        }
        return buckets
    }

    private class TotalsAccumulator {
        var income = 0L
            private set
        var expense = 0L
            private set
        var count = 0
            private set

        fun add(amountMinor: Long) {
            if (amountMinor > 0) {
                income = Math.addExact(income, amountMinor)
            } else if (amountMinor < 0) {
                expense = Math.addExact(expense, Math.negateExact(amountMinor))
            }
            count = Math.addExact(count, 1)
        }

        fun snapshot() = FinanceTotals(income, expense, Math.subtractExact(income, expense), count)
    }
}
