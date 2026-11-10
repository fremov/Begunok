package ru.begunok.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.begunok.data.db.FinanceDao
import ru.begunok.data.db.toEntity
import ru.begunok.data.db.toModel
import ru.begunok.finance.FinanceImportPreview
import ru.begunok.finance.FinanceInclusion
import ru.begunok.finance.FinanceMoney
import ru.begunok.finance.FinanceRule
import ru.begunok.finance.FinanceRules
import ru.begunok.finance.FinanceSource
import ru.begunok.finance.FinanceTransaction

data class FinanceImportReport(val added: Int, val duplicates: Int, val total: Int, val fileName: String)

class FinanceRepository(private val dao: FinanceDao) {
    fun observeTransactions(): Flow<List<FinanceTransaction>> = dao.observeTransactions().map { rows -> rows.map { it.toModel() } }

    fun observeRules(): Flow<List<FinanceRule>> = dao.observeRules().map { rows -> rows.map { it.toModel() } }

    suspend fun importCsv(preview: FinanceImportPreview): FinanceImportReport {
        require(preview.transactions.isNotEmpty()) { "Нет операций для импорта" }
        val keys = HashSet<String>()
        // Complete validation happens before entering the atomic DAO insertion.
        val rows = preview.transactions.map { transaction ->
            validateTransaction(transaction)
            require(transaction.id == 0L && transaction.source == FinanceSource.Csv) { "Импортировать можно только новые операции CSV" }
            val key = transaction.importKey
            require(!key.isNullOrBlank() && keys.add(key)) { "Некорректный или повторяющийся ключ импортируемой операции" }
            require(transaction.inclusion == FinanceInclusion.Auto) { "Импорт не должен содержать ручные переопределения" }
            transaction.toEntity()
        }
        val added = dao.importTransactions(rows)
        return FinanceImportReport(added, rows.size - added, rows.size, preview.fileName)
    }

    suspend fun saveManual(transaction: FinanceTransaction) {
        validateTransaction(transaction)
        require(transaction.source == FinanceSource.Manual && transaction.importKey == null) { "Операцию CSV нельзя изменить как ручную" }
        val row = transaction.toEntity()
        if (row.id == 0L) {
            dao.insertManual(row)
        } else {
            require(dao.updateManual(row.id, row.occurredAt, row.amountMinor, row.currency, row.account, row.card,
                row.category, row.description, row.note, row.status, row.bankIncluded, row.inclusion) == 1) {
                "Ручная операция не найдена; импортированные операции недоступны для такого изменения"
            }
        }
    }

    suspend fun deleteManual(id: Long) {
        require(id > 0) { "Некорректный идентификатор операции" }
        require(dao.deleteManual(id) == 1) { "Удалять можно только существующие ручные операции" }
    }

    suspend fun setInclusion(id: Long, inclusion: FinanceInclusion) {
        require(id > 0) { "Некорректный идентификатор операции" }
        require(dao.setInclusion(id, inclusion.name) == 1) { "Операция не найдена" }
    }

    suspend fun setCategory(id: Long, category: String) {
        require(id > 0) { "Некорректный идентификатор операции" }
        require(category.isNotBlank()) { "Укажите категорию" }
        require(dao.setCategory(id, category.trim()) == 1) { "Операция не найдена" }
    }

    suspend fun saveRule(rule: FinanceRule) {
        FinanceRules.validate(rule)
        val row = rule.toEntity()
        if (row.id == 0L) {
            dao.insertRule(row)
        } else {
            require(dao.updateRule(row.id, row.name, row.enabled, row.action, row.direction, row.account, row.category,
                row.descriptionContains, row.minAmountMinor, row.maxAmountMinor, row.currency) == 1) { "Правило не найдено" }
        }
    }

    suspend fun deleteRule(id: Long) {
        require(id > 0) { "Некорректный идентификатор правила" }
        require(dao.deleteRule(id) == 1) { "Правило не найдено" }
    }

    private fun validateTransaction(transaction: FinanceTransaction) {
        require(transaction.id >= 0) { "Некорректный идентификатор операции" }
        require(transaction.amountMinor != 0L || transaction.source == FinanceSource.Csv) { "Сумма ручной операции должна отличаться от нуля" }
        FinanceMoney.requireCurrency(transaction.currency)
        require(transaction.account.isNotBlank()) { "Укажите счёт" }
        require(transaction.category.isNotBlank()) { "Укажите категорию" }
        require(transaction.status.isNotBlank()) { "Не указан статус операции" }
    }
}
