package ru.begunok.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import android.util.Log
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.CancellationException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import kotlin.reflect.KClass

enum class HealthAvailability { Available, NeedsUpdate, Unavailable }

data class HrSample(val time: Instant, val bpm: Long)

data class Spo2Sample(val time: Instant, val percent: Double)

data class HealthTotals(
    val steps: Long? = null,
    val activeKcal: Double? = null,
    val avgHr: Long? = null,
    val minHr: Long? = null,
    val maxHr: Long? = null,
    val sources: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = steps == null && activeKcal == null && avgHr == null
}

data class DailySteps(val day: LocalDate, val count: Long)

data class StepHistory(
    val days: List<DailySteps> = emptyList(),
    val loading: Boolean = false,
    val message: String? = null,
    val failed: Boolean = false,
)

class HealthReadException(message: String, cause: Exception? = null) : Exception(message, cause)

data class HealthDay(
    val totals: HealthTotals,
    val lastHr: HrSample?,
    val spo2: Spo2Sample?,
    val sleep: Duration?,
)

class HealthRepository(private val context: Context) {
    val permissions: Set<String>
        get() = buildSet {
            add(read(StepsRecord::class))
            add(read(HeartRateRecord::class))
            add(read(OxygenSaturationRecord::class))
            add(read(SleepSessionRecord::class))
            add(read(ActiveCaloriesBurnedRecord::class))
            add(read(ExerciseSessionRecord::class))
            if (availability() == HealthAvailability.Available &&
                client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY) ==
                HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
            ) add(HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY)
        }

    private val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    fun availability(): HealthAvailability = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> HealthAvailability.Available
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthAvailability.NeedsUpdate
        else -> HealthAvailability.Unavailable
    }

    suspend fun granted(): Set<String> =
        if (availability() == HealthAvailability.Available) {
            reading("Не удалось проверить разрешения Health Connect") {
                client.permissionController.getGrantedPermissions()
            }
        } else {
            emptySet()
        }

    suspend fun steps(start: Instant, end: Instant): Long? {
        val granted = granted()
        return if (read(StepsRecord::class) in granted && end.isAfter(start)) {
            reading("Не удалось прочитать шаги") {
                client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(start, end)))[StepsRecord.COUNT_TOTAL]
            }
        } else null
    }

    suspend fun dailySteps(start: Instant, end: Instant, zone: ZoneId = ZoneId.systemDefault()): StepHistory {
        if (availability() != HealthAvailability.Available) {
            return StepHistory(message = "Шаги: Health Connect недоступен.", failed = true)
        }
        val granted = granted()
        if (read(StepsRecord::class) !in granted) {
            return StepHistory(message = "Шаги: разрешите чтение в разделе «Здоровье».")
        }
        return readStepHistory(
            start, end, zone,
            historyGranted = HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY in granted,
            today = LocalDate.now(zone),
        ) { from, until -> readDailySteps(from, until, zone) }
    }

    private suspend fun readDailySteps(start: Instant, end: Instant, zone: ZoneId): List<DailySteps> =
        reading("Не удалось прочитать шаги за ${start.atZone(zone).toLocalDate()} — ${end.atZone(zone).toLocalDate()}") {
            client.aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(start.atZone(zone).toLocalDateTime(), end.atZone(zone).toLocalDateTime()),
                    timeRangeSlicer = Period.ofDays(1),
                ),
            ).mapNotNull { bucket ->
                bucket.result[StepsRecord.COUNT_TOTAL]?.let { DailySteps(bucket.startTime.toLocalDate(), it) }
            }
        }

    suspend fun totals(start: Instant, end: Instant, granted: Set<String>): HealthTotals {
        val metrics = buildSet<AggregateMetric<*>> {
            if (read(StepsRecord::class) in granted) add(StepsRecord.COUNT_TOTAL)
            if (read(ActiveCaloriesBurnedRecord::class) in granted) add(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
            if (read(HeartRateRecord::class) in granted) {
                add(HeartRateRecord.BPM_AVG)
                add(HeartRateRecord.BPM_MIN)
                add(HeartRateRecord.BPM_MAX)
            }
        }
        if (metrics.isEmpty() || !end.isAfter(start)) return HealthTotals()
        val result: AggregationResult = reading("Не удалось прочитать показатели Health Connect") {
            client.aggregate(AggregateRequest(metrics, TimeRangeFilter.between(start, end)))
        }
        return HealthTotals(
            steps = result[StepsRecord.COUNT_TOTAL],
            activeKcal = result[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories,
            avgHr = result[HeartRateRecord.BPM_AVG],
            minHr = result[HeartRateRecord.BPM_MIN],
            maxHr = result[HeartRateRecord.BPM_MAX],
            sources = result.dataOrigins.map { it.packageName }.toSet(),
        )
    }

    suspend fun day(dayStart: Instant, now: Instant, granted: Set<String>): HealthDay {
        val lastHr = latest(HeartRateRecord::class, now.minus(Duration.ofDays(1)), now, granted)
            ?.samples?.maxByOrNull { it.time }?.let { HrSample(it.time, it.beatsPerMinute) }
        val spo2 = latest(OxygenSaturationRecord::class, now.minus(Duration.ofDays(7)), now, granted)
            ?.let { Spo2Sample(it.time, it.percentage.value) }
        val sleep = records(SleepSessionRecord::class, dayStart.minus(Duration.ofHours(SLEEP_LOOKBACK_HOURS)), now, granted)
            ?.takeIf { it.isNotEmpty() }
            ?.fold(Duration.ZERO) { sum, s -> sum + Duration.between(s.startTime, s.endTime) }
        return HealthDay(totals(dayStart, now, granted), lastHr, spo2, sleep)
    }

    private suspend fun <T : Record> latest(type: KClass<T>, start: Instant, end: Instant, granted: Set<String>): T? =
        records(type, start, end, granted, pageSize = 1)?.firstOrNull()

    private suspend fun <T : Record> records(
        type: KClass<T>,
        start: Instant,
        end: Instant,
        granted: Set<String>,
        pageSize: Int = 100,
    ): List<T>? {
        if (read(type) !in granted) return null
        return reading("Не удалось прочитать ${type.simpleName} из Health Connect") {
            client.readRecords(
                ReadRecordsRequest(
                    recordType = type,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    ascendingOrder = false,
                    pageSize = pageSize,
                )
            ).records
        }
    }

    private suspend fun <T> reading(message: String, block: suspend () -> T): T =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e("BegunokHealth", message, error)
            throw HealthReadException(message, error)
        }

    companion object {
        const val PROVIDER_PACKAGE = "com.google.android.apps.healthdata"
        private const val SLEEP_LOOKBACK_HOURS = 6L

        fun read(type: KClass<out Record>): String = HealthPermission.getReadPermission(type)

        fun cadence(steps: Long?, durationMs: Long): Int? {
            if (steps == null || steps <= 0 || durationMs < 60_000) return null
            return (steps * 60_000 / durationMs).toInt()
        }
    }
}
