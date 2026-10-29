package ru.begunok.reading

import ru.begunok.habit.Streaks
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.ceil

data class ReadingLog(val time: Long, val pages: Int)

data class PageUpdate(val from: Int, val to: Int, val finished: Boolean) {
    val pages: Int get() = (to - from).coerceAtLeast(0)
}

object ReadingStats {
    const val DEFAULT_GOAL = 20
    const val WEEK_DAYS = 7
    private const val PACE_WINDOW_DAYS = 14

    fun update(current: Int, total: Int, newPage: Int): PageUpdate {
        val page = newPage.coerceIn(0, total)
        return PageUpdate(current, page, total > 0 && page >= total)
    }

    fun pagesByDay(logs: List<ReadingLog>, zone: ZoneId = ZoneId.systemDefault()): Map<LocalDate, Int> =
        logs.groupBy { Streaks.dayOf(it.time, zone) }.mapValues { (_, day) -> day.sumOf { it.pages } }

    fun lastDays(byDay: Map<LocalDate, Int>, today: LocalDate, count: Int = WEEK_DAYS): List<Pair<LocalDate, Int>> =
        (count - 1 downTo 0).map { today.minusDays(it.toLong()) }.map { it to (byDay[it] ?: 0) }

    fun streak(byDay: Map<LocalDate, Int>, today: LocalDate): Int =
        Streaks.consecutiveDays(byDay.filterValues { it > 0 }.keys, today)

    fun pagesPerDay(byDay: Map<LocalDate, Int>, today: LocalDate): Double {
        val first = byDay.filterValues { it > 0 }.keys.minOrNull() ?: return 0.0
        val window = (ChronoUnit.DAYS.between(first, today) + 1).coerceIn(1, PACE_WINDOW_DAYS.toLong())
        val pages = (0 until window).sumOf { byDay[today.minusDays(it)] ?: 0 }
        return pages.toDouble() / window
    }

    fun daysLeft(remainingPages: Int, pagesPerDay: Double): Int? =
        if (remainingPages <= 0 || pagesPerDay <= 0.0) null else ceil(remainingPages / pagesPerDay).toInt()
}
