package ru.begunok.workout

import ru.begunok.data.db.ExerciseSetEntity

internal class Repetitions {
    var upper = 0L
        private set
    var lower = 0L
        private set

    companion object {
        private data class Rule(val lower: Boolean, val perSide: Boolean)

        private val rules = buildMap<String, Rule> {
            fun register(group: Ladder, lower: Boolean) {
                for (exercise in group.variations) {
                    if (!exercise.timed) put(exercise.id, Rule(lower, exercise.perSide))
                }
            }
            for (group in ExerciseCatalog.ladders) {
                when (group.id) {
                    "legs" -> register(group, true)
                    "pull", "dips", "push", "core" -> register(group, false)
                }
            }
            for (group in ExerciseCatalog.groups(DayType.StrengthUpper)) register(group, false)
            for (group in ExerciseCatalog.groups(DayType.StrengthLower)) register(group, true)
        }

        // The saved exercise identifies the movement even for substitutions and strength blocks in runs.
        // Set completion stores the entered reps per side, not the sum of both sides.
        fun byWorkout(sets: List<ExerciseSetEntity>): Map<Long, Repetitions> = buildMap {
            for (set in sets) {
                if (set.timed || set.reps <= 0) continue
                val variation = set.exerciseId.substringAfter(':').removePrefix("c_").removePrefix("s_")
                val rule = rules[variation] ?: continue
                val reps = set.reps.toLong() * if (rule.perSide) 2 else 1
                val totals = getOrPut(set.workoutId) { Repetitions() }
                if (rule.lower) totals.lower += reps else totals.upper += reps
            }
        }
    }
}
