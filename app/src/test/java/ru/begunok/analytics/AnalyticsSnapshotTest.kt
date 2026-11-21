package ru.begunok.analytics

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.begunok.data.ShowerRepository
import ru.begunok.data.WeightRepository
import ru.begunok.data.db.ColdShowerEntity
import ru.begunok.data.db.ShowerDao
import ru.begunok.data.db.WeightDao
import ru.begunok.data.db.WeightEntryEntity
import ru.begunok.health.HealthDay
import ru.begunok.health.HealthHistory
import ru.begunok.health.HealthHistoryDay
import ru.begunok.health.HealthTotals
import ru.begunok.health.HrSample
import ru.begunok.health.SleepNight
import ru.begunok.health.SleepStageKind
import ru.begunok.health.SleepStageSample
import ru.begunok.health.StressSample
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class AnalyticsSnapshotTest {
    private val zone = ZoneId.of("Europe/Moscow")
    private val february = YearMonth.of(2026, 2)
    private val march = february.plusMonths(1)
    private val localMarch = Instant.parse("2026-02-28T22:30:00Z")

    @Test
    fun monthSnapshotsUseLocalDateAndKeepTimestampAndSimultaneousPulseValues() {
        val duplicateTime = listOf(HrSample(localMarch, 71), HrSample(localMarch, 72))
        val history = HealthHistory(heartRates = listOf(HrSample(localMarch.minusSeconds(7200), 70)) + duplicateTime)
        val partition = AnalyticsPartition(AnalyticsStream.pulse, march)
        val json = batchJson(partition, healthRecords(history, partition.stream, march, zone))
        val records = json.getJSONArray("records")
        assertEquals("2026-03", json.getString("month"))
        assertEquals(2, records.length())
        assertEquals("2026-03-01", records.getJSONObject(0).getString("date"))
        assertEquals("2026-03-01T01:30:00+03:00", records.getJSONObject(0).getString("time"))
        assertNotEquals(records.getJSONObject(0).getString("id"), records.getJSONObject(1).getString("id"))
        assertEquals(0, batchJson(partition, emptySequence()).getJSONArray("records").length())
    }

    @Test
    fun exportStreamsRetainHealthStressAndExcludePsychologicalMetrics() {
        assertEquals(
            setOf("pulse", "oxygen", "stress", "steps", "active_kcal", "distance", "sleep", "shower", "weight"),
            AnalyticsStream.entries.map { it.name }.toSet(),
        )
        assertTrue(AnalyticsStream.stress.health)
        val history = HealthHistory(stress = listOf(StressSample(localMarch, 42)))
        val partition = AnalyticsPartition(AnalyticsStream.stress, march)
        val json = batchJson(partition, healthRecords(history, partition.stream, march, zone))
        assertEquals("stress", json.getString("stream"))
        val row = json.getJSONArray("records").getJSONObject(0)
        assertEquals("2026-03-01", row.getString("date"))
        assertEquals("2026-03-01T01:30:00+03:00", row.getString("time"))
        assertEquals(42, row.getInt("value"))
    }

    @Test
    fun sleepUsesWakeDateAndOriginalStageTimesWithoutSourceMetadata() {
        val start = localMarch.minusSeconds(7200)
        val stage = SleepStageSample(start, start.plusSeconds(80), SleepStageKind.OUT_OF_BED)
        val night = SleepNight(start, localMarch, listOf(stage), "provider-to-exclude")
        val previousDay = HealthHistoryDay(LocalDate.of(2026, 2, 28), localMarch,
            HealthDay(HealthTotals(), null, null, null, sleepNights = listOf(night)))
        val history = HealthHistory(days = listOf(previousDay, previousDay))
        assertTrue(healthRecords(history, AnalyticsStream.sleep, february, zone).none())
        val record = healthRecords(history, AnalyticsStream.sleep, march, zone).single().json
        assertEquals("2026-03-01", record.getString("date"))
        assertEquals(7120, record.getInt("value"))
        val jsonStage = record.getJSONArray("stages").getJSONObject(0)
        assertEquals("out_of_bed", jsonStage.getString("stage"))
        assertEquals(record.getString("start"), jsonStage.getString("start"))
        assertFalse(record.toString().contains("provider-to-exclude"))
    }

    @Test
    fun localSnapshotsOnlyExposeShowerAndWeightMeasurements() = runBlocking {
        val millis = localMarch.toEpochMilli()
        val date = LocalDate.of(2026, 2, 26)
        val showerRows = listOf(ColdShowerEntity(7, millis, 95, 100, false))
        val showers = ShowerRepository(object : ShowerDao {
            override suspend fun insert(shower: ColdShowerEntity) = error("unused")
            override fun observeRecent(limit: Int) = flowOf(showerRows.take(limit))
            override fun observeAll() = flowOf(showerRows)
            override suspend fun delete(id: Long) = Unit
        })
        val weight = WeightRepository(object : WeightDao {
            override suspend fun upsert(entry: WeightEntryEntity) = Unit
            override suspend fun deleteDay(day: Long) = Unit
            override fun observeEntries() = flowOf(listOf(WeightEntryEntity(42, date.toEpochDay(), millis, 70.5)))
        })
        val months = LocalAnalyticsSnapshots(showers, weight).read(zone)
        assertEquals(setOf(february, march), months.keys)
        val records = months.getValue(march)
        assertEquals(setOf(AnalyticsStream.shower), records.keys)
        assertEquals(95, records.getValue(AnalyticsStream.shower).single().json.getInt("value"))
        assertEquals(setOf(AnalyticsStream.weight), months.getValue(february).keys)
        val weightRecord = months.getValue(february).getValue(AnalyticsStream.weight).single().json
        assertEquals(date.toString(), weightRecord.getString("date"))
        assertEquals(70.5, weightRecord.getDouble("value"), 0.0)
        for (month in months.values) for (stream in month.values) for (record in stream) {
            assertEquals(setOf("id", "date", "time", "value"), record.json.keys().asSequence().toSet())
        }
    }

    private fun batchJson(partition: AnalyticsPartition, records: Sequence<AnalyticsRecord>) =
        JSONObject(Buffer().also { writeAnalyticsBatch(it, partition, records.iterator()) }.readUtf8())
}
