package ru.begunok.data

import kotlinx.coroutines.flow.Flow
import ru.begunok.data.db.BookEntity
import ru.begunok.data.db.ReadingDao
import ru.begunok.data.db.ReadingEntryEntity
import ru.begunok.reading.ReadingStats

class ReadingRepository(private val dao: ReadingDao) {
    fun observeBooks(): Flow<List<BookEntity>> = dao.observeBooks()

    fun observeEntries(): Flow<List<ReadingEntryEntity>> = dao.observeEntries()

    suspend fun addBook(title: String, author: String?, totalPages: Int, currentPage: Int) {
        dao.insertBook(
            BookEntity(
                title = title,
                author = author,
                totalPages = totalPages,
                currentPage = currentPage.coerceIn(0, totalPages),
                createdAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun editBook(id: Long, title: String, author: String?, totalPages: Int) {
        val book = dao.book(id) ?: return
        val current = book.currentPage.coerceAtMost(totalPages)
        dao.updateBook(
            book.copy(
                title = title,
                author = author,
                totalPages = totalPages,
                currentPage = current,
                finishedAt = if (current >= totalPages) book.finishedAt ?: System.currentTimeMillis() else null,
            )
        )
    }

    suspend fun setPage(id: Long, page: Int) {
        val book = dao.book(id) ?: return
        val update = ReadingStats.update(book.currentPage, book.totalPages, page)
        val now = System.currentTimeMillis()
        if (update.pages > 0) dao.insertEntry(ReadingEntryEntity(bookId = id, time = now, fromPage = update.from, toPage = update.to))
        dao.updateBook(book.copy(currentPage = update.to, finishedAt = if (update.finished) book.finishedAt ?: now else null))
    }

    suspend fun deleteBook(id: Long) = dao.deleteBook(id)
}
