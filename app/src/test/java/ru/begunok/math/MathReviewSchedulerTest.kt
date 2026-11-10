package ru.begunok.math

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class MathReviewSchedulerTest {
    private val today = LocalDate.of(2024, 2, 29)

    @Test
    fun initialReviewUsesCalendarDaysAcrossLeapDay() {
        val initial = MathReview.initial(LocalDate.of(2024, 2, 28))
        assertEquals(today, initial.due)
        assertEquals(1, initial.interval)
        assertEquals(1, initial.repetitions)
        assertEquals(null, initial.lastReviewed)
        assertEquals(LocalDate.of(2024, 3, 1), MathReview.initial(today).due)
    }

    @Test
    fun firstSuccessfulReviewAfterCompletionUsesSixDays() {
        val old = MathReview.initial(today.minusDays(1))
        val result = MathReviewScheduler.rate(old, MathRating.Good, today)
        assertEquals(2, result.repetitions)
        assertEquals(6, result.interval)
        assertEquals(LocalDate.of(2024, 3, 6), result.due)
        assertEquals(today, result.lastReviewed)
        assertEquals(2.5, result.ease, 0.000001)
    }

    @Test
    fun laterIntervalRoundsUpRatherThanToNearestInteger() {
        val old = MathReview(today, 6, 2, 2.51, null)
        val result = MathReviewScheduler.rate(old, MathRating.Good, today)
        assertEquals(16, result.interval)
        assertEquals(today.plusDays(16), result.due)
    }

    @Test
    fun laterIntervalUsesOldEaseBeforeApplyingRating() {
        val old = MathReview(today, 10, 3, 2.51, null)
        val result = MathReviewScheduler.rate(old, MathRating.Easy, today)
        assertEquals(26, result.interval)
        assertEquals(2.61, result.ease, 0.000001)
    }

    @Test
    fun failureResetsRepetitionsAndFloorsEase() {
        val old = MathReview(today.minusDays(8), 20, 5, 1.35, today.minusDays(28))
        val result = MathReviewScheduler.rate(old, MathRating.Again, today)
        assertEquals(0, result.repetitions)
        assertEquals(1, result.interval)
        assertEquals(today.plusDays(1), result.due)
        assertEquals(1.3, result.ease, 0.000001)
        val recovered = MathReviewScheduler.rate(result, MathRating.Good, today.plusDays(1))
        assertEquals(1, recovered.repetitions)
        assertEquals(1, recovered.interval)
    }

    @Test
    fun hardRecallKeepsSuccessfulSequenceButLowersEase() {
        val old = MathReview(LocalDate.of(2024, 12, 31), 1, 1, 2.5, null)
        val result = MathReviewScheduler.rate(old, MathRating.Hard, old.due)
        assertEquals(2, result.repetitions)
        assertEquals(6, result.interval)
        assertEquals(LocalDate.of(2025, 1, 6), result.due)
        assertEquals(2.36, result.ease, 0.000001)
    }

    @Test(expected = IllegalArgumentException::class)
    fun earlyReviewIsRejected() {
        MathReviewScheduler.rate(MathReview.initial(today), MathRating.Good, today)
    }

    @Test(expected = IllegalArgumentException::class)
    fun sameDayReviewIsRejectedEvenWhenDueDateIsInPast() {
        val old = MathReview(today.minusDays(1), 1, 0, 2.5, today)
        MathReviewScheduler.rate(old, MathRating.Good, today)
    }
}
