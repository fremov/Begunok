package ru.begunok.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.begunok.data.db.ColdShowerEntity
import ru.begunok.data.db.ReadingEntryEntity
import ru.begunok.data.db.WorkoutEntity
import ru.begunok.health.DailySteps
import ru.begunok.math.MathActivityEvent
import ru.begunok.math.MathActivityKind
import ru.begunok.math.MathDayActivity
import ru.begunok.workout.Period
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.ZoneOffset

class DailyActivityTest {
    private val today = LocalDate.of(2026, 11, 14)
    private val zone = ZoneOffset.UTC

    private fun millis(day: LocalDate, zone: ZoneId = this.zone): Long =
        day.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun workout(day: LocalDate, kind: String = "Run", finished: Boolean = true) =
        WorkoutEntity(
            startTime = millis(day),
            endTime = if (finished) millis(day.plusDays(1)) else null,
            upperLimit = 145,
            dayType = kind,
        )

    private fun reading(day: LocalDate, from: Int, to: Int) =
        ReadingEntryEntity(bookId = 1, time = millis(day), fromPage = from, toPage = to)

    private fun shower(day: LocalDate, completed: Boolean = true) =
        ColdShowerEntity(time = millis(day), durationSec = 30, targetSec = 60, completed = completed)

    private fun event(day: LocalDate, kind: MathActivityKind, lesson: String = "topic") =
        MathActivityEvent(day, millis(day), lesson, kind)

    private fun build(
        workouts: List<WorkoutEntity> = emptyList(),
        reading: List<ReadingEntryEntity> = emptyList(),
        learning: List<MathActivityEvent> = emptyList(),
        showers: List<ColdShowerEntity> = emptyList(),
        steps: List<DailySteps> = emptyList(),
        period: Period = Period.All,
        zone: ZoneId = this.zone,
    ) = DailyActivity.build(workouts, reading, learning, showers, steps, today, period.start(today), zone)

    @Test
    fun mixedSourcesSumWithinDay() {
        val rows = build(
            workouts = listOf(workout(today), workout(today, "Strength")),
            reading = listOf(reading(today, 0, 12), reading(today, 12, 19), reading(today, 19, 10), reading(today, 10, 10)),
            learning = listOf(
                event(today, MathActivityKind.Topic),
                event(today, MathActivityKind.Topic),
                event(today, MathActivityKind.Task),
                event(today, MathActivityKind.Task),
                event(today, MathActivityKind.Review),
            ),
            showers = listOf(shower(today), shower(today, completed = false).copy(durationSec = 45)),
            steps = listOf(DailySteps(today, 12_345)),
        )
        assertEquals(
            DailyActivityRow(
                day = today,
                workouts = 2,
                study = MathDayActivity(topics = 1, tasks = 2, reviews = 1),
                pages = 19,
                showerDurationSec = 75L,
                steps = 12_345,
            ),
            rows.single(),
        )
    }

    @Test
    fun finitePeriodsIncludeBoundaryAndUseLocalDateAtMidnight() {
        val localZone = ZoneId.of("Europe/Moscow")
        for (period in listOf(Period.Month, Period.Quarter, Period.Year)) {
            val start = requireNotNull(period.start(today))
            val boundary = millis(start, localZone)
            val endOfToday = millis(today.plusDays(1), localZone) - 1
            val rows = build(
                workouts = listOf(
                    workout(today).copy(startTime = boundary - 1),
                    workout(today).copy(startTime = boundary),
                    workout(today).copy(startTime = endOfToday),
                ),
                reading = listOf(reading(today, 0, 5).copy(time = boundary)),
                showers = listOf(shower(today).copy(time = boundary)),
                period = period,
                zone = localZone,
            )
            assertEquals(today, rows.first().day)
            assertEquals(start, rows.last().day)
            assertEquals(1, rows.first().workouts)
            assertEquals(1, rows.last().workouts)
            assertEquals(5, rows.last().pages)
            assertEquals(30L, rows.last().showerDurationSec)
            assertEquals(2, rows.sumOf { it.workouts })
            assertEquals(ChronoUnit.DAYS.between(start, today) + 1, rows.size.toLong())
        }
    }

    @Test
    fun futureActivityAndUnfinishedWorkoutsDoNotExtendHistory() {
        val tomorrow = today.plusDays(1)
        val rows = build(
            workouts = listOf(workout(today.minusYears(2), finished = false), workout(tomorrow)),
            reading = listOf(reading(tomorrow, 0, 20), reading(today.minusYears(3), 10, 5)),
            learning = listOf(event(tomorrow, MathActivityKind.Topic)),
            showers = listOf(shower(tomorrow)),
            steps = listOf(DailySteps(tomorrow, 123)),
        )
        assertEquals(
            listOf(DailyActivityRow(today)),
            rows,
        )
    }

    @Test
    fun firstTopicBeforeCutoffIsNotRecountedInsidePeriod() {
        val start = requireNotNull(Period.Month.start(today))
        val rows = build(
            learning = listOf(
                event(today, MathActivityKind.Topic),
                event(start.minusDays(1), MathActivityKind.Topic),
                event(today, MathActivityKind.Task),
                event(today, MathActivityKind.Review),
            ),
            period = Period.Month,
        )
        assertEquals(MathDayActivity(tasks = 1, reviews = 1), rows.first().study)
        assertEquals(0, rows.sumOf { it.study.topics })
    }

    @Test
    fun missingStepsStayNullAndRecordedZeroIsPreserved() {
        assertNull(build().single().steps)
        val available = build(steps = listOf(DailySteps(today.minusDays(1), 0)))
        assertNull(available.first().steps)
        assertEquals(0L, available.last().steps)
    }

    @Test
    fun allHistoryUsesEarliestValidSourceAndIncludesBlankDaysBeyondTwentySixWeeks() {
        val oldest = today.minusWeeks(40)
        val sources = listOf(
            build(workouts = listOf(workout(oldest))),
            build(reading = listOf(reading(oldest, 0, 1))),
            build(learning = listOf(event(oldest, MathActivityKind.Topic))),
            build(showers = listOf(shower(oldest, completed = false))),
            build(steps = listOf(DailySteps(oldest, 0))),
        )
        for (rows in sources) {
            assertEquals(281, rows.size)
            assertEquals(today, rows.first().day)
            assertEquals(oldest, rows.last().day)
            assertEquals(
                DailyActivityRow(today.minusDays(1)),
                rows[1],
            )
            assertEquals((0L..280L).map { today.minusDays(it) }, rows.map { it.day })
        }
    }
}
