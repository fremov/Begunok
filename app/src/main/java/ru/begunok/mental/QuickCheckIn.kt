package ru.begunok.mental

/** A momentary observation: each measure is independent, and zero is a real answer. */
data class QuickCheckIn(
    val id: Long = 0,
    val completedAt: Long,
    val mood: Int,
    val tension: Int,
    val energy: Int,
    val context: String? = null,
) {
    init {
        require(mood in 0..10) { "Mood must be in 0..10" }
        require(tension in 0..10) { "Tension must be in 0..10" }
        require(energy in 0..10) { "Energy must be in 0..10" }
    }
}
