package ru.begunok.ui.reading

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.begunok.data.ReadingRepository
import ru.begunok.data.SettingsRepository
import ru.begunok.data.db.BookEntity
import ru.begunok.reading.ReadingLog
import ru.begunok.reading.ReadingStats
import java.time.LocalDate

data class BookProgress(val book: BookEntity, val daysLeft: Int?) {
    val fraction: Float get() = if (book.totalPages > 0) book.currentPage.toFloat() / book.totalPages else 0f
}

data class ReadingOverview(
    val goal: Int,
    val today: Int,
    val week: List<Pair<LocalDate, Int>>,
    val streak: Int,
    val pagesPerDay: Double,
    val reading: List<BookProgress>,
    val finished: List<BookEntity>,
) {
    val weekTotal: Int get() = week.sumOf { it.second }
}

class ReadingViewModel(
    private val repository: ReadingRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    val overview: StateFlow<ReadingOverview?> = combine(
        repository.observeBooks(),
        repository.observeEntries(),
        settings.settings,
    ) { books, entries, prefs ->
        val byDay = ReadingStats.pagesByDay(entries.map { ReadingLog(it.time, it.toPage - it.fromPage) })
        val today = LocalDate.now()
        val pace = ReadingStats.pagesPerDay(byDay, today)
        val lastRead = entries.groupBy { it.bookId }.mapValues { (_, list) -> list.maxOf { it.time } }
        ReadingOverview(
            goal = prefs.readingGoal,
            today = byDay[today] ?: 0,
            week = ReadingStats.lastDays(byDay, today),
            streak = ReadingStats.streak(byDay, today),
            pagesPerDay = pace,
            reading = books.filter { it.finishedAt == null }
                .sortedByDescending { lastRead[it.id] ?: it.createdAt }
                .map { BookProgress(it, ReadingStats.daysLeft(it.totalPages - it.currentPage, pace)) },
            finished = books.filter { it.finishedAt != null }.sortedByDescending { it.finishedAt },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun addBook(title: String, author: String?, pages: Int, currentPage: Int) {
        viewModelScope.launch { repository.addBook(title, author, pages, currentPage) }
    }

    fun editBook(id: Long, title: String, author: String?, pages: Int) {
        viewModelScope.launch { repository.editBook(id, title, author, pages) }
    }

    fun setPage(id: Long, page: Int) {
        viewModelScope.launch { repository.setPage(id, page) }
    }

    fun deleteBook(id: Long) {
        viewModelScope.launch { repository.deleteBook(id) }
    }

    fun setGoal(pages: Int) {
        viewModelScope.launch { settings.setReadingGoal(pages.coerceIn(MIN_GOAL, MAX_GOAL)) }
    }

    companion object {
        const val MIN_GOAL = 5
        const val MAX_GOAL = 200
        const val GOAL_STEP = 5
    }
}
