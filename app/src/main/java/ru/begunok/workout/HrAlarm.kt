package ru.begunok.workout

enum class HrAlert { High, Low }

class HrAlarm(
    private val holdMs: Long = 5_000,
    private val repeatMs: Long = 30_000,
    private val hysteresis: Int = 3,
) {
    private val high = Side()
    private val low = Side()

    private class Side {
        var outsideSince: Long? = null
        var active = false
        var lastAlertAt: Long? = null

        fun reset() {
            outsideSince = null
            active = false
            lastAlertAt = null
        }
    }

    fun reset() {
        high.reset()
        low.reset()
    }

    fun update(nowMs: Long, heartRate: Int?, upperLimit: Int, lowerLimit: Int?, lowEnabled: Boolean = true): HrAlert? {
        if (heartRate == null) {
            high.outsideSince = null
            low.outsideSince = null
            return null
        }
        val highAlert = check(
            side = high,
            nowMs = nowMs,
            outside = heartRate > upperLimit,
            recovered = heartRate < upperLimit - hysteresis,
        )
        if (highAlert) return HrAlert.High

        if (lowerLimit == null || !lowEnabled) {
            low.reset()
            return null
        }
        val lowAlert = check(
            side = low,
            nowMs = nowMs,
            outside = heartRate < lowerLimit,
            recovered = heartRate > lowerLimit + hysteresis,
        )
        return if (lowAlert) HrAlert.Low else null
    }

    private fun check(side: Side, nowMs: Long, outside: Boolean, recovered: Boolean): Boolean {
        if (!outside) {
            side.outsideSince = null
            if (recovered) side.reset()
            return false
        }
        val since = side.outsideSince ?: nowMs.also { side.outsideSince = it }
        if (!side.active) {
            if (nowMs - since < holdMs) return false
            side.active = true
            side.lastAlertAt = nowMs
            return true
        }
        val last = side.lastAlertAt
        if (last == null || nowMs - last >= repeatMs) {
            side.lastAlertAt = nowMs
            return true
        }
        return false
    }
}
