package ru.begunok.math

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class MathActivityTest {
    @Test
    fun independentDeviceCompletionsCountOnceOnEarliestDayButTasksRemainSeparate() {
        val first = LocalDate.of(2026, 3, 1)
        val second = first.plusDays(1)
        val events = listOf(
            MathActivityEvent(second, 200, "chisla", MathActivityKind.Topic),
            MathActivityEvent(first, 100, "chisla", MathActivityKind.Topic),
            MathActivityEvent(second, 210, "chisla", MathActivityKind.Task),
            MathActivityEvent(second, 220, "chisla", MathActivityKind.Task),
            MathActivityEvent(second, 230, "chisla", MathActivityKind.Review),
        )
        val activity = mathActivityByDay(events)
        assertEquals(MathDayActivity(topics = 1), activity[first])
        assertEquals(MathDayActivity(tasks = 2, reviews = 1), activity[second])
    }
}
