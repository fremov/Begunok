package ru.begunok.reminders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.begunok.mental.MentalTest
import java.time.ZoneId
import java.time.ZonedDateTime

class MentalReminderScheduleTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val completion = ZonedDateTime.of(2026, 10, 20, 23, 30, 0, 0, zone)

    @Test
    fun dueDateUsesCalendarDaysAcrossDaylightSavingChange() {
        val next = MentalReminderSchedule.nextAt(MentalTest.WHO5, completion.toInstant().toEpochMilli(), null, completion)!!
        assertEquals(ZonedDateTime.of(2026, 11, 3, 10, 0, 0, 0, zone), next)
    }

    @Test
    fun overdueTestIsNotSilentlyLostAfterRestart() {
        val now = completion.plusDays(20)
        assertEquals(now.plusMinutes(1), MentalReminderSchedule.nextAt(MentalTest.GAD7, completion.toInstant().toEpochMilli(), null, now))
    }

    @Test
    fun deliveredCompletionDoesNotNotifyAgainButRetakeDoes() {
        val timestamp = completion.toInstant().toEpochMilli()
        assertNull(MentalReminderSchedule.nextAt(MentalTest.PHQ9, timestamp, timestamp, completion))
        assertTrue(MentalReminderSchedule.nextAt(MentalTest.PHQ9, timestamp + 1, timestamp, completion)!!.isAfter(completion))
    }

    @Test
    fun perceivedStressUsesThirtyDayInterval() {
        assertEquals(completion.toLocalDate().plusDays(30).atTime(10, 0).atZone(zone),
            MentalReminderSchedule.nextAt(MentalTest.PSS10, completion.toInstant().toEpochMilli(), null, completion))
    }
}
