package ru.begunok.finance

import java.math.BigDecimal
import java.text.NumberFormat
import java.time.LocalDateTime
import java.util.Currency
import java.util.Locale

// CSV may contain zero-valued failed operations; manual operations must be nonzero.
enum class FinanceSource { Csv, Manual }
enum class FinanceInclusion { Auto, Include, Exclude }
enum class FinanceDirection { Any, Income, Expense }

data class FinanceTransaction(
    val id: Long = 0,
    val occurredAt: LocalDateTime,
    val amountMinor: Long,
    val currency: String = "RUB",
    val account: String,
    val card: String = "",
    val category: String,
    val description: String,
    val note: String = "",
    val status: String = "Ок",
    val bankIncluded: Boolean = true,
    val source: FinanceSource = FinanceSource.Manual,
    val importKey: String? = null,
    val inclusion: FinanceInclusion = FinanceInclusion.Auto,
)

data class FinanceRule(
    val id: Long = 0,
    val name: String,
    val enabled: Boolean = true,
    val action: FinanceInclusion = FinanceInclusion.Exclude,
    val direction: FinanceDirection = FinanceDirection.Any,
    val account: String? = null,
    val category: String? = null,
    val descriptionContains: String = "",
    val minAmountMinor: Long? = null,
    val maxAmountMinor: Long? = null,
    val currency: String? = null,
)

data class FinanceDecision(val included: Boolean, val reason: String, val ruleId: Long? = null)

data class FinanceImportPreview(
    val transactions: List<FinanceTransaction>,
    val fileName: String,
    val failedCount: Int,
)

object FinanceMoney {
    private val amountPattern = Regex("[+-]?[0-9]+(?:[.,][0-9]{1,2})?")

    fun parseMinor(text: String): Long {
        val normalized = text.trim().replace(" ", "").replace("\u00a0", "").replace("\u202f", "")
        require(amountPattern.matches(normalized)) { "Введите сумму с точностью не более двух знаков после запятой" }
        return try {
            BigDecimal(normalized.replace(',', '.')).movePointRight(2).longValueExact()
        } catch (_: ArithmeticException) {
            throw IllegalArgumentException("Сумма выходит за допустимый диапазон")
        }
    }

    fun requireCurrency(code: String): Currency {
        val currency = try {
            Currency.getInstance(code)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Неизвестная валюта: $code")
        }
        require(currency.defaultFractionDigits == 2) { "Поддерживаются только валюты с двумя десятичными знаками: $code" }
        return currency
    }

    fun format(amountMinor: Long, currency: String): String {
        requireCurrency(currency)
        val formatter = NumberFormat.getNumberInstance(Locale.forLanguageTag("ru-RU")).apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
            isGroupingUsed = true
        }
        return "${formatter.format(BigDecimal.valueOf(amountMinor, 2))} $currency"
    }
}

object FinanceRules {
    fun validate(rule: FinanceRule) {
        require(rule.id >= 0) { "Некорректный идентификатор правила" }
        require(rule.name.isNotBlank()) { "Укажите название правила" }
        require(rule.action != FinanceInclusion.Auto) { "Правило должно включать или исключать операции" }
        require(rule.account == null || rule.account.isNotBlank()) { "Счёт не может быть пустым" }
        require(rule.category == null || rule.category.isNotBlank()) { "Категория не может быть пустой" }
        rule.currency?.let { FinanceMoney.requireCurrency(it) }
        require(rule.minAmountMinor == null || rule.minAmountMinor > 0) { "Минимальная сумма должна быть больше нуля" }
        require(rule.maxAmountMinor == null || rule.maxAmountMinor > 0) { "Максимальная сумма должна быть больше нуля" }
        require(rule.minAmountMinor == null || rule.maxAmountMinor == null || rule.minAmountMinor <= rule.maxAmountMinor) {
            "Минимальная сумма не может превышать максимальную"
        }
        require((rule.minAmountMinor == null && rule.maxAmountMinor == null) || rule.currency != null) {
            "Для условия по сумме укажите валюту"
        }
        require(rule.direction != FinanceDirection.Any || rule.account != null || rule.category != null ||
            rule.descriptionContains.isNotBlank() || rule.minAmountMinor != null || rule.maxAmountMinor != null || rule.currency != null) {
            "Добавьте хотя бы одно условие правила"
        }
    }

    fun evaluate(transaction: FinanceTransaction, rules: List<FinanceRule>): FinanceDecision {
        if (!transaction.status.equals("Ок", ignoreCase = true)) {
            return FinanceDecision(false, "Не учитывается: статус банка «${transaction.status}»")
        }
        when (transaction.inclusion) {
            FinanceInclusion.Include -> return FinanceDecision(true, "Включено вручную для этой операции")
            FinanceInclusion.Exclude -> return FinanceDecision(false, "Исключено вручную для этой операции")
            FinanceInclusion.Auto -> Unit
        }
        var rule: FinanceRule? = null
        for (candidate in rules) {
            if (candidate.enabled && (rule == null || candidate.id > rule.id) && matches(transaction, candidate)) {
                rule = candidate
            }
        }
        if (rule != null) {
            return FinanceDecision(rule.action == FinanceInclusion.Include, "Правило «${rule.name}»", rule.id)
        }
        return if (transaction.bankIncluded) {
            FinanceDecision(true, if (transaction.source == FinanceSource.Manual) "Ручная операция" else "Включено банком в аналитику")
        } else {
            FinanceDecision(false, "Исключено банком из аналитики")
        }
    }

    private fun matches(transaction: FinanceTransaction, rule: FinanceRule): Boolean {
        if (rule.action == FinanceInclusion.Auto) return false
        if (rule.direction == FinanceDirection.Income && transaction.amountMinor <= 0) return false
        if (rule.direction == FinanceDirection.Expense && transaction.amountMinor >= 0) return false
        if (rule.account != null && transaction.account != rule.account) return false
        if (rule.category != null && transaction.category != rule.category) return false
        if (rule.currency != null && transaction.currency != rule.currency) return false
        if (!transaction.description.contains(rule.descriptionContains, ignoreCase = true)) return false
        // abs(Long.MIN_VALUE) overflows: its magnitude exceeds every representable positive bound.
        if (transaction.amountMinor == Long.MIN_VALUE) return rule.maxAmountMinor == null
        val magnitude = kotlin.math.abs(transaction.amountMinor)
        if (rule.minAmountMinor != null && magnitude < rule.minAmountMinor) return false
        if (rule.maxAmountMinor != null && magnitude > rule.maxAmountMinor) return false
        return true
    }
}
