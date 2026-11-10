package ru.begunok.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset
import java.time.LocalDateTime

class FinanceCsvParserTest {
    private val headers = listOf("Имя счёта", "Номер карты", "Дата операции", "Сумма операции", "Валюта операции",
        "Сумма в валюте счёта", "Валюта счёта", "Статус", "Категория по-умолчанию", "Ваша категория",
        "Описание", "Сообщение", "Округление", "Сумма операции с округлением", "Бонусы (включая кэшбэк)", "Учёт в аналитике")

    private fun row(vararg changes: Pair<String, String>): Map<String, String> = mapOf(
        "Имя счёта" to "Основной", "Номер карты" to "*1234", "Дата операции" to "10.11.2026 12:34:56",
        "Сумма операции" to "-10,23", "Валюта операции" to "RUB", "Сумма в валюте счёта" to "-10,23",
        "Валюта счёта" to "RUB", "Статус" to "Ок", "Категория по-умолчанию" to "Магазины",
        "Описание" to "Магазин", "Округление" to "-9,77", "Сумма операции с округлением" to "-20,00",
        "Бонусы (включая кэшбэк)" to "100,00", "Учёт в аналитике" to "Да",
    ) + changes

    private fun csv(vararg rows: Map<String, String>, columns: List<String> = headers): String {
        fun quote(value: String) = "\"${value.replace("\"", "\"\"")}\""
        return (listOf(columns) + rows.map { row -> columns.map { row[it].orEmpty() } })
            .joinToString("\r\n") { fields -> fields.joinToString(";") { quote(it) } }
    }

    private fun parse(vararg rows: Map<String, String>) = FinanceCsvParser.parse(csv(*rows).toByteArray(), "test.csv")

    private fun rejected(text: String): IllegalArgumentException {
        try {
            FinanceCsvParser.parse(text.toByteArray(), "bad.csv")
            throw AssertionError("Expected CSV rejection")
        } catch (error: IllegalArgumentException) {
            return error
        }
    }

    @Test
    fun quotedUtf8BomMultilineAndAccountAmountArePreserved() {
        val preview = FinanceCsvParser.parse(("\uFEFF" + csv(row(
            "Описание" to "Кофе; \"двойной\"\r\nВторая строка", "Сообщение" to "Личная заметка",
            "Ваша категория" to "Кофе", "Сумма операции" to "-1,00", "Валюта операции" to "USD",
        ))).toByteArray(), "операции.csv")
        val transaction = preview.transactions.single()
        assertEquals(-1023L, transaction.amountMinor)
        assertEquals("RUB", transaction.currency)
        assertEquals("Кофе; \"двойной\"\nВторая строка", transaction.description)
        assertEquals("Личная заметка", transaction.note)
        assertEquals("Кофе", transaction.category)
        assertEquals(LocalDateTime.of(2026, 11, 10, 12, 34, 56), transaction.occurredAt)
        assertEquals(FinanceSource.Csv, transaction.source)
        assertEquals(0, preview.failedCount)
        assertEquals("операции.csv", preview.fileName)
    }

    @Test
    fun windows1251AndOptionalColumnsAreSupported() {
        val text = csv(row("Категория по-умолчанию" to ""), columns = headers.filterNot {
            it in listOf("Ваша категория", "Номер карты", "Сообщение", "Сумма операции", "Валюта операции", "Округление",
                "Сумма операции с округлением", "Бонусы (включая кэшбэк)")
        })
        val transaction = FinanceCsvParser.parse(text.toByteArray(Charset.forName("windows-1251")), "old.csv").transactions.single()
        assertEquals("Основной", transaction.account)
        assertEquals("Без категории", transaction.category)
        assertEquals("", transaction.card)
    }

    @Test
    fun duplicateMultiplicityAndReimportsHaveStableKeys() {
        val first = parse(row(), row(), row("Описание" to "Другой магазин"))
        val repeated = parse(row(), row(), row("Описание" to "Другой магазин"))
        val keys = first.transactions.map { it.importKey }
        assertEquals(3, keys.toSet().size)
        assertEquals(keys, repeated.transactions.map { it.importKey })
        assertTrue(keys[0]!!.endsWith(":1"))
        assertTrue(keys[1]!!.endsWith(":2"))
        val overlap = parse(row("Описание" to "Другой магазин"), row())
        assertEquals(setOf(keys[0], keys[2]), overlap.transactions.map { it.importKey }.toSet())
    }

    @Test
    fun editableMetadataDoesNotChangeImportIdentity() {
        val original = parse(row()).transactions.single()
        val edited = parse(row("Ваша категория" to "Своя", "Учёт в аналитике" to "Нет", "Статус" to "Ошибка")).transactions.single()
        assertEquals(original.importKey, edited.importKey)
        assertNotEquals(original.importKey, parse(row("Сообщение" to "Другой текст")).transactions.single().importKey)
        assertNotEquals(original.importKey, parse(row("Номер карты" to "*9999")).transactions.single().importKey)
        assertNotEquals(original.importKey, parse(row("Сумма операции" to "-9,00")).transactions.single().importKey)
    }

    @Test
    fun numericFormattingDoesNotChangeIdentity() {
        assertEquals(parse(row()).transactions.single().importKey,
            parse(row("Сумма операции" to "-010.23", "Сумма в валюте счёта" to "-010.23")).transactions.single().importKey)
    }

    @Test
    fun failedZeroOperationsRemainVisibleButCannotBeIncluded() {
        val preview = parse(row("Сумма операции" to "0,00", "Сумма в валюте счёта" to "0,00",
            "Статус" to "Ошибка", "Учёт в аналитике" to "Нет"), row())
        assertEquals(2, preview.transactions.size)
        assertEquals(1, preview.failedCount)
        val failed = preview.transactions.first()
        assertEquals(0L, failed.amountMinor)
        assertFalse(FinanceRules.evaluate(failed.copy(inclusion = FinanceInclusion.Include), emptyList()).included)
    }

    @Test
    fun invalidRowRejectsWholeFileWithItsLine() {
        val error = rejected(csv(row(), row("Сумма в валюте счёта" to "12,345")))
        assertTrue(error.message.orEmpty().contains("Строка 3"))
    }

    @Test
    fun datesAreStrictAndLocal() {
        for (date in listOf("29.02.2025 12:00:00", "31.04.2026 12:00:00", "10.11.2026 24:00:00", "10.11.2026 12:00:00Z")) {
            rejected(csv(row("Дата операции" to date)))
        }
        assertEquals(29, parse(row("Дата операции" to "29.02.2024 23:59:59")).transactions.single().occurredAt.dayOfMonth)
    }

    @Test
    fun malformedFlagsCurrencyHeadersAndRowsAreRejected() {
        rejected(csv(row("Учёт в аналитике" to "Возможно")))
        rejected(csv(row("Валюта счёта" to "JPY")))
        rejected(csv(row("Валюта счёта" to "XXX")))
        rejected(csv(row("Имя счёта" to "")))
        rejected(csv(row(), columns = headers.filterNot { it == "Статус" }))
        rejected(csv(row(), columns = headers + "Статус"))
        rejected(csv(row()) + "\r\nshort;row")
        rejected(csv(row()) + "\r\n\"незакрыто")
        rejected(csv(row()) + "\r\n\"поле\"extra")
        rejected("")
        rejected(headers.joinToString(";"))
    }

    @Test
    fun undecodableBytesNeverBecomeReplacementCharacters() {
        try {
            FinanceCsvParser.parse(byteArrayOf(0x98.toByte()), "bad.csv")
            throw AssertionError("Expected strict decoding failure")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("UTF-8"))
        }
    }
}
