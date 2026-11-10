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

    @Test
    fun checkInUsesSelectedTimeAndMovesPastSlotsToTomorrow() {
        val now = LocalDateTime.of(2026, 11, 10, 10, 0)
        assertEquals(LocalDateTime.of(2026, 11, 11, 9, 0), ReminderSchedule.nextCheckIn(now, 9 * 60))
        assertEquals(LocalDateTime.of(2026, 11, 10, 21, 30), ReminderSchedule.nextCheckIn(now, 21 * 60 + 30))
    }

    @Test
    fun checkInDoesNotRepeatTheCurrentMinuteAndIncludesWeekends() {
        val saturday = LocalDateTime.of(2026, 11, 14, 0, 0)
        assertEquals(LocalDateTime.of(2026, 11, 15, 0, 0), ReminderSchedule.nextCheckIn(saturday, 0))
        assertEquals(LocalDateTime.of(2026, 11, 14, 23, 59), ReminderSchedule.nextCheckIn(saturday, 1439))
    }

    @Test(expected = IllegalArgumentException::class)
    fun checkInRejectsTimeOutsideDay() {
        ReminderSchedule.nextCheckIn(LocalDateTime.of(2026, 11, 10, 10, 0), 1440)
    }
}
