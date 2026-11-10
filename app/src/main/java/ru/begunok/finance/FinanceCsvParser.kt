package ru.begunok.finance

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

object FinanceCsvParser {
    private val dateFormat = DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm:ss", Locale.ROOT)
        .withResolverStyle(ResolverStyle.STRICT)
    private val requiredHeaders = listOf(
        "Имя счёта", "Дата операции", "Сумма в валюте счёта", "Валюта счёта", "Статус",
        "Категория по-умолчанию", "Описание", "Учёт в аналитике",
    )

    fun parse(bytes: ByteArray, fileName: String): FinanceImportPreview {
        val rows = readRows(decode(bytes).removePrefix("\uFEFF"))
        require(rows.isNotEmpty()) { "CSV-файл пуст" }
        val headers = rows.first().fields.map { it.trim() }
        require(headers.size == headers.toSet().size) { "CSV содержит повторяющиеся заголовки" }
        val missing = requiredHeaders.filterNot { it in headers }
        require(missing.isEmpty()) { "Нет обязательных столбцов: ${missing.joinToString()}" }
        val columns = headers.withIndex().associate { it.value to it.index }
        val occurrences = mutableMapOf<String, Int>()
        val transactions = rows.drop(1).map { row ->
            try {
                require(row.fields.size == headers.size) { "Ожидалось ${headers.size} столбцов, получено ${row.fields.size}" }
                fun field(name: String): String = columns[name]?.let { row.fields[it].trim() }.orEmpty()
                val account = field("Имя счёта")
                require(account.isNotBlank()) { "Не указан счёт" }
                val status = field("Статус")
                require(status.isNotBlank()) { "Не указан статус" }
                val amount = FinanceMoney.parseMinor(field("Сумма в валюте счёта"))
                val currency = field("Валюта счёта")
                FinanceMoney.requireCurrency(currency)
                val occurredAt = LocalDateTime.parse(field("Дата операции"), dateFormat)
                val bankIncluded = when (field("Учёт в аналитике").lowercase(Locale.ROOT)) {
                    "да" -> true
                    "нет" -> false
                    else -> throw IllegalArgumentException("Учёт в аналитике должен быть «Да» или «Нет»")
                }
                val originalAmount = field("Сумма операции").let { if (it.isBlank()) "" else FinanceMoney.parseMinor(it).toString() }
                val originalCurrency = field("Валюта операции")
                // The original currency is identity only; the account currency governs stored money.
                if (originalCurrency.isNotBlank()) {
                    require(runCatching { java.util.Currency.getInstance(originalCurrency) }.isSuccess) { "Неизвестная валюта операции" }
                }
                val card = field("Номер карты")
                val description = field("Описание")
                val note = field("Сообщение")
                // Length-prefix each immutable component: delimiters/newlines in descriptions cannot collide.
                // The bank provides no operation ID: identical source rows are distinguished by occurrence ordinal.
                val fingerprint = fingerprint(listOf(account, card, occurredAt.toString(), amount.toString(), currency,
                    description, note, originalAmount, originalCurrency))
                val ordinal = (occurrences[fingerprint] ?: 0) + 1
                occurrences[fingerprint] = ordinal
                FinanceTransaction(
                    occurredAt = occurredAt,
                    amountMinor = amount,
                    currency = currency,
                    account = account,
                    card = card,
                    category = field("Ваша категория").ifBlank { field("Категория по-умолчанию").ifBlank { "Без категории" } },
                    description = description,
                    note = note,
                    status = status,
                    bankIncluded = bankIncluded,
                    source = FinanceSource.Csv,
                    importKey = "$fingerprint:$ordinal",
                )
            } catch (error: Exception) {
                throw IllegalArgumentException("Строка ${row.line}: ${error.message ?: "некорректная операция"}", error)
            }
        }
        require(transactions.isNotEmpty()) { "В CSV нет операций" }
        return FinanceImportPreview(transactions, fileName, transactions.count { !it.status.equals("Ок", ignoreCase = true) })
    }

    private fun decode(bytes: ByteArray): String {
        fun decodeWith(charset: Charset): String = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
        return try {
            decodeWith(Charsets.UTF_8)
        } catch (_: CharacterCodingException) {
            try {
                decodeWith(Charset.forName("windows-1251"))
            } catch (error: CharacterCodingException) {
                throw IllegalArgumentException("Не удалось прочитать CSV в UTF-8 или Windows-1251", error)
            }
        }
    }

    private fun fingerprint(parts: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (part in parts) {
            val bytes = part.toByteArray(Charsets.UTF_8)
            val length = bytes.size
            digest.update(byteArrayOf((length ushr 24).toByte(), (length ushr 16).toByte(), (length ushr 8).toByte(), length.toByte()))
            digest.update(bytes)
        }
        val hex = "0123456789abcdef"
        return buildString(64) {
            for (byte in digest.digest()) {
                val value = byte.toInt() and 255
                append(hex[value ushr 4])
                append(hex[value and 15])
            }
        }
    }

    private data class CsvRow(val fields: List<String>, val line: Int)

    private fun readRows(text: String): List<CsvRow> {
        val rows = mutableListOf<CsvRow>()
        var fields = mutableListOf<String>()
        val value = StringBuilder()
        var quoted = false
        var closedQuote = false
        var fieldStarted = false
        var line = 1
        var rowLine = 1
        var index = 0
        fun finishField() {
            fields.add(value.toString())
            value.setLength(0)
            closedQuote = false
            fieldStarted = false
        }
        fun finishRow() {
            finishField()
            if (fields.size != 1 || fields[0].isNotBlank()) rows.add(CsvRow(fields, rowLine))
            fields = mutableListOf()
        }
        while (index < text.length) {
            val char = text[index]
            if (quoted) {
                when {
                    char == '"' && index + 1 < text.length && text[index + 1] == '"' -> {
                        value.append('"')
                        index++
                    }
                    char == '"' -> {
                        quoted = false
                        closedQuote = true
                    }
                    char == '\r' || char == '\n' -> {
                        value.append('\n')
                        if (char == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
                        line++
                    }
                    else -> value.append(char)
                }
            } else {
                when (char) {
                    ';' -> finishField()
                    '\r', '\n' -> {
                        finishRow()
                        if (char == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
                        line++
                        rowLine = line
                    }
                    '"' -> {
                        require(!fieldStarted && !closedQuote) { "Строка $line: кавычка внутри неэкранированного поля" }
                        quoted = true
                        fieldStarted = true
                    }
                    else -> {
                        require(!closedQuote) { "Строка $line: символы после закрывающей кавычки" }
                        value.append(char)
                        fieldStarted = true
                    }
                }
            }
            index++
        }
        require(!quoted) { "Строка $rowLine: незакрытое поле в кавычках" }
        if (fieldStarted || closedQuote || value.isNotEmpty() || fields.isNotEmpty()) finishRow()
        return rows
    }
}
