package ru.begunok.mental

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.begunok.workout.Period
import java.time.LocalDate
import java.time.ZoneId

class MentalAnalyticsTest {
    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 11, 10)

    @Test
    fun multipleEntriesArePreservedChronologicallyButDaysHaveEqualWeight() {
        val busyDay = (0..9).map { entry("2026-11-08", it, 0, id = it.toLong()) }
        val otherDay = entry("2026-11-10", 9, 10, id = 100)
        val result = build(listOf(otherDay) + busyDay.reversed())

        assertEquals(11, result.entries.size)
        assertEquals(11, result.summary.entries)
        assertEquals(2, result.summary.observedDays)
        assertEquals(5.0, result.summary.averages!!.mood, 0.0001)
        assertEquals(5.0, result.summary.averages!!.tension, 0.0001)
        assertEquals(5.0, result.summary.averages!!.energy, 0.0001)
        assertEquals(10, result.daily.first().entries)
        assertTrue(result.entries.zipWithNext().all { (a, b) -> a.completedAt <= b.completedAt })
    }

    @Test
    fun missingDaysAreNotZeroButRecordedZeroCounts() {
        val result = build(listOf(entry("2026-10-19", 9, 0), entry("2026-11-10", 9, 8)))

        assertEquals(2, result.daily.size)
        assertEquals(2, result.summary.observedDays)
        assertEquals(4.0, result.summary.averages!!.mood, 0.0001)
        assertNull(result.previous!!.averages)
        val empty = build(emptyList())
        assertEquals(0, empty.summary.observedDays)
        assertNull(empty.summary.averages)
    }

    @Test
    fun currentAndEqualPrecedingPeriodsAreHalfOpenAndDoNotOverlap() {
        val start = millis("2026-10-19")
        val end = millis("2026-11-11")
        val previousStart = millis("2026-09-26")
        val entries = listOf(
            at(previousStart - 1, 10),
            at(previousStart, 2),
            at(start - 1, 4),
            at(start, 6),
            at(end - 1, 8),
            at(end, 10),
        )
        val result = build(entries)

        assertEquals(LocalDate.of(2026, 10, 19), result.start)
        assertEquals(LocalDate.of(2026, 9, 26), result.previousStart)
        assertEquals(LocalDate.of(2026, 11, 11), result.endExclusive)
        assertEquals(listOf(start, end - 1), result.entries.map { it.completedAt })
        assertEquals(7.0, result.summary.averages!!.mood, 0.0001)
        assertEquals(3.0, result.previous!!.averages!!.mood, 0.0001)
        assertEquals(2, result.previous!!.entries)
    }

    @Test
    fun comparisonUsesDailyMeansInBothPeriods() {
        val currentBusy = (0..8).map { entry("2026-11-09", it, 10) }
        val previousBusy = (0..8).map { entry("2026-10-15", it, 0) }
        val result = build(currentBusy + entry("2026-11-10", 9, 0) + previousBusy + entry("2026-10-16", 9, 8))

        assertEquals(5.0, result.summary.averages!!.mood, 0.0001)
        assertEquals(4.0, result.previous!!.averages!!.mood, 0.0001)
        assertEquals(1.0, (result.summary.averages!! - result.previous!!.averages!!).mood, 0.0001)
    }

    @Test
    fun quarterUsesExistingWeekAlignedBoundaryAndAllIncludesOlderHistory() {
        val old = entry("2020-01-01", 9, 7)
        val boundary = entry("2026-08-17", 0, 3)
        val before = at(boundary.completedAt - 1, 9)
        val quarter = MentalAnalytics.build(listOf(old, before, boundary), emptyList(), today, Period.Quarter, zone)
        assertEquals(LocalDate.of(2026, 8, 17), quarter.start)
        assertEquals(listOf(boundary), quarter.entries)

        val all = build(listOf(old, boundary), period = Period.All)
        assertEquals(2, all.summary.entries)
        assertNull(all.start)
        assertNull(all.previous)
    }

    @Test
    fun localDayBoundariesAndComparisonStayCalendarBasedAcrossDaylightSaving() {
        val berlin = ZoneId.of("Europe/Berlin")
        val firstDay = LocalDate.of(2026, 3, 9)
        val before = firstDay.atStartOfDay(berlin).toInstant().toEpochMilli() - 1
        val start = before + 1
        val result = MentalAnalytics.build(
            listOf(at(before, 2), at(start, 8)), emptyList(), LocalDate.of(2026, 3, 31), Period.Month, berlin,
        )
        assertEquals(firstDay, result.start)
        assertEquals(LocalDate.of(2026, 2, 14), result.previousStart)
        assertEquals(listOf(start), result.entries.map { it.completedAt })
        assertEquals(2.0, result.previous!!.averages!!.mood, 0.0001)
        assertEquals(firstDay, result.daily.single().day)
    }

    @Test
    fun daypartComparisonUsesOnlyPairedDaysAndEqualDailyWeight() {
        val entries = (6..11).map { entry("2026-11-08", it, 10) } + listOf(
            entry("2026-11-08", 18, 2),
            entry("2026-11-09", 6, 0),
            entry("2026-11-09", 23, 4),
            entry("2026-11-10", 9, 10), // No evening: this day must not bias the comparison.
            entry("2026-11-09", 12, 10), // Neither morning nor evening.
            entry("2026-11-09", 5, 10),
        )
        val comparison = build(entries).dayparts!!
        assertEquals(2, comparison.pairedDays)
        assertEquals(7, comparison.morning.entries)
        assertEquals(2, comparison.evening.entries)
        assertEquals(5.0, comparison.morning.averages!!.mood, 0.0001)
        assertEquals(3.0, comparison.evening.averages!!.mood, 0.0001)
        assertNull(build(entries.filter { it.completedAt < millis("2026-11-09") }).dayparts)
    }

    @Test
    fun questionnaireSeriesNeverMixScalesAndCompareWithPrecedingResultOutsidePeriod() {
        val whoOld = result(MentalTest.WHO5, "2026-10-18", 1, 1)
        val whoNew = result(MentalTest.WHO5, "2026-11-09", 3, 2)
        val gadOld = result(MentalTest.GAD7, "2026-11-01", 2, 3)
        val gadNew = result(MentalTest.GAD7, "2026-11-10", 1, 4)
        val pss = result(MentalTest.PSS10, "2026-11-05", 0, 5)
        val phq = result(MentalTest.PHQ9, "2026-11-05", 3, 6)
        val future = result(MentalTest.WHO5, "2026-11-11", 5, 7)
        val analytics = MentalAnalytics.build(emptyList(), listOf(gadNew, whoOld, pss, future, whoNew, phq, gadOld), today, Period.Month, zone)
        val series = analytics.questionnaires.associateBy { it.test }

        assertEquals(MentalTest.entries.toSet(), series.keys)
        assertEquals(listOf(whoNew), series.getValue(MentalTest.WHO5).results)
        assertEquals(40, series.getValue(MentalTest.WHO5).change)
        assertEquals(whoOld, series.getValue(MentalTest.WHO5).precedingLatest)
        assertEquals(listOf(gadOld, gadNew), series.getValue(MentalTest.GAD7).results)
        assertEquals(-7, series.getValue(MentalTest.GAD7).change)
        assertEquals(16, series.getValue(MentalTest.PSS10).latest!!.score)
        assertEquals(27, series.getValue(MentalTest.PHQ9).latest!!.score)
        assertNull(series.getValue(MentalTest.PHQ9).change)
        assertTrue(analytics.questionnaires.all { s -> s.results.all { it.test == s.test } })
    }

    private fun build(entries: List<QuickCheckIn>, period: Period = Period.Month) =
        MentalAnalytics.build(entries, emptyList(), today, period, zone)

    private fun millis(date: String) = LocalDate.parse(date).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun entry(date: String, hour: Int, value: Int, id: Long = 0) =
        at(millis(date) + hour * 3_600_000L, value, id)

    private fun at(time: Long, value: Int, id: Long = 0) =
        QuickCheckIn(id = id, completedAt = time, mood = value, tension = value, energy = value)

    private fun result(test: MentalTest, date: String, answer: Int, id: Long) =
        MentalResult(id, test, millis(date), List(test.questions.size) { answer })
}
