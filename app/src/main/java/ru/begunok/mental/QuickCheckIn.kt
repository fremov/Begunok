package ru.begunok.mental

/** A momentary tension score. Zero is a measured answer, not missing data. */
data class QuickCheckIn(
    val id: Long = 0,
    val completedAt: Long,
    val tension: Int,
    val context: String? = null,
) {
    init {
        require(tension in 0..10) { "Tension must be in 0..10" }
    }
}
