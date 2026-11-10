package ru.begunok.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class FinanceAnalyticsTest {
    private val march = FinanceFilter(date("2026-03-01"), date("2026-03-31"))

    @Test
    fun dateBoundariesIncludeWholeLastDayWithoutTimezoneConversion() {
        val first = transaction(100, "2026-03-01T00:00:00", id = 1)
        val last = transaction(-25, "2026-03-31T23:59:59.999999999", id = 2)
        val before = transaction(17, "2026-02-28T23:59:59.999999999", id = 3)
        val after = transaction(19, "2026-04-01T00:00:00", id = 4)
        val result = FinanceAnalytics.build(listOf(after, first, before, last), emptyList(), march)

        assertEquals(FinanceTotals(100, 25, 75, 2), result.current)
        assertEquals(listOf(2L, 1L), result.included.map { it.id })
        assertTrue(FinanceAnalytics.matches(first, march))
        assertTrue(FinanceAnalytics.matches(last, march))
        assertFalse(FinanceAnalytics.matches(before, march))
        assertFalse(FinanceAnalytics.matches(after, march))
    }

    @Test
    fun filtersAreConjunctiveAndCurrenciesAndAccountsNeverMix() {
        val selected = transaction(-100, account = "Основной", category = "Еда", description = "Кофе")
        val filter = march.copy(account = "Основной", category = "Еда", search = "КОФ")
        val rows = listOf(
            selected,
            selected.copy(currency = "USD", amountMinor = Long.MAX_VALUE),
            selected.copy(account = "Другой"),
            selected.copy(category = "Другое"),
            selected.copy(description = "Чай"),
            selected.copy(currency = "EUR", bankIncluded = false),
        )
        val result = FinanceAnalytics.build(rows, emptyList(), filter)

        assertEquals(FinanceTotals(0, 100, -100, 1), result.current)
        assertEquals(listOf(selected), result.included)
        assertTrue(result.excluded.isEmpty())
        assertEquals(listOf(FinanceBreakdown("Основной", 100, 1)), result.expenseAccounts)
        assertFalse(FinanceAnalytics.matches(selected.copy(account = "основной"), filter))
        assertFalse(FinanceAnalytics.matches(selected.copy(category = "еда"), filter))
    }

    @Test
    fun searchChecksDescriptionNoteCategoryAndAccountCaseInsensitively() {
        val row = transaction(100, description = "Кофейня", note = "Подарок", category = "Еда", account = "Основной")
        for (search in listOf("КОФЕ", "ДАР", "ЕДА", "ОСНОВ")) {
            assertTrue(FinanceAnalytics.matches(row, march.copy(search = search)))
        }
        assertTrue(FinanceAnalytics.matches(row, march.copy(search = "  ")))
        assertFalse(FinanceAnalytics.matches(row, march.copy(search = "не найдено")))
        assertFalse(FinanceAnalytics.matches(row.copy(card = "1234"), march.copy(search = "1234")))
    }

    @Test
    fun previousPeriodHasSameCalendarLengthIncludingLeapDay() {
        val filter = FinanceFilter(date("2024-03-01"), date("2024-03-31"))
        val rows = listOf(
            transaction(111, "2024-01-29T23:59:59"),
            transaction(200, "2024-01-30T00:00:00"),
            transaction(-75, "2024-02-29T23:59:59.999999999"),
            transaction(900, "2024-03-01T00:00:00"),
            transaction(-100, "2024-03-31T23:59:59"),
            transaction(888, "2024-04-01T00:00:00"),
        )
        val result = FinanceAnalytics.build(rows, emptyList(), filter)

        assertEquals(FinanceTotals(900, 100, 800, 2), result.current)
        assertEquals(FinanceTotals(200, 75, 125, 2), result.previous)
    }

    @Test
    fun previousPeriodAppliesSameFiltersAndRules() {
        val filter = march.copy(account = "Карта", category = "Еда", search = "Кофе")
        val matching = transaction(-100, "2026-02-28T12:00:00", account = "Карта", category = "Еда", description = "Кофе")
        val rule = FinanceRule(id = 1, name = "Исключить кофе", descriptionContains = "Кофе")
        val rows = listOf(
            matching.copy(inclusion = FinanceInclusion.Include),
            matching,
            matching.copy(currency = "USD", inclusion = FinanceInclusion.Include),
            matching.copy(account = "Вклад", inclusion = FinanceInclusion.Include),
            matching.copy(category = "Подарки", inclusion = FinanceInclusion.Include),
            matching.copy(description = "Чай", inclusion = FinanceInclusion.Include),
            matching.copy(occurredAt = LocalDateTime.parse("2026-03-10T12:00:00"), inclusion = FinanceInclusion.Include),
        )
        val result = FinanceAnalytics.build(rows, listOf(rule), filter)

        assertEquals(FinanceTotals(0, 100, -100, 1), result.previous)
        assertEquals(FinanceTotals(0, 100, -100, 1), result.current)
    }

    @Test
    fun explicitOverridesPrecedeRulesAndFailedStatusCannotBeIncluded() {
        val row = transaction(-100)
        val exclusion = FinanceRule(id = 10, name = "Исключение", category = row.category)
        val included = row.copy(id = 1, inclusion = FinanceInclusion.Include, bankIncluded = false)
        val failed = included.copy(id = 2, status = "Ошибка")
        val automatic = row.copy(id = 3)
        val result = FinanceAnalytics.build(listOf(included, failed, automatic), listOf(exclusion), march)

        assertEquals(listOf(included), result.included)
        assertEquals(listOf(3L, 2L), result.excluded.map { it.id })
        assertEquals(FinanceTotals(0, 100, -100, 1), result.current)
        assertEquals(listOf(FinanceBreakdown(row.category, 100, 1)), result.expenseCategories)
        assertEquals(100L, result.trend.single().expenseMinor)
        assertEquals(listOf(included), result.largestExpenses)

        val includeRule = exclusion.copy(action = FinanceInclusion.Include)
        val overridden = row.copy(inclusion = FinanceInclusion.Exclude)
        assertEquals(listOf(overridden), FinanceAnalytics.build(listOf(overridden), listOf(includeRule), march).excluded)
    }

    @Test
    fun newestMatchingEnabledRuleWinsAndBankFlagIsOnlyFallback() {
        val row = transaction(-100, category = "Переводы")
        val older = FinanceRule(id = 1, name = "Включить", action = FinanceInclusion.Include, category = "Переводы")
        val newer = older.copy(id = 2, name = "Исключить", action = FinanceInclusion.Exclude)
        val excluded = FinanceAnalytics.build(listOf(row), listOf(older, newer), march)
        assertTrue(excluded.included.isEmpty())
        assertEquals(listOf(row), excluded.excluded)

        val disabled = newer.copy(enabled = false)
        assertEquals(listOf(row), FinanceAnalytics.build(listOf(row), listOf(disabled, older), march).included)
        assertEquals(listOf(row), FinanceAnalytics.build(listOf(row), emptyList(), march).included)
        val bankExcluded = row.copy(bankIncluded = false)
        assertEquals(listOf(bankExcluded), FinanceAnalytics.build(listOf(bankExcluded), emptyList(), march).excluded)
        assertEquals(listOf(bankExcluded), FinanceAnalytics.build(listOf(bankExcluded), listOf(older), march).included)
    }

    @Test
    fun dailyTrendHasZeroBucketsAndUsesOnlyIncludedCurrentRows() {
        val filter = FinanceFilter(date("2026-03-01"), date("2026-03-03"))
        val rows = listOf(
            transaction(300, "2026-03-01T12:00:00"),
            transaction(-101, "2026-03-03T12:00:00"),
            transaction(-999, "2026-03-02T12:00:00", bankIncluded = false),
            transaction(-888, "2026-02-28T12:00:00"),
        )
        val result = FinanceAnalytics.build(rows, emptyList(), filter, FinanceGranularity.Day)

        assertEquals(
            listOf(
                FinanceTrend(date("2026-03-01"), 300, 0),
                FinanceTrend(date("2026-03-02"), 0, 0),
                FinanceTrend(date("2026-03-03"), 0, 101),
            ),
            result.trend,
        )
        assertEquals(33L, result.averageDailyExpenseMinor)
        assertEquals(199.0 / 300.0 * 100, result.savingsRatePercent!!, 0.000001)
    }

    @Test
    fun weeklyTrendIsMondayBasedAcrossYearAndDoesNotIncludeOutsidePartialWeeks() {
        val filter = FinanceFilter(date("2025-12-31"), date("2026-01-13"))
        val rows = listOf(
            transaction(-500, "2025-12-29T12:00:00"),
            transaction(-10, "2025-12-31T12:00:00"),
            transaction(20, "2026-01-04T23:59:59"),
            transaction(-30, "2026-01-12T00:00:00"),
            transaction(600, "2026-01-14T12:00:00"),
        )
        val result = FinanceAnalytics.build(rows, emptyList(), filter, FinanceGranularity.Week)

        assertEquals(
            listOf(
                FinanceTrend(date("2025-12-29"), 20, 10),
                FinanceTrend(date("2026-01-05"), 0, 0),
                FinanceTrend(date("2026-01-12"), 0, 30),
            ),
            result.trend,
        )
    }

    @Test
    fun monthlyTrendIncludesEmptyMonthsAndPartialEnds() {
        val filter = FinanceFilter(date("2026-01-31"), date("2026-03-01"))
        val rows = listOf(
            transaction(999, "2026-01-30T23:59:59"),
            transaction(-10, "2026-01-31T12:00:00"),
            transaction(20, "2026-03-01T12:00:00"),
            transaction(-888, "2026-03-02T00:00:00"),
        )
        val result = FinanceAnalytics.build(rows, emptyList(), filter)

        assertEquals(
            listOf(
                FinanceTrend(date("2026-01-01"), 0, 10),
                FinanceTrend(date("2026-02-01"), 0, 0),
                FinanceTrend(date("2026-03-01"), 20, 0),
            ),
            result.trend,
        )
        assertEquals(0L, result.averageDailyExpenseMinor)
    }

    @Test
    fun emptyPeriodHasRealZeroBucketsAndNoSavingsRatio() {
        val filter = FinanceFilter(date("2026-03-01"), date("2026-03-03"))
        val result = FinanceAnalytics.build(emptyList(), emptyList(), filter, FinanceGranularity.Day)

        assertEquals(FinanceTotals(0, 0, 0, 0), result.current)
        assertEquals(FinanceTotals(0, 0, 0, 0), result.previous)
        assertEquals(3, result.trend.size)
        assertTrue(result.trend.all { it.incomeMinor == 0L && it.expenseMinor == 0L })
        assertEquals(0L, result.averageDailyExpenseMinor)
        assertNull(result.savingsRatePercent)
        assertTrue(result.included.isEmpty())
        assertTrue(result.excluded.isEmpty())
        assertTrue(result.expenseCategories.isEmpty())
        assertTrue(result.incomeCategories.isEmpty())
        assertTrue(result.expenseAccounts.isEmpty())
        assertTrue(result.incomeAccounts.isEmpty())
        assertTrue(result.merchants.isEmpty())
        assertTrue(result.largestExpenses.isEmpty())
        assertTrue(result.largestIncome.isEmpty())
    }

    @Test
    fun neutralZeroCsvRowsCountButAreNotIncomeExpenseOrMerchants() {
        val zero = transaction(0).copy(source = FinanceSource.Csv)
        val result = FinanceAnalytics.build(listOf(zero), emptyList(), march)

        assertEquals(FinanceTotals(0, 0, 0, 1), result.current)
        assertEquals(listOf(zero), result.included)
        assertTrue(result.expenseCategories.isEmpty())
        assertTrue(result.incomeCategories.isEmpty())
        assertTrue(result.expenseAccounts.isEmpty())
        assertTrue(result.incomeAccounts.isEmpty())
        assertTrue(result.merchants.isEmpty())
        assertTrue(result.largestIncome.isEmpty())
        assertTrue(result.largestExpenses.isEmpty())
        assertEquals(FinanceTrend(march.start, 0, 0), result.trend.single())
        assertNull(result.savingsRatePercent)
    }

    @Test
    fun legitimateIdenticalTransactionsAreNotDeduplicated() {
        val row = transaction(-100)
        val result = FinanceAnalytics.build(listOf(row, row), emptyList(), march)

        assertEquals(FinanceTotals(0, 200, -200, 2), result.current)
        assertEquals(listOf(row, row), result.included)
        assertEquals(listOf(FinanceBreakdown(row.category, 200, 2)), result.expenseCategories)
        assertEquals(listOf(row, row), result.largestExpenses)
    }

    @Test
    fun breakdownsHavePositiveMagnitudesCountsAndDeterministicAmountLabelSorting() {
        val rows = listOf(
            transaction(-100, category = "Б", account = "Б", description = "Магазин Б"),
            transaction(-40, category = "А", account = "А", description = "Магазин А"),
            transaction(-60, category = "А", account = "А", description = "Магазин А"),
            transaction(-200, category = "В", account = "В", description = "Магазин В"),
            transaction(50, category = "Я", account = "Я", description = "Работа"),
            transaction(50, category = "Г", account = "Г", description = "Работа"),
        )
        val result = FinanceAnalytics.build(rows.reversed(), emptyList(), march)

        assertEquals(
            listOf(FinanceBreakdown("В", 200, 1), FinanceBreakdown("А", 100, 2), FinanceBreakdown("Б", 100, 1)),
            result.expenseCategories,
        )
        assertEquals(result.expenseCategories, result.expenseAccounts)
        assertEquals(
            listOf(FinanceBreakdown("Г", 50, 1), FinanceBreakdown("Я", 50, 1)),
            result.incomeCategories,
        )
        assertEquals(result.incomeCategories, result.incomeAccounts)
        assertEquals(listOf("Магазин В", "Магазин А", "Магазин Б"), result.merchants.map { it.label })
        assertEquals(listOf(200L, 100L, 100L), result.merchants.map { it.amountMinor })
        assertEquals(listOf(1, 2, 1), result.merchants.map { it.count })
    }

    @Test
    fun includedAndExcludedSortNewestThenIdButLargestSortsMagnitudeFirstAndCapsTen() {
        val expenses = (1L..12L).map { transaction(-it, id = it) }
        val income = (1L..12L).map { transaction(it, id = it + 20) }
        val newerSmall = transaction(-1, "2026-03-31T12:00:00", id = 100)
        val newerEqual = transaction(-12, "2026-03-31T12:00:00", id = 101)
        val excluded = listOf(
            transaction(-500, "2026-03-29T12:00:00", id = 201, bankIncluded = false),
            transaction(-500, "2026-03-30T12:00:00", id = 202, bankIncluded = false),
            transaction(-500, "2026-03-30T12:00:00", id = 203, bankIncluded = false),
        )
        val result = FinanceAnalytics.build(expenses + income + newerSmall + newerEqual + excluded, emptyList(), march)

        assertEquals(listOf(101L, 100L, 32L), result.included.take(3).map { it.id })
        assertEquals(listOf(203L, 202L, 201L), result.excluded.map { it.id })
        assertEquals(10, result.largestExpenses.size)
        assertEquals(listOf(101L, 12L, 11L), result.largestExpenses.take(3).map { it.id })
        assertEquals(listOf(-12L, -12L, -11L, -10L, -9L, -8L, -7L, -6L, -5L, -4L), result.largestExpenses.map { it.amountMinor })
        assertEquals((12L downTo 3L).toList(), result.largestIncome.map { it.amountMinor })
    }

    @Test
    fun savingsCanBeNegativeAndExpenseOnlyHasNoRatio() {
        val result = FinanceAnalytics.build(listOf(transaction(100), transaction(-150)), emptyList(), march)
        assertEquals(-50.0, result.savingsRatePercent!!, 0.0)
        assertEquals(-50L, result.current.netMinor)
        assertNull(FinanceAnalytics.build(listOf(transaction(-150)), emptyList(), march).savingsRatePercent)
    }

    @Test
    fun singleDayAverageUsesOneDayAndPreviousEndsImmediatelyBeforeIt() {
        val filter = FinanceFilter(date("2026-03-01"), date("2026-03-01"))
        val rows = listOf(
            transaction(-101, "2026-03-01T23:59:59"),
            transaction(-25, "2026-02-28T23:59:59"),
            transaction(-99, "2026-02-27T23:59:59"),
        )
        val result = FinanceAnalytics.build(rows, emptyList(), filter, FinanceGranularity.Day)
        assertEquals(101L, result.averageDailyExpenseMinor)
        assertEquals(FinanceTotals(0, 25, -25, 1), result.previous)
        assertEquals(listOf(FinanceTrend(filter.start, 0, 101)), result.trend)
    }

    @Test
    fun moneyAboveDoubleExactIntegerRangeIsStillSummedExactly() {
        val large = 9_007_199_254_740_993L
        val result = FinanceAnalytics.build(
            listOf(transaction(large), transaction(2), transaction(-large), transaction(-1)),
            emptyList(),
            march,
        )
        assertEquals(FinanceTotals(large + 2, large + 1, 1, 4), result.current)
        assertEquals(large + 1, result.expenseCategories.single().amountMinor)
        assertEquals(large + 2, result.trend.single().incomeMinor)
    }

    @Test(expected = ArithmeticException::class)
    fun incomeOverflowFailsRatherThanWrappingOrCancellingWithExpenses() {
        FinanceAnalytics.build(listOf(transaction(Long.MAX_VALUE), transaction(1), transaction(-1)), emptyList(), march)
    }

    @Test(expected = ArithmeticException::class)
    fun expenseOverflowFailsRatherThanWrapping() {
        FinanceAnalytics.build(listOf(transaction(-Long.MAX_VALUE), transaction(-1)), emptyList(), march)
    }

    @Test(expected = ArithmeticException::class)
    fun minimumSignedLongExpenseCannotBecomeNegativeMagnitude() {
        FinanceAnalytics.build(listOf(transaction(Long.MIN_VALUE)), emptyList(), march)
    }

    @Test(expected = ArithmeticException::class)
    fun previousTotalsUseCheckedArithmeticToo() {
        FinanceAnalytics.build(
            listOf(transaction(Long.MAX_VALUE, "2026-02-28T12:00:00"), transaction(1, "2026-02-28T12:00:00")),
            emptyList(),
            march,
        )
    }

    @Test
    fun excludedAndOtherCurrencyExtremesDoNotAffectMoney() {
        val rows = listOf(
            transaction(100),
            transaction(Long.MAX_VALUE, bankIncluded = false),
            transaction(Long.MIN_VALUE, bankIncluded = false),
            transaction(Long.MIN_VALUE, currency = "USD"),
        )
        val result = FinanceAnalytics.build(rows, emptyList(), march)
        assertEquals(FinanceTotals(100, 0, 100, 1), result.current)
        assertEquals(2, result.excluded.size)
        assertEquals(100.0, result.savingsRatePercent!!, 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invertedPeriodIsRejectedByBuildEvenWhenEmpty() {
        FinanceAnalytics.build(emptyList(), emptyList(), march.copy(endInclusive = march.start.minusDays(1)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invertedPeriodIsRejectedByMatches() {
        FinanceAnalytics.matches(transaction(1), march.copy(endInclusive = march.start.minusDays(1)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun hugeDailyChartIsRejectedBeforeAllocatingUnboundedBuckets() {
        FinanceAnalytics.build(
            emptyList(),
            emptyList(),
            FinanceFilter(date("1900-01-01"), date("3000-01-01")),
            FinanceGranularity.Day,
        )
    }

    private fun date(value: String): LocalDate = LocalDate.parse(value)

    private fun transaction(
        amount: Long,
        at: String = "2026-03-15T12:00:00",
        id: Long = 0,
        currency: String = "RUB",
        account: String = "Основной",
        category: String = "Покупки",
        description: String = "Магазин",
        note: String = "",
        bankIncluded: Boolean = true,
    ) = FinanceTransaction(
        id = id,
        occurredAt = LocalDateTime.parse(at),
        amountMinor = amount,
        currency = currency,
        account = account,
        category = category,
        description = description,
        note = note,
        bankIncluded = bankIncluded,
    )
}
