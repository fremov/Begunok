package ru.begunok.reading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class ReadingStatsTest {
    private val today = LocalDate.of(2026, 10, 29)
    private val utc = ZoneOffset.UTC

    private fun at(day: LocalDate, hour: Int = 12) = day.atTime(hour, 0).toInstant(utc).toEpochMilli()

    @Test
    fun updateCountsOnlyForwardPages() {
        assertEquals(PageUpdate(10, 35, false), ReadingStats.update(10, 300, 35))
        assertEquals(25, ReadingStats.update(10, 300, 35).pages)
        assertEquals(0, ReadingStats.update(50, 300, 40).pages)
        assertEquals(40, ReadingStats.update(50, 300, 40).to)
    }

    @Test
    fun reachingLastPageFinishesBook() {
        val update = ReadingStats.update(280, 300, 320)
        assertEquals(300, update.to)
        assertTrue(update.finished)
        assertFalse(ReadingStats.update(280, 300, 299).finished)
    }

    @Test
    fun pagesGroupedByDayAndLastWeek() {
        val logs = listOf(
            ReadingLog(at(today, 8), 10),
            ReadingLog(at(today, 21), 15),
            ReadingLog(at(today.minusDays(2)), 30),
        )
        val byDay = ReadingStats.pagesByDay(logs, utc)
        assertEquals(25, byDay[today])
        val week = ReadingStats.lastDays(byDay, today)
        assertEquals(7, week.size)
        assertEquals(today, week.last().first)
        assertEquals(listOf(0, 0, 0, 0, 30, 0, 25), week.map { it.second })
    }

    @Test
    fun streakIgnoresEmptyDays() {
        val byDay = mapOf(today.minusDays(1) to 20, today.minusDays(2) to 5, today.minusDays(3) to 0, today.minusDays(4) to 9)
        assertEquals(2, ReadingStats.streak(byDay, today))
        assertEquals(3, ReadingStats.streak(byDay + (today to 1), today))
    }

    @Test
    fun paceUsesDaysSinceFirstReading() {
        val byDay = mapOf(today to 30, today.minusDays(1) to 30, today.minusDays(2) to 30)
        assertEquals(30.0, ReadingStats.pagesPerDay(byDay, today), 0.001)
        assertEquals(4, ReadingStats.daysLeft(100, 30.0))
        assertNull(ReadingStats.daysLeft(100, 0.0))
        assertEquals(0.0, ReadingStats.pagesPerDay(emptyMap(), today), 0.001)
    }

    @Test
    fun paceWindowIsTwoWeeks() {
        val byDay = mapOf(today.minusDays(30) to 1000, today to 140)
        assertEquals(10.0, ReadingStats.pagesPerDay(byDay, today), 0.001)
    }
}
