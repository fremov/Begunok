package ru.begunok.workout

import ru.begunok.data.db.SampleEntity
import ru.begunok.data.db.WorkoutEntity
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale

object GpxWriter {
    fun write(workout: WorkoutEntity, samples: List<SampleEntity>, name: String): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append(
            """<gpx version="1.1" creator="Begunok" """ +
                """xmlns="http://www.topografix.com/GPX/1/1" """ +
                """xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1" """ +
                """xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" """ +
                """xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd """ +
                """http://www.garmin.com/xmlschemas/TrackPointExtension/v1 http://www.garmin.com/xmlschemas/TrackPointExtensionv1.xsd">"""
        ).append('\n')
        append("  <metadata><time>").append(time(workout.startTime)).append("</time></metadata>\n")
        append("  <trk>\n")
        append("    <name>").append(escape(name)).append("</name>\n")
        append("    <type>running</type>\n")
        for (segment in segments(samples)) {
            append("    <trkseg>\n")
            for (s in segment) {
                append("      <trkpt lat=\"").append(coord(s.latitude!!))
                append("\" lon=\"").append(coord(s.longitude!!)).append("\">")
                append("<time>").append(time(s.time)).append("</time>")
                if (s.heartRate != null) {
                    append("<extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>")
                    append(s.heartRate)
                    append("</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions>")
                }
                append("</trkpt>\n")
            }
            append("    </trkseg>\n")
        }
        append("  </trk>\n")
        append("</gpx>\n")
    }

    private fun segments(samples: List<SampleEntity>): List<List<SampleEntity>> {
        val result = mutableListOf<List<SampleEntity>>()
        var current = mutableListOf<SampleEntity>()
        for (s in samples) {
            if (s.paused) {
                if (current.isNotEmpty()) result += current
                current = mutableListOf()
                continue
            }
            if (s.latitude == null || s.longitude == null) continue
            current += s
        }
        if (current.isNotEmpty()) result += current
        return result
    }

    private fun coord(value: Double) = String.format(Locale.US, "%.7f", value)

    private fun time(millis: Long) = Instant.ofEpochMilli(millis).truncatedTo(ChronoUnit.SECONDS).toString()

    private fun escape(text: String) = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
