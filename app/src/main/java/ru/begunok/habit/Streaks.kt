package ru.begunok.habit

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

object Streaks {
    fun dayOf(timeMs: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(timeMs).atZone(zone).toLocalDate()

    fun consecutiveDays(days: Set<LocalDate>, today: LocalDate): Int {
        var day = if (today in days) today else today.minusDays(1)
        var count = 0
        while (day in days) {
            count++
            day = day.minusDays(1)
        }
        return count
    }
}
