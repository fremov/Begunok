package ru.begunok.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

object ZoneColors {
    val Below = Color(0xFF1E88E5)
    val Normal = Color(0xFF43A047)
    val Near = Color(0xFFFDD835)
    val Above = Color(0xFFE53935)
}

@Composable
fun BegunokTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFFEF5350))
        else -> lightColorScheme(primary = Color(0xFFD32F2F))
    }
    MaterialTheme(colorScheme = colors, content = content)
}
