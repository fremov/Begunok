package ru.begunok.analytics

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.begunok.data.MentalRepository
import ru.begunok.data.ShowerRepository
import ru.begunok.data.WeightRepository
import ru.begunok.data.db.ColdShowerEntity
import ru.begunok.data.db.MentalDao
import ru.begunok.data.db.MentalResultEntity
import ru.begunok.data.db.QuickCheckInEntity
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
        val batch = AnalyticsBatch(partition, healthRecords(history, partition.stream, march, zone))
        val json = batch.json()
        val records = json.getJSONArray("records")
        assertEquals("2026-03", json.getString("month"))
        assertEquals(2, records.length())
        assertEquals("2026-03-01", records.getJSONObject(0).getString("date"))
        assertEquals("2026-03-01T01:30:00+03:00", records.getJSONObject(0).getString("time"))
        assertNotEquals(records.getJSONObject(0).getString("id"), records.getJSONObject(1).getString("id"))
        assertEquals(batch.digest, AnalyticsBatch(partition, healthRecords(history, partition.stream, march, zone)).digest)
        val cleared = AnalyticsBatch(partition, emptySequence())
        assertEquals(0, cleared.json().getJSONArray("records").length())
        assertNotEquals(batch.digest, cleared.digest)
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
    fun personalSnapshotsOnlyExposeMetricValuesAndDates() = runBlocking {
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
        val mental = MentalRepository(object : MentalDao {
            override fun observeResults() = flowOf(listOf(MentalResultEntity(4, "WHO5", millis, "2,3,4,1,5")))
            override fun observeCheckIns() = flowOf(listOf(QuickCheckInEntity(6, millis, -1, 0, -1, "private-note")))
            override suspend fun insert(result: MentalResultEntity) = Unit
            override suspend fun delete(id: Long) = Unit
            override suspend fun insertCheckIn(entry: QuickCheckInEntity) = Unit
            override suspend fun deleteCheckIn(id: Long) = Unit
        })
        val months = LocalAnalyticsSnapshots(showers, weight, mental).read(zone)
        val records = checkNotNull(months[march])
        assertEquals(60, records.getValue(AnalyticsStream.who5).single().json.getInt("value"))
        assertEquals(0, records.getValue(AnalyticsStream.tension).single().json.getInt("value"))
        assertEquals(95, records.getValue(AnalyticsStream.shower).single().json.getInt("value"))
        val weightRecord = months.getValue(february).getValue(AnalyticsStream.weight).single().json
        assertEquals(date.toString(), weightRecord.getString("date"))
        assertEquals(70.5, weightRecord.getDouble("value"), 0.0)
        for (month in months.values) for (stream in month.values) for (record in stream) {
            assertEquals(setOf("id", "date", "time", "value"), record.json.keys().asSequence().toSet())
        }
    }

    @Test
    fun configurationRestrictsCleartextAndFallback() {
        assertTrue(AnalyticsConfiguration.parse("https://private.example.net", "token", ANALYTICS_USB_ENDPOINT).supportsUsb)
        assertTrue(AnalyticsConfiguration.parse(ANALYTICS_USB_ENDPOINT, "token", null).supportsUsb)
        assertFalse(AnalyticsConfiguration.parse("https://private.example.net", "token", null).supportsUsb)
        assertThrows(IllegalArgumentException::class.java) {
            AnalyticsConfiguration.parse("http://private.example.net", "token", null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AnalyticsConfiguration.parse(ANALYTICS_USB_ENDPOINT, "token", "https://private.example.net")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AnalyticsConfiguration.parse(ANALYTICS_USB_ENDPOINT, "bad\r\nsecret", null)
        }
    }

    private fun AnalyticsBatch.json() = JSONObject(Buffer().also { writeTo(it) }.readUtf8())
}
