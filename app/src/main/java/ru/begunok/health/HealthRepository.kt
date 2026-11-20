package ru.begunok.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import android.util.Log
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.CancellationException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
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
    val distanceMeters: Double? = null,
    val avgStepCadence: Double? = null,
    val hrSamples: List<HrSample> = emptyList(),
    val spo2Samples: List<Spo2Sample> = emptyList(),
    val sleepNights: List<SleepNight> = emptyList(),
    val stressSamples: List<StressSample> = emptyList(),
    val vitalitySamples: List<VitalitySample> = emptyList(),
    val lastStress: StressSample? = null,
    val lastVitality: VitalitySample? = null,
)

internal data class StepSourceTotal(val packageName: String, val count: Long)

internal suspend fun maxStepsBySource(
    origins: Set<DataOrigin>,
    readCount: suspend (DataOrigin) -> Long?,
): StepSourceTotal? {
    var winner: DataOrigin? = null
    var maximum = -1L
    for (origin in origins) {
        val count = readCount(origin)?.takeIf { it >= 0 } ?: continue
        if (count > maximum || (count == maximum && origin.packageName < checkNotNull(winner).packageName)) {
            winner = origin
            maximum = count
        }
    }
    return winner?.let { StepSourceTotal(it.packageName, maximum) }
}

class HealthRepository(private val context: Context) {
    val permissions: Set<String>
        get() = buildSet {
            add(read(StepsRecord::class))
            add(read(HeartRateRecord::class))
            add(read(OxygenSaturationRecord::class))
            add(read(SleepSessionRecord::class))
            add(read(ActiveCaloriesBurnedRecord::class))
            add(read(DistanceRecord::class))
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
            stepTotals(start, end)?.count
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

    private suspend fun readDailySteps(start: Instant, end: Instant, zone: ZoneId): List<DailySteps> {
        val days = mutableListOf<DailySteps>()
        var from = start
        while (from < end) {
            val date = from.atZone(zone).toLocalDate()
            val until = minOf(end, date.plusDays(1).atStartOfDay(zone).toInstant())
            stepTotals(from, until)?.let { days.add(DailySteps(date, it.count)) }
            from = until
        }
        return days
    }

    private suspend fun stepTotals(start: Instant, end: Instant): StepSourceTotal? =
        reading("Не удалось прочитать шаги Health Connect") {
            val range = TimeRangeFilter.between(start, end)
            val metrics = setOf(StepsRecord.COUNT_TOTAL)
            // Aggregation discovers overlapping intervals that began before the window.
            // Raw records also expose origins that did not contribute to the priority aggregate.
            val origins = client.aggregate(AggregateRequest(metrics, range)).dataOrigins.toMutableSet()
            visitRecords(StepsRecord::class, range) { origins.add(it.metadata.dataOrigin) }
            maxStepsBySource(origins) { origin ->
                client.aggregate(AggregateRequest(metrics, range, setOf(origin)))[StepsRecord.COUNT_TOTAL]
            }
        }

    suspend fun totals(start: Instant, end: Instant, granted: Set<String>): HealthTotals {
        if (!end.isAfter(start)) return HealthTotals()
        val steps = if (read(StepsRecord::class) in granted) stepTotals(start, end) else null
        val metrics = buildSet<AggregateMetric<*>> {
            if (read(ActiveCaloriesBurnedRecord::class) in granted) add(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
            if (read(HeartRateRecord::class) in granted) {
                add(HeartRateRecord.BPM_AVG)
                add(HeartRateRecord.BPM_MIN)
                add(HeartRateRecord.BPM_MAX)
            }
        }
        val result: AggregationResult? = if (metrics.isEmpty()) null else {
            reading("Не удалось прочитать показатели Health Connect") {
                client.aggregate(AggregateRequest(metrics, TimeRangeFilter.between(start, end)))
            }
        }
        return HealthTotals(
            steps = steps?.count,
            activeKcal = result?.get(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)?.inKilocalories,
            avgHr = result?.get(HeartRateRecord.BPM_AVG),
            minHr = result?.get(HeartRateRecord.BPM_MIN),
            maxHr = result?.get(HeartRateRecord.BPM_MAX),
            sources = buildSet {
                steps?.let { add(it.packageName) }
                result?.dataOrigins?.forEach { add(it.packageName) }
            },
        )
    }

    suspend fun day(dayStart: Instant, now: Instant, granted: Set<String>): HealthDay {
        val result = readDay(dayStart, now, granted)
        if (result.errors.isNotEmpty()) throw HealthReadException(result.errors.joinToString("; "))
        return result.data
    }

    /** Independent reads retain successful metrics even when one provider query fails. */
    internal suspend fun readDay(dayStart: Instant, now: Instant, granted: Set<String>): HealthDayRead {
        if (!now.isAfter(dayStart)) return HealthDayRead(HealthDay(HealthTotals(), null, null, null), emptySet(), emptyList())
        val errors = mutableListOf<String>()
        val completed = mutableSetOf<String>()
        suspend fun <T> metric(type: KClass<out Record>, readValue: suspend () -> T): T? {
            val permission = read(type)
            if (permission !in granted) return null
            return try {
                readValue().also { completed.add(permission) }
            } catch (error: HealthReadException) {
                errors.add(error.message ?: "Не удалось прочитать ${type.simpleName}")
                null
            }
        }
        // Read the full requested day, not just the most recent record/page. HC may contain
        // only an old Mi Fitness upload; never replace its source timestamp with the read time.
        val heartRecords = metric(HeartRateRecord::class) {
            // Interval records may begin before midnight while containing today's samples.
            records(HeartRateRecord::class, dayStart.minus(Duration.ofDays(1)), now, granted).orEmpty()
        }
        val hr = heartRecords.orEmpty().flatMap { record ->
            record.samples.filter { sampleInRange(it.time, dayStart, now) }
                .map { HrSample(it.time, it.beatsPerMinute) }
        }.distinct().sortedBy { it.time }
        val oxygenRecords = metric(OxygenSaturationRecord::class) {
            records(OxygenSaturationRecord::class, dayStart, now, granted).orEmpty()
        }
        val oxygen = oxygenRecords.orEmpty().filter { sampleInRange(it.time, dayStart, now) }
            .map { Spo2Sample(it.time, it.percentage.value) }.distinct().sortedBy { it.time }
        val sleepRecords = metric(SleepSessionRecord::class) {
            records(SleepSessionRecord::class, dayStart.minus(Duration.ofHours(SLEEP_LOOKBACK_HOURS)), now, granted)
                .orEmpty().filter { it.endTime.isAfter(dayStart) && !it.endTime.isAfter(now) }
        }
        val sleepNights = selectSleepNights(sleepRecords.orEmpty())
        val steps = metric(StepsRecord::class) { stepTotals(dayStart, now) }
        val calories = metric(ActiveCaloriesBurnedRecord::class) {
            totals(dayStart, now, setOf(read(ActiveCaloriesBurnedRecord::class)))
        }
        val distance = metric(DistanceRecord::class) {
            reading("Не удалось прочитать расстояние из Health Connect") {
                val request = AggregateRequest(setOf(DistanceRecord.DISTANCE_TOTAL), TimeRangeFilter.between(dayStart, now))
                client.aggregate(request)[DistanceRecord.DISTANCE_TOTAL]?.inMeters
            }
        }
        val totals = HealthTotals(
            steps = steps?.count,
            activeKcal = calories?.activeKcal,
            avgHr = hr.takeIf { it.isNotEmpty() }?.map { it.bpm }?.average()?.toLong(),
            minHr = hr.minOfOrNull { it.bpm },
            maxHr = hr.maxOfOrNull { it.bpm },
            sources = buildSet {
                steps?.let { add(it.packageName) }
                addAll(calories?.sources.orEmpty())
                heartRecords.orEmpty().forEach { add(it.metadata.dataOrigin.packageName) }
                oxygenRecords.orEmpty().forEach { add(it.metadata.dataOrigin.packageName) }
                sleepRecords.orEmpty().forEach { add(it.metadata.dataOrigin.packageName) }
            },
        )
        return HealthDayRead(
            HealthDay(totals, hr.lastOrNull(), oxygen.lastOrNull(),
                sleepNights.takeIf { it.isNotEmpty() }?.fold(Duration.ZERO) { total, night -> total + night.sleepDuration },
                distanceMeters = distance, hrSamples = hr, spo2Samples = oxygen,
                sleepNights = sleepNights),
            completed, errors,
        )
    }

    private fun selectSleepNights(records: List<SleepSessionRecord>): List<SleepNight> {
        val candidates = records.map { record ->
            SleepNight(
                start = record.startTime,
                end = record.endTime,
                stages = record.stages.map { stage ->
                    SleepStageSample(stage.startTime, stage.endTime, when (stage.stage) {
                        SleepSessionRecord.STAGE_TYPE_AWAKE,
                        SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> SleepStageKind.AWAKE
                        SleepSessionRecord.STAGE_TYPE_SLEEPING -> SleepStageKind.SLEEPING
                        SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> SleepStageKind.OUT_OF_BED
                        SleepSessionRecord.STAGE_TYPE_LIGHT -> SleepStageKind.LIGHT
                        SleepSessionRecord.STAGE_TYPE_DEEP -> SleepStageKind.DEEP
                        SleepSessionRecord.STAGE_TYPE_REM -> SleepStageKind.REM
                        else -> SleepStageKind.UNKNOWN
                    })
                },
                source = record.metadata.dataOrigin.packageName,
            )
        }
        return normalizeSleepNights(candidates)
    }

    private suspend fun <T : Record> records(
        type: KClass<T>,
        start: Instant,
        end: Instant,
        granted: Set<String>,
    ): List<T>? {
        if (read(type) !in granted) return null
        if (!end.isAfter(start)) return emptyList()
        return reading("Не удалось прочитать ${type.simpleName} из Health Connect") {
            buildList {
                visitRecords(type, TimeRangeFilter.between(start, end)) { add(it) }
            }
        }
    }

    private suspend fun <T : Record> visitRecords(type: KClass<T>, range: TimeRangeFilter, visit: (T) -> Unit) {
        var token: String? = null
        do {
            val page = client.readRecords(
                ReadRecordsRequest(
                    recordType = type,
                    timeRangeFilter = range,
                    ascendingOrder = false,
                    pageSize = 1000,
                    pageToken = token,
                )
            )
            page.records.forEach(visit)
            token = page.pageToken
        } while (token != null)
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
        private const val SLEEP_LOOKBACK_HOURS = 24L

        fun read(type: KClass<out Record>): String = HealthPermission.getReadPermission(type)
    }
}
