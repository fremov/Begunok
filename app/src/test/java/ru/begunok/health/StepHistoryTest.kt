package ru.begunok.health

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class StepHistoryTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val today = LocalDate.of(2026, 11, 14)
    private fun LocalDate.instant(): Instant = atStartOfDay(zone).toInstant()

    @Test
    fun oldHistoryFailureDoesNotEraseTodaysStepsOrPretendHistoryIsEmpty() = runBlocking {
        val current = DailySteps(today, 8432)
        val state = readStepHistory(today.minusYears(1).instant(), today.plusDays(1).instant(), zone, false, today) { from, _ ->
            if (from < today.minusDays(29).instant()) throw HealthReadException("history denied")
            listOf(current)
        }
        assertEquals(listOf(current), state.days)
        assertTrue(state.failed)
        assertNotNull(state.message)
    }

    @Test
    fun grantedHistoryRetainsOlderThanThirtyDays() = runBlocking {
        val old = DailySteps(today.minusDays(200), 5000)
        val current = DailySteps(today, 8432)
        val state = readStepHistory(today.minusYears(1).instant(), today.plusDays(1).instant(), zone, true, today) { _, _ ->
            listOf(old, current)
        }
        assertEquals(listOf(old, current), state.days)
        assertFalse(state.failed)
        assertEquals(null, state.message)
    }

    @Test
    fun defaultAccessDoesNotHidePreviouslyReadableDaysAndDoesNotDoubleCountBoundaryDay() = runBlocking {
        val boundary = today.minusDays(29)
        val source = listOf(
            DailySteps(today.minusDays(100), 1234),
            DailySteps(boundary.minusDays(1), 2000),
            DailySteps(boundary, 3000),
            DailySteps(today, 4567),
        )
        val state = readStepHistory(today.minusYears(1).instant(), today.plusDays(1).instant(), zone, false, today) { from, until ->
            source.filter { it.day.instant() >= from && it.day.instant() < until }
        }
        assertEquals(source, state.days)
        assertEquals(10801L, state.days.sumOf { it.count })
        assertFalse(state.failed)
        assertNotNull(state.message)
    }

    @Test
    fun daylightSavingTransitionKeepsEachCalendarDayOnce() = runBlocking {
        val source = (0L..65L).map { DailySteps(today.minusDays(it), it + 1) }.sortedBy { it.day }
        val state = readStepHistory(today.minusDays(65).instant(), today.plusDays(1).instant(), zone, false, today) { from, until ->
            source.filter { it.day.instant() >= from && it.day.instant() < until }
        }
        assertEquals(source, state.days)
    }

    @Test
    fun noRecordedStepsStaysAbsentRatherThanBecomingAZeroMeasurement() = runBlocking {
        val state = readStepHistory(today.instant(), today.plusDays(1).instant(), zone, true, today) { _, _ -> emptyList() }
        assertTrue(state.days.isEmpty())
        assertFalse(state.failed)
        assertEquals(null, state.message)
    }

    @Test
    fun recentReadFailureIsNotReportedAsNoData() = runBlocking {
        try {
            readStepHistory(today.instant(), today.plusDays(1).instant(), zone, true, today) { _, _ ->
                throw HealthReadException("service unavailable")
            }
            throw AssertionError("Expected an explicit read error")
        } catch (error: HealthReadException) {
            assertEquals("service unavailable", error.message)
        }
    }
}
