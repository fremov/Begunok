package ru.begunok.mental

import org.junit.Assert.fail
import org.junit.Test

class QuickCheckInTest {

    @Test
    fun tensionRejectsUnansweredAndOutOfRangeValues() {
        for (invalid in listOf(-1, 11, Int.MIN_VALUE, Int.MAX_VALUE)) {
            expectInvalid { QuickCheckIn(completedAt = 0L, tension = invalid) }
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
