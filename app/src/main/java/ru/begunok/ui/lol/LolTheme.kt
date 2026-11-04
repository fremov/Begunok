package ru.begunok.ui.lol

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage

/** Палитра интерфейса Sintence (sintence-web/src/styles.css): тёмные панели, золото, синий и красный. */
object Lol {
    val Panel = Color(0xFF12161D)
    val PanelSoft = Color(0xFF1A212B)
    val Edge = Color(0xFF262D38)
    val Text = Color(0xFFE8ECF2)
    val Muted = Color(0xFF7D8899)
    val Order = Color(0xFF4B9DFF)
    val Chaos = Color(0xFFFF5F56)
    val Gold = Color(0xFFC8AA6E)
    val Danger = Color(0xFFFF8B80)
    val Ok = Color(0xFF4AD66D)
}

/** Оборачивает вкладку в палитру Sintence, остальное приложение остаётся в своей теме. */
@Composable
fun LolTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Lol.Gold, onPrimary = Lol.Panel,
            background = Lol.Panel, onBackground = Lol.Text,
            surface = Lol.PanelSoft, onSurface = Lol.Text,
            surfaceVariant = Lol.PanelSoft, onSurfaceVariant = Lol.Muted,
            error = Lol.Danger, outline = Lol.Edge,
            secondaryContainer = Lol.Edge, onSecondaryContainer = Lol.Text,
        ),
        typography = MaterialTheme.typography,
        content = content,
    )
}

@Composable
fun LolCard(modifier: Modifier = Modifier, accent: Color? = null, content: @Composable () -> Unit) {
    Card(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Lol.PanelSoft),
        border = BorderStroke(1.dp, accent?.copy(alpha = 0.55f) ?: Lol.Edge),
    ) { content() }
}

/** Заголовок раздела: мелкие золотые заглавные буквы, как в панелях Sintence. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier,
        color = Lol.Gold,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.2.sp,
    )
}

/** Картинка по ссылке (иконка чемпиона, предмета, ранга) на тёмной подложке, пока грузится. */
@Composable
fun Icon(url: String?, size: Dp, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(8.dp), border: Color? = null) {
    var m = modifier.size(size).clip(shape).background(Lol.Panel)
    if (border != null) m = m.border(1.5.dp, border, shape)
    Box(m) {
        if (url != null) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
    }
}

@Composable
fun RoundIcon(url: String?, size: Dp, border: Color = Lol.Gold) = Icon(url, size, shape = CircleShape, border = border)

/** Метка-таблетка: уровень, режим, «в игре». */
@Composable
fun Chip(text: String, color: Color = Lol.Gold, filled: Boolean = false) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier.clip(shape)
            .background(if (filled) color.copy(alpha = 0.18f) else Color.Transparent)
            .border(1.dp, color.copy(alpha = 0.6f), shape)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) { Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium) }
}

/** Полоса побед и поражений: зелёная часть — доля побед. */
@Composable
fun WinLossBar(wins: Int, losses: Int, modifier: Modifier = Modifier) {
    val total = (wins + losses).coerceAtLeast(1)
    Row(modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Lol.Edge)) {
        if (wins > 0) Box(Modifier.weight(wins.toFloat() / total).height(6.dp).background(Lol.Ok))
        if (losses > 0) Box(Modifier.weight(losses.toFloat() / total).height(6.dp).background(Lol.Chaos))
    }
}

fun teamColor(teamId: Int): Color = if (teamId == 100) Lol.Order else Lol.Chaos

@Composable
fun TeamHeader(teamId: Int) {
    Row(Modifier.padding(top = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(teamColor(teamId)))
        Text(if (teamId == 100) "Синяя команда" else "Красная команда", color = teamColor(teamId),
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

