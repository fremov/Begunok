package ru.begunok.analytics

import okio.BufferedSink
import org.json.JSONObject
import ru.begunok.health.HealthAutoSync
import ru.begunok.health.HealthHistory
import java.time.YearMonth
import java.time.ZoneId

internal class AnalyticsExport(
    private val health: HealthAutoSync,
    private val local: LocalAnalyticsSnapshots,
) {
    suspend fun write(sink: BufferedSink, source: String) {
        val zone = ZoneId.systemDefault()
        val retained = health.analyticsDateBounds(zone)
        val personal = local.read(zone)
        val months = sortedSetOf<YearMonth>()
        months.addAll(personal.keys)
        // A sleep session's wake date can cross a month after a timezone change.
        val first = retained?.start?.minusDays(2)?.let(YearMonth::from)
        val last = retained?.endInclusive?.plusDays(2)?.let(YearMonth::from)
        if (first != null && last != null) {
            var month: YearMonth = first
            while (month <= last) {
                months.add(month)
                month = month.plusMonths(1)
            }
        }
        sink.writeUtf8(JSONObject().put("version", 1).put("source", source).toString()).writeByte('\n'.code)
        val empty = HealthHistory()
        for (month in months) {
            val history = if (first != null && last != null && month in first..last) {
                health.analyticsHistory(month.atDay(1).minusDays(2), month.atEndOfMonth().plusDays(2), zone)
            } else empty
            for (stream in AnalyticsStream.entries) {
                val records = if (stream.health) healthRecords(history, stream, month, zone).iterator()
                    else personal[month]?.get(stream).orEmpty().iterator()
                if (!records.hasNext()) continue
                writeAnalyticsBatch(sink, AnalyticsPartition(stream, month), records)
                sink.writeByte('\n'.code)
            }
        }
        // The PC must see this marker and EOF before it can commit anything.
        sink.writeUtf8("{\"complete\":true}\n")
    }
}
