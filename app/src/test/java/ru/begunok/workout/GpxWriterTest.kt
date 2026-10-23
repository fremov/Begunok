package ru.begunok.workout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.begunok.data.db.SampleEntity
import ru.begunok.data.db.WorkoutEntity

class GpxWriterTest {
    private fun sample(t: Long, lat: Double?, hr: Int?, paused: Boolean = false) =
        SampleEntity(workoutId = 1, time = t, heartRate = hr, latitude = lat, longitude = lat?.let { 37.5 }, accuracy = 5f, speed = null, paused = paused)

    @Test
    fun writesSegmentsWithHeartRate() {
        val workout = WorkoutEntity(id = 1, startTime = 0, upperLimit = 145)
        val gpx = GpxWriter.write(
            workout,
            listOf(
                sample(0, 55.75, 120),
                sample(1000, 55.7501, null),
                sample(2000, 55.7502, 130, paused = true),
                sample(3000, null, 131),
                sample(4000, 55.7503, 132),
            ),
            "Бег & <тест>",
        )
        assertEquals(2, Regex("<trkseg>").findAll(gpx).count())
        assertEquals(3, Regex("<trkpt ").findAll(gpx).count())
        assertTrue(gpx.contains("""<trkpt lat="55.7500000" lon="37.5000000"><time>1970-01-01T00:00:00Z</time>"""))
        assertTrue(gpx.contains("<gpxtpx:hr>120</gpxtpx:hr>"))
        assertTrue(gpx.contains("<gpxtpx:hr>132</gpxtpx:hr>"))
        assertFalse(gpx.contains("<gpxtpx:hr>130</gpxtpx:hr>"))
        assertTrue(gpx.contains("<name>Бег &amp; &lt;тест&gt;</name>"))
    }
}
