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

internal fun sampleInRange(time: Instant, start: Instant, end: Instant): Boolean =
    !time.isBefore(start) && time.isBefore(end)

/** Missing reads retain acquired values; a measured zero is a valid correction, not missing data. */
internal fun mergeHealthDay(previous: HealthDay?, incoming: HealthDay): HealthDay {
    fun long(old: Long?, new: Long?): Long? = new?.takeIf { it >= 0 } ?: old
    fun number(old: Double?, new: Double?): Double? = new?.takeIf { it.isFinite() && it >= 0 } ?: old
    val old = previous ?: HealthDay(HealthTotals(), null, null, null)
    return HealthDay(
        totals = HealthTotals(
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
