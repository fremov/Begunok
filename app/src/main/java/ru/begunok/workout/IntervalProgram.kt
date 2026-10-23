package ru.begunok.workout

enum class IntervalKind { Run, Walk }

data class IntervalProgram(
    val id: String,
    val name: String,
    val runSec: Int,
    val walkSec: Int,
    val repeats: Int,
) {
    val isContinuous: Boolean get() = walkSec == 0 && repeats == 1

    val totalSec: Int get() = (runSec + walkSec) * repeats

    val description: String
        get() = if (isContinuous) {
            "${formatSpan(runSec)} непрерывно"
        } else {
            "${formatSpan(runSec)} бег / ${formatSpan(walkSec)} шаг × $repeats"
        }

    fun positionAt(activeSec: Long): IntervalPosition {
        var start = 0L
        for (i in 0 until repeats) {
            val runEnd = start + runSec
            if (activeSec < runEnd) {
                return IntervalPosition(IntervalKind.Run, i + 1, repeats, (runEnd - activeSec).toInt(), segmentIndex = i * 2)
            }
            val walkEnd = runEnd + walkSec
            if (activeSec < walkEnd) {
                return IntervalPosition(IntervalKind.Walk, i + 1, repeats, (walkEnd - activeSec).toInt(), segmentIndex = i * 2 + 1)
            }
            start = walkEnd
        }
        return IntervalPosition(null, repeats, repeats, 0, segmentIndex = repeats * 2)
    }

    companion object {
        val defaults = listOf(
            IntervalProgram("level1", "Уровень 1", 60, 120, 8),
            IntervalProgram("level2", "Уровень 2", 120, 60, 7),
            IntervalProgram("level3", "Уровень 3", 240, 60, 4),
            IntervalProgram("level4", "Уровень 4", 540, 120, 2),
            IntervalProgram("level5", "Уровень 5", 1200, 0, 1),
            IntervalProgram("level6", "Уровень 6", 1500, 0, 1),
        )
    }
}

data class IntervalPosition(
    val kind: IntervalKind?,
    val repeat: Int,
    val repeats: Int,
    val remainingSec: Int,
    val segmentIndex: Int,
) {
    val finished: Boolean get() = kind == null
}

fun formatSpan(sec: Int): String = when {
    sec % 60 == 0 -> "${sec / 60} мин"
    sec < 60 -> "$sec с"
    else -> "${sec / 60} мин ${sec % 60} с"
}
