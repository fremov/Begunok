package ru.begunok.reminders

import ru.begunok.mental.MentalTest
import java.time.LocalTime
import java.time.ZonedDateTime

object MentalReminderSchedule {
    fun nextAt(
        test: MentalTest,
        completedAt: Long,
        notifiedCompletion: Long?,
        now: ZonedDateTime = ZonedDateTime.now(),
    ): ZonedDateTime? {
        if (notifiedCompletion == completedAt) return null
        val due = test.nextDue(completedAt, now.zone).atTime(LocalTime.of(10, 0)).atZone(now.zone)
        return if (due.isAfter(now)) due else now.plusMinutes(1)
    }
}
