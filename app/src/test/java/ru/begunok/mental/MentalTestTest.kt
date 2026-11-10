package ru.begunok.mental

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class MentalTestTest {

    @Test
    fun who5MapsNeverToZeroAndAllTheTimeToOneHundred() {
        val test = MentalTest.WHO5
        assertEquals("Никогда", test.options.first())
        assertEquals("Все время", test.options.last())
        assertEquals(0, test.score(List(5) { 0 }))
        assertEquals(100, test.score(List(5) { 5 }))
        assertEquals(60, test.score(listOf(1, 2, 3, 4, 5)))
    }

    @Test
    fun pssReversesExactlyItemsFourFiveSevenAndEight() {
        val test = MentalTest.PSS10
        val baseline = List(10) { 0 }
        assertEquals(16, test.score(baseline))
        for (index in baseline.indices) {
            val answers = baseline.toMutableList().apply { this[index] = 1 }
            val expected = if (index in listOf(3, 4, 6, 7)) 15 else 17
            assertEquals("Item ${index + 1}", expected, test.score(answers))
        }
    }

    @Test
    fun pssExtremesAreZeroAndFortyNotTenAndFifty() {
        val minimum = listOf(0, 0, 0, 4, 4, 0, 4, 4, 0, 0)
        val maximum = listOf(4, 4, 4, 0, 0, 4, 0, 0, 4, 4)
        assertEquals(0, MentalTest.PSS10.score(minimum))
        assertEquals(40, MentalTest.PSS10.score(maximum))
        assertEquals(24, MentalTest.PSS10.score(List(10) { 4 }))
    }

    @Test
    fun gadAndPhqUseEveryItemIncludingTheNinth() {
        assertEquals(0, MentalTest.GAD7.score(List(7) { 0 }))
        assertEquals(21, MentalTest.GAD7.score(List(7) { 3 }))
        assertEquals(0, MentalTest.PHQ9.score(List(9) { 0 }))
        assertEquals(27, MentalTest.PHQ9.score(List(9) { 3 }))
        assertEquals(3, MentalTest.PHQ9.score(List(8) { 0 } + 3))
    }

    @Test
    fun incompleteExtraAndOutOfRangeAnswersAreRejected() {
        for (test in MentalTest.entries) {
            val count = test.questions.size
            expectInvalid { test.score(List(count - 1) { 0 }) }
            expectInvalid { test.score(List(count + 1) { 0 }) }
            expectInvalid { test.score(emptyList()) }
            for (index in 0 until count) {
                val answers = MutableList(count) { 0 }
                answers[index] = -1
                expectInvalid { test.score(answers) }
                answers[index] = test.options.size
                expectInvalid { test.score(answers) }
            }
            expectInvalid { test.interpretation(-1) }
            expectInvalid { test.interpretation(test.maxScore + 1) }
        }
    }


    @Test
    fun ninthItemNeedsAttentionEvenWithMinimalTotalScore() {
        for (answer in 1..3) {
            val result = MentalResult(1, MentalTest.PHQ9, 0, List(8) { 0 } + answer)
            assertEquals(answer, result.score)
            assertTrue(result.hasSelfHarmThoughts)
        }
        assertFalse(MentalResult(2, MentalTest.PHQ9, 0, List(8) { 3 } + 0).hasSelfHarmThoughts)
        assertFalse(MentalResult(3, MentalTest.GAD7, 0, List(7) { 3 }).hasSelfHarmThoughts)
    }

    @Test
    fun dueDatesUseLocalCalendarDaysAcrossDaylightSaving() {
        val completed = Instant.parse("2026-03-20T23:30:00Z").toEpochMilli()
        val berlin = ZoneId.of("Europe/Berlin")
        assertEquals(LocalDate.of(2026, 4, 4), MentalTest.WHO5.nextDue(completed, berlin))
        assertEquals(LocalDate.of(2026, 4, 3), MentalTest.WHO5.nextDue(completed, ZoneOffset.UTC))
        assertEquals(LocalDate.of(2026, 4, 20), MentalTest.PSS10.nextDue(completed, berlin))
        assertEquals(listOf(14L, 30L, 14L, 14L), MentalTest.entries.map { it.intervalDays })
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
