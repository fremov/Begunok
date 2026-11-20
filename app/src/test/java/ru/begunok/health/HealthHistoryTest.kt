package ru.begunok.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class HealthHistoryTest {
    private val time = Instant.parse("2026-11-15T07:29:00.123456789Z")
    private val recorded = HealthDay(
        HealthTotals(5000, 240.0, 75, 55, 135, setOf("mi.fitness")),
        HrSample(time, 72), Spo2Sample(time, 98.0), Duration.ofHours(7),
        distanceMeters = 3200.0, avgStepCadence = 112.0,
    )

    @Test
    fun missingValuesRetainHistoryAndMeasuredZerosRemainValid() {
        val missing = HealthDay(HealthTotals(), null, null, null)
        assertEquals(recorded, mergeHealthDay(recorded, missing))
        val corrected = recorded.copy(
            totals = recorded.totals.copy(steps = 0, activeKcal = 0.0),
            sleep = Duration.ZERO, distanceMeters = 0.0, avgStepCadence = 0.0,
        )
        assertEquals(corrected, mergeHealthDay(recorded, corrected))
    }

    @Test
    fun correctedStepsReplaceInflatedHistoryWithoutDiscardingLegacyMetrics() {
        val legacy = recorded.copy(
            totals = recorded.totals.copy(steps = 14200, sources = setOf("mi.fitness", "android")),
            lastVitality = VitalitySample(time, 80.0, 6.0),
        )
        val absent = HealthDay(HealthTotals(), null, null, null)
        assertEquals(legacy, mergeHealthDay(legacy, absent))

        val recovered = absent.copy(totals = HealthTotals(steps = 7500, sources = setOf("mi.fitness")))
        assertEquals(legacy.copy(totals = legacy.totals.copy(steps = 7500)), mergeHealthDay(legacy, recovered))
    }

    @Test
    fun legacyStepCompletionIsReopenedWithoutRetryingOtherCompletedMetrics() {
        val existing = setOf(HEALTH_STEPS_PERMISSION, "android.permission.health.READ_HEART_RATE")
        assertEquals(setOf(HEALTH_STEPS_PERMISSION), pendingHealthPermissions(existing, existing, historyGranted = false))
        assertEquals(setOf(HEALTH_STEPS_PERMISSION), pendingHealthPermissions(existing, existing, historyGranted = true))

        val limited = existing + healthCompletionMarkers(setOf(HEALTH_STEPS_PERMISSION), historyGranted = false)
        assertTrue(pendingHealthPermissions(existing, limited, historyGranted = false).isEmpty())
        assertEquals(setOf(HEALTH_STEPS_PERMISSION), pendingHealthPermissions(existing, limited, historyGranted = true))

        val complete = limited + healthCompletionMarkers(setOf(HEALTH_STEPS_PERMISSION), historyGranted = true)
        assertTrue(pendingHealthPermissions(existing, complete, historyGranted = true).isEmpty())
        assertTrue(pendingHealthPermissions(existing, complete, historyGranted = false).isEmpty())
    }

    @Test
    fun olderMeasurementsCannotLookLikeFreshOnesAndMergeIsIdempotent() {
        val incoming = HealthDay(
            HealthTotals(5100, sources = setOf("other")),
            HrSample(time.minusSeconds(60), 90), Spo2Sample(time.minusSeconds(60), 95.0), null,
        )
        val merged = mergeHealthDay(recorded, incoming)
        assertEquals(recorded.lastHr, merged.lastHr)
        assertEquals(recorded.spo2, merged.spo2)
        assertEquals(5100L, merged.totals.steps)
        assertEquals(setOf("mi.fitness", "other"), merged.totals.sources)
        assertEquals(merged, mergeHealthDay(merged, incoming))
    }

    @Test
    fun dailySummariesDoNotDuplicateMeasurementArrays() {
        val incoming = recorded.copy(hrSamples = listOf(recorded.lastHr!!), spo2Samples = listOf(recorded.spo2!!))
        val merged = mergeHealthDay(null, incoming)
        assertTrue(merged.hrSamples.isEmpty())
        assertTrue(merged.spo2Samples.isEmpty())
        assertEquals(recorded.lastHr, merged.lastHr)
        assertEquals(time, merged.lastHr!!.time)
    }

    @Test
    fun adjacentDayWindowsAssignMidnightOnceEvenAcrossDst() {
        val zone = ZoneId.of("Europe/Berlin")
        val date = LocalDate.of(2026, 10, 25)
        val start = date.atStartOfDay(zone).toInstant()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant()
        assertEquals(25L, Duration.between(start, end).toHours())
        assertTrue(sampleInRange(start, start, end))
        assertTrue(sampleInRange(end.minusNanos(1), start, end))
        assertFalse(sampleInRange(end, start, end))
        assertFalse(sampleInRange(start.minusNanos(1), start, end))
        assertTrue(sampleInRange(end, end, date.plusDays(2).atStartOfDay(zone).toInstant()))
    }
}
