package ru.begunok.activity

import java.time.LocalDate
import java.time.YearMonth

data class ActivityCalendarPeriod(
    val month: YearMonth = YearMonth.now(),
    val yearly: Boolean = false,
) {
    val start: LocalDate
        get() = if (yearly) LocalDate.of(month.year, 1, 1) else month.atDay(1)

    val end: LocalDate
        get() = if (yearly) LocalDate.of(month.year, 12, 31) else month.atEndOfMonth()

    fun previous(): ActivityCalendarPeriod =
        copy(month = if (yearly) month.minusYears(1) else month.minusMonths(1))

    fun next(): ActivityCalendarPeriod =
        copy(month = if (yearly) month.plusYears(1) else month.plusMonths(1))
}
