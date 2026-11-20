package ru.begunok.health

import java.time.Instant
import java.time.LocalDate

/** Daily summaries deliberately omit sample arrays; the history owns each measurement once. */
data class HealthHistoryDay(val date: LocalDate, val readAt: Instant, val data: HealthDay)

data class HealthHistory(
    val days: List<HealthHistoryDay> = emptyList(),
    val heartRates: List<HrSample> = emptyList(),
    val oxygen: List<Spo2Sample> = emptyList(),
    val stress: List<StressSample> = emptyList(),
    val vitality: List<VitalitySample> = emptyList(),
)

internal data class HealthDayRead(
    val data: HealthDay,
    val completedPermissions: Set<String>,
    val errors: List<String>,
)

internal const val HEALTH_STEPS_PERMISSION = "android.permission.health.READ_STEPS"
private const val MAX_STEPS_COMPLETE = "steps_max_by_source"
private const val MAX_STEPS_LIMITED_COMPLETE = "steps_max_by_source_limited"

// Legacy READ_STEPS completion refers to the old cross-source aggregate. A separate marker
// schedules re-reading without changing the retained count or touching other permissions.
internal fun healthCompletionMarkers(permissions: Set<String>, historyGranted: Boolean): Set<String> =
    if (HEALTH_STEPS_PERMISSION !in permissions) permissions else {
        (permissions - HEALTH_STEPS_PERMISSION) +
            (if (historyGranted) MAX_STEPS_COMPLETE else MAX_STEPS_LIMITED_COMPLETE)
    }

internal fun pendingHealthPermissions(
    permissions: Set<String>,
    completed: Set<String>,
    historyGranted: Boolean,
): Set<String> = permissions.filterTo(mutableSetOf()) { permission ->
    if (permission == HEALTH_STEPS_PERMISSION) {
        MAX_STEPS_COMPLETE !in completed && (historyGranted || MAX_STEPS_LIMITED_COMPLETE !in completed)
    } else permission !in completed
}

internal fun sampleInRange(time: Instant, start: Instant, end: Instant): Boolean =
    !time.isBefore(start) && time.isBefore(end)

/** Missing reads retain acquired values; a measured zero is a valid correction, not missing data. */
internal fun mergeHealthDay(previous: HealthDay?, incoming: HealthDay): HealthDay {
    fun long(old: Long?, new: Long?): Long? = new?.takeIf { it >= 0 } ?: old
    fun number(old: Double?, new: Double?): Double? = new?.takeIf { it.isFinite() && it >= 0 } ?: old
    val old = previous ?: HealthDay(HealthTotals(), null, null, null)
    return HealthDay(
        totals = HealthTotals(
            // A recovered single-source count may be lower than the legacy combined count.
            steps = long(old.totals.steps, incoming.totals.steps),
            activeKcal = number(old.totals.activeKcal, incoming.totals.activeKcal),
            avgHr = long(old.totals.avgHr, incoming.totals.avgHr),
            minHr = long(old.totals.minHr, incoming.totals.minHr),
            maxHr = long(old.totals.maxHr, incoming.totals.maxHr),
            sources = old.totals.sources + incoming.totals.sources,
        ),
        lastHr = listOfNotNull(old.lastHr, incoming.lastHr).filter { it.bpm > 0 }.maxByOrNull { it.time },
        spo2 = listOfNotNull(old.spo2, incoming.spo2).filter { it.percent.isFinite() && it.percent > 0 }
            .maxByOrNull { it.time },
        sleep = incoming.sleep?.takeUnless { it.isNegative } ?: old.sleep,
        distanceMeters = number(old.distanceMeters, incoming.distanceMeters),
        avgStepCadence = number(old.avgStepCadence, incoming.avgStepCadence),
        sleepNights = normalizeSleepNights(old.sleepNights + incoming.sleepNights),
        lastStress = incoming.lastStress?.takeIf { old.lastStress == null || it.time >= old.lastStress.time } ?: old.lastStress,
        lastVitality = incoming.lastVitality?.takeIf { old.lastVitality == null || it.time >= old.lastVitality.time } ?: old.lastVitality,
    )
}
