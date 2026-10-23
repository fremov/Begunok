package ru.begunok.workout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutTrackerTest {
    private val metersPerDegreeLat = 111_195.0

    private fun fix(sec: Int, northM: Double, accuracy: Float = 5f) =
        Fix(sec * 1000L, northM / metersPerDegreeLat, 37.0, accuracy)

    @Test
    fun accumulatesDistanceAndTime() {
        val t = WorkoutTracker()
        for (s in 0..20) {
            t.onFix(fix(s, s * 3.0))
            t.tick(s * 1000L, 150)
        }
        assertEquals(60.0, t.distanceM, 0.5)
        assertEquals(20_000L, t.activeMs)
        assertEquals(150, t.avgHr)
        assertNotNull(t.currentSpeedMps)
        assertEquals(3.0, t.currentSpeedMps!!, 0.1)
    }

    @Test
    fun rejectsInaccurateFixes() {
        val t = WorkoutTracker()
        assertTrue(t.onFix(fix(0, 0.0)))
        assertFalse(t.onFix(fix(1, 3.0, accuracy = 25f)))
        assertEquals(0.0, t.distanceM, 0.0)
    }

    @Test
    fun rejectsJumps() {
        val t = WorkoutTracker()
        t.onFix(fix(0, 0.0))
        assertFalse(t.onFix(fix(1, 50.0)))
        assertTrue(t.onFix(fix(2, 6.0)))
        assertEquals(6.0, t.distanceM, 0.1)
    }

    @Test
    fun noDistanceWhilePaused() {
        val t = WorkoutTracker()
        t.tick(0, null)
        t.onFix(fix(0, 0.0))
        t.onFix(fix(5, 15.0))
        t.pause(5_000)
        t.onFix(fix(10, 30.0))
        t.tick(60_000, null)
        t.resume(60_000)
        t.onFix(fix(61, 300.0))
        t.onFix(fix(62, 304.0))
        t.tick(62_000, null)
        assertEquals(19.0, t.distanceM, 0.2)
        assertEquals(7_000L, t.activeMs)
    }

    @Test
    fun speedUnknownWithoutGps() {
        val t = WorkoutTracker()
        for (s in 0..10) t.tick(s * 1000L, null)
        assertNull(t.currentSpeedMps)
    }
}
