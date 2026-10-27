package ru.begunok.ui.common

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

val LocalNavAnimatedScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedBounds(key: String): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val animated = LocalNavAnimatedScope.current ?: return this
    return with(shared) {
        this@sharedBounds.sharedBounds(rememberSharedContentState(key), animatedVisibilityScope = animated)
    }
}

@Composable
fun AnimatedNumber(text: String, style: TextStyle, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    Row(modifier) {
        text.forEachIndexed { i, char ->
            AnimatedContent(
                targetState = char,
                transitionSpec = {
                    val up = targetState > initialState
                    (slideInVertically { if (up) it else -it } + fadeIn()) togetherWith
                        (slideOutVertically { if (up) -it else it } + fadeOut())
                },
                label = "digit$i",
            ) { c -> Text(c.toString(), style = style, color = color) }
        }
    }
}

@Composable
fun BeatingHeart(bpm: Int?, color: Color, size: Dp, modifier: Modifier = Modifier) {
    val period = ((60_000 / (bpm ?: 60).coerceIn(30, 220))).coerceAtLeast(250)
    val transition = rememberInfiniteTransition(label = "heart")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = period
                1f at 0
                1.22f at (period * 0.12f).toInt()
                0.96f at (period * 0.28f).toInt()
                1.1f at (period * 0.4f).toInt()
                1f at (period * 0.6f).toInt()
            },
        ),
        label = "beat",
    )
    Icon(
        Icons.Filled.Favorite,
        contentDescription = null,
        tint = color,
        modifier = modifier
            .size(size)
            .graphicsLayer {
                val s = if (bpm == null) 1f else scale
                scaleX = s
                scaleY = s
            },
    )
}

@Composable
fun RingTimer(
    progress: Float,
    color: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
    stroke: Dp = 14.dp,
    content: @Composable () -> Unit,
) {
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), tween(900, easing = LinearEasing), label = "ring")
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val w = stroke.toPx()
            val inset = w / 2
            val arcSize = Size(size.minDimension - w, size.minDimension - w)
            val topLeft = Offset((size.width - arcSize.width) / 2, (size.height - arcSize.height) / 2)
            drawArc(trackColor, 0f, 360f, false, topLeft, arcSize, style = Stroke(w))
            drawArc(color, -90f, 360f * animated, false, topLeft, arcSize, style = Stroke(w, cap = StrokeCap.Round))
            if (animated > 0f && inset > 0f) {
                val angle = (-90f + 360f * animated) * PI.toFloat() / 180f
                val r = arcSize.width / 2
                drawCircle(Color.White, w * 0.32f, Offset(center.x + r * cos(angle), center.y + r * sin(angle)))
            }
        }
        content()
    }
}

fun Modifier.pressScale(interaction: MutableInteractionSource): Modifier = composed {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.94f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

fun Modifier.appear(index: Int): Modifier = composed {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index.coerceAtMost(12) * 45L)
        progress.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
    }
    graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 48f
    }
}

@Composable
fun rememberCountUp(target: Float, durationMs: Int = 1_100): Float {
    val value = remember { Animatable(0f) }
    LaunchedEffect(target) { value.animateTo(target, tween(durationMs, easing = FastOutSlowInEasing)) }
    return value.value
}

private class Particle(val x: Float, val vx: Float, val vy: Float, val spin: Float, val color: Color, val w: Float, val h: Float)

private val confettiColors = listOf(
    Color(0xFFFF6B2C), Color(0xFFC6F432), Color(0xFF3D8BFF), Color(0xFFFACC15), Color(0xFFFF4D9A), Color(0xFF22C55E),
)

@Composable
fun Confetti(trigger: Any?, modifier: Modifier = Modifier) {
    if (trigger == null) return
    val particles = remember(trigger) {
        val random = Random(trigger.hashCode())
        List(140) {
            Particle(
                x = random.nextFloat(),
                vx = (random.nextFloat() - 0.5f) * 0.5f,
                vy = 0.5f + random.nextFloat() * 0.9f,
                spin = (random.nextFloat() - 0.5f) * 1440f,
                color = confettiColors[random.nextInt(confettiColors.size)],
                w = 6f + random.nextFloat() * 8f,
                h = 10f + random.nextFloat() * 10f,
            )
        }
    }
    val time = remember(trigger) { Animatable(0f) }
    LaunchedEffect(trigger) { time.animateTo(1f, tween(3_200, easing = LinearEasing)) }
    if (time.value >= 1f) return
    Canvas(modifier) {
        val t = time.value
        particles.forEach { p ->
            val y = -0.1f + p.vy * t * 1.4f + 0.6f * t * t
            val x = p.x + p.vx * t
            if (y > 1.1f) return@forEach
            val alpha = if (t > 0.75f) (1f - t) / 0.25f else 1f
            val center = Offset(x * size.width, y * size.height)
            rotate(p.spin * t, center) {
                drawRect(
                    p.color.copy(alpha = alpha),
                    topLeft = Offset(center.x - p.w / 2, center.y - p.h / 2),
                    size = Size(p.w, p.h),
                )
            }
        }
    }
}
