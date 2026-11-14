package ru.begunok.activity

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class ActivityCalendarPeriodTest {
    @Test
    fun leapFebruaryIncludesTwentyNinthDay() {
        val period = ActivityCalendarPeriod(YearMonth.of(2024, 2))

        assertEquals(LocalDate.of(2024, 2, 1), period.start)
        assertEquals(LocalDate.of(2024, 2, 29), period.end)
    }

    @Test
    fun ordinaryFebruaryEndsOnTwentyEighthDay() {
        val period = ActivityCalendarPeriod(YearMonth.of(2025, 2))

        assertEquals(LocalDate.of(2025, 2, 1), period.start)
        assertEquals(LocalDate.of(2025, 2, 28), period.end)
    }

    @Test
    fun monthNavigationCrossesDecemberAndJanuary() {
        val december = ActivityCalendarPeriod(YearMonth.of(2025, 12))
        val january = ActivityCalendarPeriod(YearMonth.of(2026, 1))

        assertEquals(january, december.next())
        assertEquals(december, january.previous())
        assertEquals(LocalDate.of(2025, 12, 1), january.previous().start)
        assertEquals(LocalDate.of(2026, 1, 31), december.next().end)
    }

    @Test
    fun monthNavigationPreservesLeapFebruaryBounds() {
        val february = ActivityCalendarPeriod(YearMonth.of(2024, 2))

        assertEquals(february, ActivityCalendarPeriod(YearMonth.of(2024, 1)).next())
        assertEquals(february, ActivityCalendarPeriod(YearMonth.of(2024, 3)).previous())
        assertEquals(LocalDate.of(2024, 1, 31), february.previous().end)
        assertEquals(LocalDate.of(2024, 3, 31), february.next().end)
    }

    @Test
    fun yearlyPeriodUsesCalendarYearRatherThanRollingDays() {
        val period = ActivityCalendarPeriod(YearMonth.of(2025, 11), yearly = true)

        assertEquals(LocalDate.of(2025, 1, 1), period.start)
        assertEquals(LocalDate.of(2025, 12, 31), period.end)
        assertEquals(365L, period.end.toEpochDay() - period.start.toEpochDay() + 1)
    }

    @Test
    fun leapYearCoversAllThreeHundredSixtySixDays() {
        val period = ActivityCalendarPeriod(YearMonth.of(2024, 2), yearly = true)

        assertEquals(LocalDate.of(2024, 1, 1), period.start)
        assertEquals(LocalDate.of(2024, 12, 31), period.end)
        assertEquals(366L, period.end.toEpochDay() - period.start.toEpochDay() + 1)
    }

    @Test
    fun yearNavigationChangesWholeYearsAcrossDecemberAndJanuary() {
        val december = ActivityCalendarPeriod(YearMonth.of(2025, 12), yearly = true)
        val january = ActivityCalendarPeriod(YearMonth.of(2026, 1), yearly = true)

        assertEquals(ActivityCalendarPeriod(YearMonth.of(2026, 12), yearly = true), december.next())
        assertEquals(ActivityCalendarPeriod(YearMonth.of(2025, 1), yearly = true), january.previous())
        assertEquals(LocalDate.of(2026, 1, 1), december.next().start)
        assertEquals(LocalDate.of(2026, 12, 31), december.next().end)
        assertEquals(LocalDate.of(2025, 1, 1), january.previous().start)
        assertEquals(LocalDate.of(2025, 12, 31), january.previous().end)
        assertEquals(december, december.next().previous())
        assertEquals(january, january.previous().next())
    }
}
