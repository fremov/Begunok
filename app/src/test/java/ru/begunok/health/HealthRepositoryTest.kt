package ru.begunok.health

import androidx.health.connect.client.records.metadata.DataOrigin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HealthRepositoryTest {
    private val band = DataOrigin("nodomain.freeyourgadget.gadgetbridge")
    private val phone = DataOrigin("android")
    private val formerBand = DataOrigin("mi.fitness")

    @Test
    fun usesTheLargestSingleAppTotalEvenWhenTheSourcesCoverDifferentPartsOfTheWindow() = runBlocking {
        val counts = mapOf(band to 6200L, phone to 4800L, formerBand to 5300L)
        val requested = mutableListOf<DataOrigin>()
        val total = maxStepsBySource(counts.keys) { origin ->
            requested.add(origin)
            counts[origin]
        }
        assertEquals(counts.keys, requested.toSet())
        assertEquals(StepSourceTotal(band.packageName, 6200L), total)
    }

    @Test
    fun distinguishesMissingCountsFromMeasuredZero() = runBlocking {
        val origins = setOf(band, phone)
        assertNull(maxStepsBySource(origins) { null })
        assertEquals(StepSourceTotal(band.packageName, 0L), maxStepsBySource(origins) { if (it == band) 0L else null })
    }

    @Test
    fun tiedSourcesProduceAStableWinner() = runBlocking {
        val winner = StepSourceTotal(phone.packageName, 4600L)
        assertEquals(winner, maxStepsBySource(linkedSetOf(band, phone)) { 4600L })
        assertEquals(winner, maxStepsBySource(linkedSetOf(phone, band)) { 4600L })
    }
}
