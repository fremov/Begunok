package ru.begunok.workout

enum class HrZone { Below, Normal, Above }

fun hrZone(heartRate: Int, upperLimit: Int, lowerLimit: Int?): HrZone = when {
    heartRate > upperLimit -> HrZone.Above
    lowerLimit != null && heartRate < lowerLimit -> HrZone.Below
    else -> HrZone.Normal
}
