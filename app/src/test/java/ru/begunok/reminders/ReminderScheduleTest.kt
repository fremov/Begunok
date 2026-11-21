package ru.begunok.reminders

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class ReminderScheduleTest {
    @Test
    fun weightReminderMovesToNextMorning() {
        val after = LocalDateTime.of(2026, 11, 9, 8, 1)

        assertEquals(
            LocalDateTime.of(2026, 11, 10, 7, 15),
            ReminderSchedule.nextWeight(after),
        )
    }

    @Test
    fun movementReminderSkipsWeekend() {
        val fridayEvening = LocalDateTime.of(2026, 11, 13, 18, 31)

        assertEquals(
            LocalDateTime.of(2026, 11, 16, 10, 30),
            ReminderSchedule.nextMovement(0, fridayEvening),
        )
    }

    @Test
    fun movementReminderUsesEachHalfHourSlot() {
        val beforeWork = LocalDateTime.of(2026, 11, 9, 9, 0)

        assertEquals(
            LocalDateTime.of(2026, 11, 9, 10, 30),
            ReminderSchedule.nextMovement(0, beforeWork),
        )
        assertEquals(
            LocalDateTime.of(2026, 11, 9, 15, 30),
            ReminderSchedule.nextMovement(5, beforeWork),
        )
        assertEquals(
            LocalDateTime.of(2026, 11, 9, 18, 30),
            ReminderSchedule.nextMovement(8, beforeWork),
        )
    }
}
