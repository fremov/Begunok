package ru.begunok.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HealthRepositoryTest {
    @Test
    fun cadenceFromSteps() {
        assertEquals(170, HealthRepository.cadence(3400, 20 * 60_000L))
        assertNull(HealthRepository.cadence(null, 20 * 60_000L))
        assertNull(HealthRepository.cadence(100, 30_000L))
    }
}
