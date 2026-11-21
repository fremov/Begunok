package ru.begunok.analytics

import kotlinx.coroutines.flow.first
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import ru.begunok.data.ShowerRepository
import ru.begunok.data.WeightRepository
import ru.begunok.health.HealthDay
import ru.begunok.health.HealthHistory
import ru.begunok.health.normalizeSleepNights
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal enum class AnalyticsStream(val health: Boolean = true) {
    pulse, oxygen, stress, steps, active_kcal, distance, sleep,
    shower(false), weight(false),
}

internal data class AnalyticsPartition(val stream: AnalyticsStream, val month: YearMonth)

internal class AnalyticsRecord(val stream: AnalyticsStream, val date: LocalDate, val json: JSONObject)

private val timeFormat = DateTimeFormatter.ISO_OFFSET_DATE_TIME

private fun Instant.wireTime(zone: ZoneId): String = atZone(zone).format(timeFormat)

private fun record(
    stream: AnalyticsStream,
    id: String,
    date: LocalDate,
    value: Number,
    time: ZonedDateTime? = null,
): AnalyticsRecord = AnalyticsRecord(stream, date, JSONObject().apply {
    put("id", id)
    put("date", date.toString())
    put("value", value)
    if (time != null) put("time", time.format(timeFormat))
})

internal fun healthRecords(
    history: HealthHistory,
    stream: AnalyticsStream,
    month: YearMonth,
    zone: ZoneId,
): Sequence<AnalyticsRecord> {
    val first = month.atDay(1)
    val last = month.atEndOfMonth()
    fun sample(time: Instant, value: Number, distinguishValues: Boolean): AnalyticsRecord? {
        val local = time.atZone(zone)
        val date = local.toLocalDate()
        if (date < first || date > last) return null
        val id = if (distinguishValues) "$time/$value" else time.toString()
        return record(stream, id, date, value, local)
    }
    fun daily(value: (HealthDay) -> Number?) = history.days.asSequence().mapNotNull { day ->
        if (day.date < first || day.date > last) null
        else value(day.data)?.let { record(stream, day.date.toString(), day.date, it) }
    }
    return when (stream) {
        AnalyticsStream.pulse -> history.heartRates.asSequence().mapNotNull { sample(it.time, it.bpm, true) }
        AnalyticsStream.oxygen -> history.oxygen.asSequence().mapNotNull { sample(it.time, it.percent, true) }
        AnalyticsStream.stress -> history.stress.asSequence().mapNotNull { sample(it.time, it.score, false) }
        AnalyticsStream.steps -> daily { it.totals.steps }
        AnalyticsStream.active_kcal -> daily { it.totals.activeKcal }
        AnalyticsStream.distance -> daily { it.distanceMeters }
        AnalyticsStream.sleep -> {
            val nights = history.days.flatMap { it.data.sleepNights }
                .filter { YearMonth.from(it.end.atZone(zone)) == month }
            normalizeSleepNights(nights).asSequence().map { night ->
                val wake = night.end.atZone(zone)
                record(stream, "${night.start}/${night.end}", wake.toLocalDate(), night.sleepDuration.seconds, wake).apply {
                    json.put("start", night.start.wireTime(zone))
                    json.put("end", json.getString("time"))
                    json.put("stages", JSONArray().apply {
                        night.stages.sortedWith(compareBy({ it.start }, { it.end }, { it.kind.ordinal })).forEach { stage ->
                            if (stage.end > stage.start) put(JSONObject()
                                .put("start", stage.start.wireTime(zone))
                                .put("end", stage.end.wireTime(zone))
                                .put("stage", stage.kind.name.lowercase()))
                        }
                    })
                }
            }
        }
        else -> error("Not a health stream: $stream")
    }
}

internal class LocalAnalyticsSnapshots(
    private val showers: ShowerRepository,
    private val weight: WeightRepository,
) {
    suspend fun read(zone: ZoneId): Map<YearMonth, Map<AnalyticsStream, List<AnalyticsRecord>>> {
        val result = mutableMapOf<YearMonth, MutableMap<AnalyticsStream, MutableList<AnalyticsRecord>>>()
        fun add(record: AnalyticsRecord) {
            result.getOrPut(YearMonth.from(record.date)) { mutableMapOf() }
                .getOrPut(record.stream) { mutableListOf() }.add(record)
        }
        fun timed(stream: AnalyticsStream, id: Long, millis: Long, value: Number) {
            val time = Instant.ofEpochMilli(millis).atZone(zone)
            add(record(stream, id.toString(), time.toLocalDate(), value, time))
        }

        for (entry in showers.observeAll().first()) {
            timed(AnalyticsStream.shower, entry.id, entry.time, entry.durationSec)
        }
        for (entry in weight.observeEntries().first()) {
            val date = LocalDate.ofEpochDay(entry.day)
            add(record(AnalyticsStream.weight, date.toString(), date, entry.kg,
                Instant.ofEpochMilli(entry.time).atZone(zone)))
        }
        return result
    }
}

internal fun writeAnalyticsBatch(sink: BufferedSink, partition: AnalyticsPartition, records: Iterator<AnalyticsRecord>) {
    sink.writeUtf8("""{"stream":"${partition.stream}","month":"${partition.month}","records":[""")
    while (records.hasNext()) {
        val entry = records.next()
        check(entry.stream == partition.stream && YearMonth.from(entry.date) == partition.month)
        sink.writeUtf8(entry.json.toString())
        if (records.hasNext()) sink.writeByte(','.code)
    }
    sink.writeUtf8("]}")
}
