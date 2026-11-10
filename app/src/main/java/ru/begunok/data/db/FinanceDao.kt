package ru.begunok.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class FinanceDao {
    @Query("SELECT * FROM finance_transactions ORDER BY occurredAt DESC, id DESC")
    abstract fun observeTransactions(): Flow<List<FinanceTransactionEntity>>

    @Query("SELECT * FROM finance_rules ORDER BY id DESC")
    abstract fun observeRules(): Flow<List<FinanceRuleEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertImported(rows: List<FinanceTransactionEntity>): List<Long>

    @Query("UPDATE finance_transactions SET status = :status, bankIncluded = :bankIncluded WHERE importKey = :importKey AND source = 'Csv'")
    protected abstract suspend fun refreshImportedMetadata(importKey: String, status: String, bankIncluded: Boolean)

    @Transaction
    open suspend fun importTransactions(rows: List<FinanceTransactionEntity>): Int {
        val insertedIds = insertImported(rows)
        var added = 0
        rows.forEachIndexed { index, row ->
            if (insertedIds[index] == -1L) {
                // A bank correction may complete an earlier failed operation, but never erase local edits.
                refreshImportedMetadata(requireNotNull(row.importKey), row.status, row.bankIncluded)
            } else {
                added++
            }
        }
        return added
    }

    @Insert
    abstract suspend fun insertManual(row: FinanceTransactionEntity): Long

    // Keep guards inside SQL so an edit/delete cannot race or convert a bank operation.
    @Query("""
        UPDATE finance_transactions SET occurredAt = :occurredAt, amountMinor = :amountMinor,
            currency = :currency, account = :account, card = :card, category = :category,
            description = :description, note = :note, status = :status, bankIncluded = :bankIncluded,
            inclusion = :inclusion
        WHERE id = :id AND source = 'Manual'
    """)
    abstract suspend fun updateManual(
        id: Long, occurredAt: String, amountMinor: Long, currency: String, account: String, card: String,
        category: String, description: String, note: String, status: String, bankIncluded: Boolean, inclusion: String,
    ): Int

    @Query("DELETE FROM finance_transactions WHERE id = :id AND source = 'Manual'")
    abstract suspend fun deleteManual(id: Long): Int

    @Query("UPDATE finance_transactions SET inclusion = :inclusion WHERE id = :id")
    abstract suspend fun setInclusion(id: Long, inclusion: String): Int

    @Query("UPDATE finance_transactions SET category = :category WHERE id = :id")
    abstract suspend fun setCategory(id: Long, category: String): Int

    @Insert
    abstract suspend fun insertRule(row: FinanceRuleEntity): Long

    @Query("""
        UPDATE finance_rules SET name = :name, enabled = :enabled, action = :action, direction = :direction,
            account = :account, category = :category, descriptionContains = :descriptionContains,
            minAmountMinor = :minAmountMinor, maxAmountMinor = :maxAmountMinor, currency = :currency
        WHERE id = :id
    """)
    abstract suspend fun updateRule(
        id: Long, name: String, enabled: Boolean, action: String, direction: String, account: String?,
        category: String?, descriptionContains: String, minAmountMinor: Long?, maxAmountMinor: Long?, currency: String?,
    ): Int

    @Query("DELETE FROM finance_rules WHERE id = :id")
    abstract suspend fun deleteRule(id: Long): Int
}
