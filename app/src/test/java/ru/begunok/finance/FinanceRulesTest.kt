package ru.begunok.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class FinanceRulesTest {
    private val operation = FinanceTransaction(
        occurredAt = LocalDateTime.of(2026, 11, 10, 12, 0), amountMinor = -12_345,
        account = "Основной", category = "Переводы", description = "Перевод другу", source = FinanceSource.Csv,
    )
    private val exclusion = FinanceRule(id = 1, name = "Переводы другу", descriptionContains = "ДРУГУ")

    private fun invalid(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected validation failure")
        } catch (_: IllegalArgumentException) {
            // Expected user-correctable input error.
        }
    }

    @Test
    fun bankStatusAlwaysWinsOverOverridesAndRules() {
        val forced = operation.copy(status = "Ошибка", inclusion = FinanceInclusion.Include)
        val decision = FinanceRules.evaluate(forced, listOf(exclusion.copy(action = FinanceInclusion.Include)))
        assertFalse(decision.included)
        assertNull(decision.ruleId)
        assertTrue(decision.reason.contains("Ошибка"))
        assertTrue(FinanceRules.evaluate(operation.copy(status = "оК"), emptyList()).included)
    }

    @Test
    fun perOperationOverrideWinsOverMatchingRules() {
        assertTrue(FinanceRules.evaluate(operation.copy(inclusion = FinanceInclusion.Include), listOf(exclusion)).included)
        assertFalse(FinanceRules.evaluate(operation.copy(inclusion = FinanceInclusion.Exclude),
            listOf(exclusion.copy(action = FinanceInclusion.Include))).included)
    }

    @Test
    fun newestMatchingEnabledRuleWinsRegardlessOfInputOrder() {
        val include = exclusion.copy(id = 3, name = "Новый", action = FinanceInclusion.Include)
        val disabled = exclusion.copy(id = 99, enabled = false)
        val unrelated = exclusion.copy(id = 100, account = "Другой")
        for (rules in listOf(listOf(exclusion, include, disabled, unrelated), listOf(unrelated, disabled, include, exclusion))) {
            val decision = FinanceRules.evaluate(operation, rules)
            assertTrue(decision.included)
            assertEquals(3L, decision.ruleId)
            assertTrue(decision.reason.contains("Новый"))
        }
    }

    @Test
    fun transfersAreNotBlanketExcludedAndBankFallbackIsHonoured() {
        assertTrue(FinanceRules.evaluate(operation, emptyList()).included)
        assertFalse(FinanceRules.evaluate(operation.copy(bankIncluded = false), emptyList()).included)
        assertTrue(FinanceRules.evaluate(operation.copy(source = FinanceSource.Manual), emptyList()).included)
        assertFalse(FinanceRules.evaluate(operation, listOf(exclusion)).included)
        assertEquals(1L, FinanceRules.evaluate(operation.copy(occurredAt = operation.occurredAt.minusYears(1)), listOf(exclusion)).ruleId)
    }

    @Test
    fun allFilledPredicatesAreAndedWithInclusiveAbsoluteBounds() {
        val rule = exclusion.copy(direction = FinanceDirection.Expense, account = "Основной", category = "Переводы",
            minAmountMinor = 12_345, maxAmountMinor = 12_345, currency = "RUB")
        FinanceRules.validate(rule)
        assertEquals(1L, FinanceRules.evaluate(operation, listOf(rule)).ruleId)
        val mismatches = listOf(
            operation.copy(amountMinor = 12_345), operation.copy(amountMinor = -12_344), operation.copy(amountMinor = -12_346),
            operation.copy(account = "основной"), operation.copy(category = "Другая"), operation.copy(currency = "USD"),
            operation.copy(description = "Перевод себе"),
        )
        for (transaction in mismatches) assertNull(FinanceRules.evaluate(transaction, listOf(rule)).ruleId)
        val incomeRule = rule.copy(direction = FinanceDirection.Income)
        assertEquals(1L, FinanceRules.evaluate(operation.copy(amountMinor = 12_345), listOf(incomeRule)).ruleId)
    }

    @Test
    fun zeroHasNoIncomeOrExpenseDirection() {
        for (direction in listOf(FinanceDirection.Income, FinanceDirection.Expense)) {
            assertNull(FinanceRules.evaluate(operation.copy(amountMinor = 0), listOf(exclusion.copy(direction = direction))).ruleId)
        }
    }

    @Test
    fun longMinimumAbsoluteMagnitudeNeverOverflows() {
        val minimum = operation.copy(amountMinor = Long.MIN_VALUE)
        assertEquals(1L, FinanceRules.evaluate(minimum,
            listOf(exclusion.copy(minAmountMinor = Long.MAX_VALUE, currency = "RUB"))).ruleId)
        assertNull(FinanceRules.evaluate(minimum,
            listOf(exclusion.copy(maxAmountMinor = Long.MAX_VALUE, currency = "RUB"))).ruleId)
    }

    @Test
    fun unsafeRulesAreRejectedBeforePersistence() {
        invalid { FinanceRules.validate(exclusion.copy(name = " ")) }
        invalid { FinanceRules.validate(exclusion.copy(action = FinanceInclusion.Auto)) }
        invalid { FinanceRules.validate(FinanceRule(name = "Без условий")) }
        invalid { FinanceRules.validate(exclusion.copy(descriptionContains = " ")) }
        invalid { FinanceRules.validate(exclusion.copy(account = "")) }
        invalid { FinanceRules.validate(exclusion.copy(category = " ")) }
        invalid { FinanceRules.validate(exclusion.copy(minAmountMinor = -1, currency = "RUB")) }
        invalid { FinanceRules.validate(exclusion.copy(maxAmountMinor = 0, currency = "RUB")) }
        invalid { FinanceRules.validate(exclusion.copy(minAmountMinor = 200, maxAmountMinor = 100, currency = "RUB")) }
        invalid { FinanceRules.validate(exclusion.copy(minAmountMinor = 100)) }
        invalid { FinanceRules.validate(exclusion.copy(currency = "JPY")) }
        FinanceRules.validate(FinanceRule(name = "Все расходы", direction = FinanceDirection.Expense))
        FinanceRules.validate(FinanceRule(name = "Все рубли", currency = "RUB"))
    }

    @Test
    fun exactMoneyAcceptsLocaleSeparatorsAndFullLongRange() {
        assertEquals(123_456L, FinanceMoney.parseMinor(" +1 234,56 "))
        assertEquals(-123_456L, FinanceMoney.parseMinor("-1\u00a0234.56"))
        assertEquals(123_456L, FinanceMoney.parseMinor("1\u202f234,56"))
        assertEquals(10L, FinanceMoney.parseMinor("0.1"))
        assertEquals(100L, FinanceMoney.parseMinor("1"))
        assertEquals(0L, FinanceMoney.parseMinor("-0,00"))
        assertEquals(Long.MAX_VALUE, FinanceMoney.parseMinor("92233720368547758.07"))
        assertEquals(Long.MIN_VALUE, FinanceMoney.parseMinor("-92233720368547758,08"))
    }

    @Test
    fun moneyRejectsPrecisionLossOverflowAndAmbiguousNumbers() {
        for (text in listOf("", " ", "1,001", "1.230", "1,2.3", "1e2", "NaN", "1,", ".5", "--1",
            "92233720368547758.08", "-92233720368547758.09")) {
            invalid { FinanceMoney.parseMinor(text) }
        }
        for (currency in listOf("JPY", "KWD", "XXX", "rub", "INVALID")) {
            invalid { FinanceMoney.format(100, currency) }
        }
    }

    @Test
    fun formattingNeverDropsCentsOrOverflowsLongMinimum() {
        fun ungroup(value: String) = value.replace("\u00a0", "").replace("\u202f", "").replace(" ", "")
        assertEquals("-0,01RUB", ungroup(FinanceMoney.format(-1, "RUB")))
        assertEquals("92233720368547758,07USD", ungroup(FinanceMoney.format(Long.MAX_VALUE, "USD")))
        assertEquals("-92233720368547758,08RUB", ungroup(FinanceMoney.format(Long.MIN_VALUE, "RUB")))
    }
}
