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
import ru.begunok.workout.Analytics
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
        games: List<DailyGameActivity>? = emptyList(),
        steps: List<DailySteps> = emptyList(),
        period: Period = Period.All,
        zone: ZoneId = this.zone,
    ) = DailyActivity.build(workouts, reading, learning, showers, games, steps, today, period, zone)

    @Test
    fun mixedSourcesSumWithinDayAndDeduplicateGames() {
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
            games = listOf(
                DailyGameActivity("one", millis(today), Int.MAX_VALUE),
                DailyGameActivity("one", millis(today), Int.MAX_VALUE),
                DailyGameActivity("two", millis(today), Int.MAX_VALUE),
            ),
            steps = listOf(DailySteps(today, 12_345)),
        )
        assertEquals(
            DailyActivityRow(
                day = today,
                workouts = 2,
                study = MathDayActivity(topics = 1, tasks = 2, reviews = 1),
                pages = 19,
                games = 2,
                gameDurationSec = 2L * Int.MAX_VALUE,
                showerDurationSec = 75L,
                steps = 12_345,
            ),
            rows.single(),
        )
    }

    @Test
    fun allQueuesIncludeStandardAndChaosAramWithoutDoubleCounting() {
        val row = build(
            games = listOf(
                DailyGameActivity("regular", millis(today), 1_800, queueId = 400),
                DailyGameActivity("aram", millis(today), 1_200, queueId = 450),
                DailyGameActivity("chaos", millis(today), 900, queueId = 2400),
                DailyGameActivity("aram", millis(today), 1_200, queueId = 450),
                DailyGameActivity("chaos", millis(today), 900, queueId = 2400),
            ),
        ).single()

        assertEquals(3, row.games)
        assertEquals(3_900L, row.gameDurationSec)
    }

    @Test
    fun finitePeriodsIncludeBoundaryAndUseLocalDateAtMidnight() {
        val localZone = ZoneId.of("Europe/Moscow")
        for (period in listOf(Period.Month, Period.Quarter)) {
            val start = Analytics.periodStart(today, period)
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
                games = listOf(DailyGameActivity("boundary", boundary, 60, queueId = 2400)),
                period = period,
                zone = localZone,
            )
            assertEquals(today, rows.first().day)
            assertEquals(start, rows.last().day)
            assertEquals(1, rows.first().workouts)
            assertEquals(1, rows.last().workouts)
            assertEquals(5, rows.last().pages)
            assertEquals(30L, rows.last().showerDurationSec)
            assertEquals(1, rows.last().games)
            assertEquals(60L, rows.last().gameDurationSec)
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
            games = listOf(DailyGameActivity("future", millis(tomorrow), 60, queueId = 450)),
            steps = listOf(DailySteps(tomorrow, 123)),
        )
        assertEquals(
            listOf(DailyActivityRow(today, games = 0, gameDurationSec = 0L)),
            rows,
        )
    }

    @Test
    fun firstTopicBeforeCutoffIsNotRecountedInsidePeriod() {
        val start = Analytics.periodStart(today, Period.Month)
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
    fun unavailableSourcesRemainNullAndKnownEmptyGamesAreZero() {
        val unavailable = build(games = null).single()
        assertNull(unavailable.games)
        assertNull(unavailable.gameDurationSec)
        assertNull(unavailable.steps)
        val available = build(steps = listOf(DailySteps(today.minusDays(1), 0)))
        assertEquals(0, available.first().games)
        assertEquals(0L, available.first().gameDurationSec)
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
            build(games = listOf(DailyGameActivity("old", millis(oldest), 60))),
            build(steps = listOf(DailySteps(oldest, 0))),
        )
        for (rows in sources) {
            assertEquals(281, rows.size)
            assertEquals(today, rows.first().day)
            assertEquals(oldest, rows.last().day)
            assertEquals(
                DailyActivityRow(today.minusDays(1), games = 0, gameDurationSec = 0L),
                rows[1],
            )
            assertEquals((0L..280L).map { today.minusDays(it) }, rows.map { it.day })
        }
    }
}
