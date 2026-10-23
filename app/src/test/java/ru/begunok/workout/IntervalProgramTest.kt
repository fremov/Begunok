package ru.begunok.workout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntervalProgramTest {
    private val level1 = IntervalProgram("1", "1", 60, 120, 8)

    @Test
    fun positions() {
        assertEquals(IntervalPosition(IntervalKind.Run, 1, 8, 60, 0), level1.positionAt(0))
        assertEquals(IntervalPosition(IntervalKind.Walk, 1, 8, 120, 1), level1.positionAt(60))
        assertEquals(IntervalPosition(IntervalKind.Run, 2, 8, 60, 2), level1.positionAt(180))
        assertEquals(IntervalPosition(IntervalKind.Walk, 8, 8, 1, 15), level1.positionAt(8 * 180 - 1))
        assertTrue(level1.positionAt(8 * 180).finished)
    }

    @Test
    fun continuous() {
        val p = IntervalProgram("5", "5", 1200, 0, 1)
        assertEquals(IntervalKind.Run, p.positionAt(1199).kind)
        assertTrue(p.positionAt(1200).finished)
        assertEquals("20 мин непрерывно", p.description)
    }
}
