package ru.begunok.reminders

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

object ReminderSchedule {
    val WEIGHT_TIME: LocalTime = LocalTime.of(7, 15)
    val MOVEMENT_TIMES: List<LocalTime> = (10..18).map { hour -> LocalTime.of(hour, 30) }

    fun nextWeight(after: LocalDateTime): LocalDateTime {
        val today = after.toLocalDate().atTime(WEIGHT_TIME)
        return if (today.isAfter(after)) today else after.toLocalDate().plusDays(1).atTime(WEIGHT_TIME)
    }

    fun nextCheckIn(after: LocalDateTime, minuteOfDay: Int): LocalDateTime {
        require(minuteOfDay in 0 until 24 * 60)
        val time = LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)
        val today = after.toLocalDate().atTime(time)
        return if (today.isAfter(after)) today else after.toLocalDate().plusDays(1).atTime(time)
    }

    fun nextMovement(slot: Int, after: LocalDateTime): LocalDateTime {
        require(slot in MOVEMENT_TIMES.indices)
        var date = after.toLocalDate()
        while (true) {
            if (date.dayOfWeek.isWeekday()) {
                val candidate = date.atTime(MOVEMENT_TIMES[slot])
                if (candidate.isAfter(after)) return candidate
            }
            date = date.plusDays(1)
        }
    }

    private fun DayOfWeek.isWeekday(): Boolean = this != DayOfWeek.SATURDAY && this != DayOfWeek.SUNDAY
}
