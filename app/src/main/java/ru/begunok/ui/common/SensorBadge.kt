package ru.begunok.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Battery2Bar
import androidx.compose.material.icons.filled.Battery5Bar
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ru.begunok.sensor.SensorState
import ru.begunok.sensor.SensorStatus

fun SensorStatus.color(): Color = when (this) {
    SensorStatus.Connected, SensorStatus.Simulation -> Color(0xFF43A047)
    SensorStatus.Searching -> Color(0xFFFFA000)
    SensorStatus.Lost -> Color(0xFFE53935)
    else -> Color.Gray
}

@Composable
fun SensorBadge(state: SensorState, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(state.status.color())
        )
        Text(
            text = listOfNotNull(state.name, state.status.label).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
        )
        state.battery?.let { BatteryLabel(it) }
    }
}

@Composable
fun BatteryLabel(level: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = when {
                level >= 70 -> Icons.Filled.BatteryFull
                level >= 30 -> Icons.Filled.Battery5Bar
                else -> Icons.Filled.Battery2Bar
            },
            contentDescription = "Батарея датчика",
            modifier = Modifier.size(18.dp),
            tint = if (level < 20) Color(0xFFE53935) else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("$level%", style = MaterialTheme.typography.bodyMedium)
    }
}
