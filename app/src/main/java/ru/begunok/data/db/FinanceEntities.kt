package ru.begunok.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import ru.begunok.finance.FinanceDirection
import ru.begunok.finance.FinanceInclusion
import ru.begunok.finance.FinanceRule
import ru.begunok.finance.FinanceSource
import ru.begunok.finance.FinanceTransaction
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Entity(tableName = "finance_transactions", indices = [Index(value = ["importKey"], unique = true)])
data class FinanceTransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val occurredAt: String,
    val amountMinor: Long,
    val currency: String,
    val account: String,
    val card: String,
    val category: String,
    val description: String,
    val note: String,
    val status: String,
    val bankIncluded: Boolean,
    val source: String,
    val importKey: String?,
    val inclusion: String,
)

@Entity(tableName = "finance_rules")
data class FinanceRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val enabled: Boolean,
    val action: String,
    val direction: String,
    val account: String?,
    val category: String?,
    val descriptionContains: String,
    val minAmountMinor: Long?,
    val maxAmountMinor: Long?,
    val currency: String?,
)

internal fun FinanceTransaction.toEntity() = FinanceTransactionEntity(
    id, occurredAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME), amountMinor, currency, account, card, category,
    description, note, status, bankIncluded, source.name, importKey, inclusion.name,
)

internal fun FinanceTransactionEntity.toModel() = FinanceTransaction(
    id, LocalDateTime.parse(occurredAt), amountMinor, currency, account, card, category, description, note,
    status, bankIncluded, FinanceSource.valueOf(source), importKey, FinanceInclusion.valueOf(inclusion),
)

internal fun FinanceRule.toEntity() = FinanceRuleEntity(
    id, name, enabled, action.name, direction.name, account, category, descriptionContains,
    minAmountMinor, maxAmountMinor, currency,
)

internal fun FinanceRuleEntity.toModel() = FinanceRule(
    id, name, enabled, FinanceInclusion.valueOf(action), FinanceDirection.valueOf(direction), account, category,
    descriptionContains, minAmountMinor, maxAmountMinor, currency,
)
