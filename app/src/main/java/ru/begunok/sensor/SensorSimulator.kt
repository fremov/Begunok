package ru.begunok.sensor

import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlin.random.Random

class SensorSimulator {
    suspend fun run(onMeasurement: (HeartRateMeasurement) -> Unit) {
        var current = 120.0
        var target = Random.nextDouble(MIN, MAX)
        var secondsToNewTarget = Random.nextInt(20, 60)
        while (true) {
            if (--secondsToNewTarget <= 0) {
                target = Random.nextDouble(MIN, MAX)
                secondsToNewTarget = Random.nextInt(20, 60)
            }
            current += (target - current) * 0.06 + Random.nextDouble(-0.8, 0.8)
            current = current.coerceIn(MIN, MAX)
            val bpm = current.roundToInt()
            val rr = buildList {
                var elapsed = 0
                while (elapsed < 1000) {
                    val interval = (60_000.0 / bpm + Random.nextDouble(-15.0, 15.0)).roundToInt()
                    add(interval)
                    elapsed += interval
                }
            }
            onMeasurement(HeartRateMeasurement(bpm, rr))
            delay(1_000)
        }
    }

    private companion object {
        const val MIN = 110.0
        const val MAX = 170.0
    }
}
