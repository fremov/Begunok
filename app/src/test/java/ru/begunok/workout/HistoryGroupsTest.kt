package ru.begunok.workout

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.begunok.data.db.WorkoutEntity
import java.time.LocalDate
import java.time.ZoneOffset

class HistoryGroupsTest {
    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 10, 28)

    private fun workout(id: Long, date: LocalDate, km: Double) = WorkoutEntity(
        id = id,
        startTime = date.atStartOfDay(zone).toInstant().toEpochMilli() + 7 * 3_600_000,
        endTime = 1,
        durationMs = 1_000,
        distanceM = km * 1000,
        upperLimit = 145,
    )

    private val workouts = listOf(
        workout(1, LocalDate.of(2026, 10, 26), 2.0),
        workout(2, LocalDate.of(2026, 10, 27), 3.0),
        workout(3, LocalDate.of(2026, 10, 20), 1.0),
        workout(4, LocalDate.of(2026, 9, 30), 4.0),
        workout(5, LocalDate.of(2025, 12, 31), 5.0),
    )

    @Test
    fun weeks() {
        val groups = groupWorkouts(workouts, Grouping.Week, today, zone)
        assertEquals(listOf("Эта неделя", "Прошлая неделя"), groups.take(2).map { it.title })
        assertEquals(listOf(2L, 1L), groups[0].workouts.map { it.id })
        assertEquals(5000.0, groups[0].distanceM, 0.1)
        assertEquals(4, groups.size)
    }

    @Test
    fun monthsAndYears() {
        assertEquals(
            listOf("Октябрь", "Сентябрь", "Декабрь 2025"),
            groupWorkouts(workouts, Grouping.Month, today, zone).map { it.title },
        )
        assertEquals(listOf("2026", "2025"), groupWorkouts(workouts, Grouping.Year, today, zone).map { it.title })
    }
}
