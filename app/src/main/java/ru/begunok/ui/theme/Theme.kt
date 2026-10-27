package ru.begunok.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.begunok.R

object ZoneColors {
    val Below = Color(0xFF3D8BFF)
    val Normal = Color(0xFF22C55E)
    val Near = Color(0xFFFACC15)
    val Above = Color(0xFFFF4D4F)
}

object StageColors {
    val Warmup = Color(0xFF8B5CF6)
    val Run = Color(0xFFFF6B2C)
    val Walk = Color(0xFF3D8BFF)
    val Rest = Color(0xFF3D8BFF)
    val Work = Color(0xFFFF6B2C)
    val Done = Color(0xFF22C55E)
}

private fun condensed(weight: Int) = Font(
    R.font.roboto_condensed,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Condensed = FontFamily(condensed(400), condensed(500), condensed(600), condensed(700), condensed(800))

fun numberStyle(size: Int, weight: FontWeight = FontWeight.Bold) = TextStyle(
    fontFamily = Condensed,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = (size * 1.05f).sp,
    fontFeatureSettings = "tnum",
)

private val darkColors = darkColorScheme(
    primary = Color(0xFFFF6B2C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF4A1F0A),
    onPrimaryContainer = Color(0xFFFFDBCB),
    secondary = Color(0xFF6CB0FF),
    onSecondary = Color(0xFF00213F),
    secondaryContainer = Color(0xFF1B3352),
    onSecondaryContainer = Color(0xFFD3E4FF),
    tertiary = Color(0xFFC6F432),
    onTertiary = Color(0xFF1B2600),
    tertiaryContainer = Color(0xFF2F3D0C),
    onTertiaryContainer = Color(0xFFE2FF9C),
    background = Color(0xFF0B0E13),
    onBackground = Color(0xFFE8ECF2),
    surface = Color(0xFF0B0E13),
    onSurface = Color(0xFFE8ECF2),
    surfaceVariant = Color(0xFF232A35),
    onSurfaceVariant = Color(0xFF9AA4B2),
    surfaceContainerLowest = Color(0xFF07090D),
    surfaceContainerLow = Color(0xFF11151C),
    surfaceContainer = Color(0xFF151A22),
    surfaceContainerHigh = Color(0xFF1B212B),
    surfaceContainerHighest = Color(0xFF232A35),
    outline = Color(0xFF3A4350),
    outlineVariant = Color(0xFF29303B),
    error = Color(0xFFFF5A5F),
    onError = Color.White,
)

private val lightColors = lightColorScheme(
    primary = Color(0xFFE8541A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCB),
    onPrimaryContainer = Color(0xFF3A1100),
    secondary = Color(0xFF1F6FD1),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD6E6FF),
    onSecondaryContainer = Color(0xFF001C3A),
    tertiary = Color(0xFF4F6B00),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD4F36E),
    onTertiaryContainer = Color(0xFF161F00),
    background = Color(0xFFF5F6F8),
    onBackground = Color(0xFF12161C),
    surface = Color(0xFFF5F6F8),
    onSurface = Color(0xFF12161C),
    surfaceVariant = Color(0xFFE4E7EC),
    onSurfaceVariant = Color(0xFF5B6472),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFEFF1F4),
    surfaceContainerHigh = Color(0xFFE9ECF0),
    surfaceContainerHighest = Color(0xFFE2E6EB),
    outline = Color(0xFFB4BBC6),
    outlineVariant = Color(0xFFD5DAE1),
    error = Color(0xFFD92D33),
)

private val base = Typography()

private val typography = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = Condensed, fontWeight = FontWeight.Bold),
    displayMedium = base.displayMedium.copy(fontFamily = Condensed, fontWeight = FontWeight.Bold),
    displaySmall = base.displaySmall.copy(fontFamily = Condensed, fontWeight = FontWeight.Bold),
    headlineLarge = base.headlineLarge.copy(fontFamily = Condensed, fontWeight = FontWeight.Bold),
    headlineMedium = base.headlineMedium.copy(fontFamily = Condensed, fontWeight = FontWeight.Bold),
    headlineSmall = base.headlineSmall.copy(fontFamily = Condensed, fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontFamily = Condensed, fontWeight = FontWeight.SemiBold, fontSize = 24.sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = base.bodyLarge,
    bodyMedium = base.bodyMedium,
    bodySmall = base.bodySmall,
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = base.labelMedium,
    labelSmall = base.labelSmall,
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BegunokTheme(content: @Composable () -> Unit) {
    MaterialExpressiveTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColors else lightColors,
        motionScheme = MotionScheme.expressive(),
        typography = typography,
        shapes = shapes,
        content = content,
    )
}
