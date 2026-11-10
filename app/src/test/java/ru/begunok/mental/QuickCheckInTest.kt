package ru.begunok.mental

import org.junit.Assert.fail
import org.junit.Test

class QuickCheckInTest {

    @Test
    fun eachMeasureRejectsUnansweredAndOutOfRangeValues() {
        for (invalid in listOf(-1, 11, Int.MIN_VALUE, Int.MAX_VALUE)) {
            expectInvalid { QuickCheckIn(completedAt = 0L, mood = invalid, tension = 5, energy = 5) }
            expectInvalid { QuickCheckIn(completedAt = 0L, mood = 5, tension = invalid, energy = 5) }
            expectInvalid { QuickCheckIn(completedAt = 0L, mood = 5, tension = 5, energy = invalid) }
        }
    }


    private fun expectInvalid(action: () -> Unit) {
        try {
            action()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected validation failure.
        }
    }
}
