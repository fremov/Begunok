package ru.begunok.ui.history

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

internal data class ActivityColors(
    val training: Color,
    val study: Color,
    val reading: Color,
    val games: Color,
    val showers: Color,
    val steps: Color,
) {
    val all = listOf(training, study, reading, games, showers, steps)
}

@Composable
internal fun activityColors(): ActivityColors {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return remember(dark) {
        if (dark) ActivityColors(
            training = Color(0xFFFF8A50), study = Color(0xFF78ADFF), reading = Color(0xFF64D98B),
            games = Color(0xFFF5C451), showers = Color(0xFFB59AFF), steps = Color(0xFF54D9E4),
        ) else ActivityColors(
            training = Color(0xFFC4400B), study = Color(0xFF215DB9), reading = Color(0xFF17753E),
            games = Color(0xFF886000), showers = Color(0xFF7043BD), steps = Color(0xFF00717A),
        )
    }
}
