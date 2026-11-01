package com.cybertxts.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private class Palette(
    val void: Color, val panel: Color, val panelAlt: Color, val primary: Color, val muted: Color,
    val primaryDeep: Color, val ink: Color, val inkDim: Color, val accent: Color, val danger: Color,
    val mine: Color, val theirs: Color, val star: Color
)

/** Night ops: deep space, neon cyan for you, crimson for everyone else. */
private val NIGHT = Palette(
    void = Color(0xFF07060F), panel = Color(0xFF0D1022), panelAlt = Color(0xFF150A18),
    primary = Color(0xFF00E5FF), muted = Color(0xFF9AA6CC), primaryDeep = Color(0xFF0A2A36),
    ink = Color(0xFFE6F1FF), inkDim = Color(0xFF9AA6CC), accent = Color(0xFFFF2A4D), danger = Color(0xFFFF5470),
    mine = Color(0xFF08141D), theirs = Color(0xFF150A18), star = Color(0xFFE6F1FF)
)

/** Day shift: cool white station lighting, deeper cyan and crimson so text stays readable. */
private val DAY = Palette(
    void = Color(0xFFEEF2FA), panel = Color(0xFFFFFFFF), panelAlt = Color(0xFFFBEEF1),
    primary = Color(0xFF00708F), muted = Color(0xFF4A5578), primaryDeep = Color(0xFFD7F1F8),
    ink = Color(0xFF0B1020), inkDim = Color(0xFF4A5578), accent = Color(0xFFC20F2F), danger = Color(0xFFC20F2F),
    mine = Color(0xFFE6F6FB), theirs = Color(0xFFFCEEF1), star = Color(0xFF8A94B8)
)

/**
 * The app's colours. They are Compose state, so switching between Night ops and Day shift
 * recolours every screen instantly without restarting.
 */
object Holo {
    var Void by mutableStateOf(NIGHT.void); private set
    var Panel by mutableStateOf(NIGHT.panel); private set
    var PanelAlt by mutableStateOf(NIGHT.panelAlt); private set
    var Primary by mutableStateOf(NIGHT.primary); private set
    var Muted by mutableStateOf(NIGHT.muted); private set
    var PrimaryDeep by mutableStateOf(NIGHT.primaryDeep); private set
    var Ink by mutableStateOf(NIGHT.ink); private set
    var InkDim by mutableStateOf(NIGHT.inkDim); private set
    var Accent by mutableStateOf(NIGHT.accent); private set
    var Danger by mutableStateOf(NIGHT.danger); private set
    /** Fill behind your own messages, and behind everyone else's. */
    var Mine by mutableStateOf(NIGHT.mine); private set
    var Theirs by mutableStateOf(NIGHT.theirs); private set
    var Star by mutableStateOf(NIGHT.star); private set
    var isDark by mutableStateOf(true); private set

    val Backdrop: Brush get() = Brush.verticalGradient(listOf(Void, Void))

    fun apply(dark: Boolean) {
        val p = if (dark) NIGHT else DAY
        if (dark == isDark && Void == p.void) return
        Void = p.void; Panel = p.panel; PanelAlt = p.panelAlt; Primary = p.primary; Muted = p.muted
        PrimaryDeep = p.primaryDeep; Ink = p.ink; InkDim = p.inkDim; Accent = p.accent; Danger = p.danger
        Mine = p.mine; Theirs = p.theirs; Star = p.star; isDark = dark
    }
}

/*
 * Fonts (all SIL Open Font License, downloaded into res/font by the GitHub build):
 *   Orbitron        - node codes and titles
 *   Rajdhani        - message text and body copy
 *   Share Tech Mono - log lines, readouts and buttons (IMG, VOX, TX...)
 */
val Display = FontFamily(
    Font(R.font.orbitron_medium, FontWeight.Medium),
    Font(R.font.orbitron_bold, FontWeight.Bold)
)
val Body = FontFamily(
    Font(R.font.rajdhani_medium, FontWeight.Normal),
    Font(R.font.rajdhani_medium, FontWeight.Medium),
    Font(R.font.rajdhani_semibold, FontWeight.SemiBold),
    Font(R.font.rajdhani_bold, FontWeight.Bold)
)
val Mono = FontFamily(Font(R.font.share_tech_mono, FontWeight.Normal))

private fun Typography.holoFonts(): Typography = copy(
    displayLarge = displayLarge.copy(fontFamily = Display),
    displayMedium = displayMedium.copy(fontFamily = Display),
    displaySmall = displaySmall.copy(fontFamily = Display),
    headlineLarge = headlineLarge.copy(fontFamily = Display),
    headlineMedium = headlineMedium.copy(fontFamily = Display),
    headlineSmall = headlineSmall.copy(fontFamily = Display),
    titleLarge = titleLarge.copy(fontFamily = Display),
    titleMedium = titleMedium.copy(fontFamily = Body),
    titleSmall = titleSmall.copy(fontFamily = Body),
    bodyLarge = bodyLarge.copy(fontFamily = Body),
    bodyMedium = bodyMedium.copy(fontFamily = Body),
    bodySmall = bodySmall.copy(fontFamily = Body),
    labelLarge = labelLarge.copy(fontFamily = Mono),
    labelMedium = labelMedium.copy(fontFamily = Mono),
    labelSmall = labelSmall.copy(fontFamily = Mono)
)

@Composable
fun HoloTheme(content: @Composable () -> Unit) {
    val scheme = if (Holo.isDark) darkColorScheme(
        primary = Holo.Primary, onPrimary = Holo.Void,
        primaryContainer = Holo.PrimaryDeep, onPrimaryContainer = Holo.Ink,
        secondary = Holo.Accent, onSecondary = Holo.Void,
        background = Holo.Void, onBackground = Holo.Ink,
        surface = Holo.Panel, onSurface = Holo.Ink,
        surfaceVariant = Holo.PanelAlt, onSurfaceVariant = Holo.Ink,
        outline = Holo.Primary, error = Holo.Danger
    ) else lightColorScheme(
        primary = Holo.Primary, onPrimary = Holo.Void,
        primaryContainer = Holo.PrimaryDeep, onPrimaryContainer = Holo.Ink,
        secondary = Holo.Accent, onSecondary = Holo.Void,
        background = Holo.Void, onBackground = Holo.Ink,
        surface = Holo.Panel, onSurface = Holo.Ink,
        surfaceVariant = Holo.PanelAlt, onSurfaceVariant = Holo.Ink,
        outline = Holo.Primary, error = Holo.Danger
    )
    MaterialTheme(colorScheme = scheme, typography = remember { Typography().holoFonts() }, content = content)
}

@Composable
fun holoFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Holo.Ink,
    unfocusedTextColor = Holo.Ink,
    focusedBorderColor = Holo.Primary,
    unfocusedBorderColor = Holo.Primary.copy(alpha = 0.4f),
    focusedLabelColor = Holo.Primary,
    unfocusedLabelColor = Holo.InkDim,
    cursorColor = Holo.Primary,
    focusedPlaceholderColor = Holo.InkDim,
    unfocusedPlaceholderColor = Holo.InkDim,
    focusedContainerColor = Holo.Void.copy(alpha = 0.7f),
    unfocusedContainerColor = Holo.Void.copy(alpha = 0.7f)
)

/** Button shape with two cut corners (top-left, bottom-right). */
fun cutShape(c: Dp = 8.dp) = CutCornerShape(topStart = c, bottomEnd = c)

/** Single cut corner on the top-left (your side) or top-right (everyone else). */
fun notchShape(left: Boolean, c: Dp = 14.dp) =
    if (left) CutCornerShape(topStart = c) else CutCornerShape(topEnd = c)

/**
 * A holo panel with one clipped corner whose outline is left OPEN at the cut (no slanted line).
 * left = true cuts the top-left corner, otherwise the top-right.
 */
fun Modifier.openPanel(fill: Color, stroke: Color, left: Boolean = false, cut: Dp = 14.dp, width: Dp = 1.dp): Modifier =
    this.clip(notchShape(left, cut))
        .background(fill)
        .drawBehind {
            val c = cut.toPx()
            val w = size.width
            val h = size.height
            val sw = width.toPx()
            val half = sw / 2
            // top edge stops short of the cut
            if (left) drawLine(stroke, Offset(c, half), Offset(w, half), sw)
            else drawLine(stroke, Offset(0f, half), Offset(w - c, half), sw)
            // right edge
            drawLine(stroke, Offset(w - half, if (left) 0f else c), Offset(w - half, h), sw)
            // bottom edge
            drawLine(stroke, Offset(0f, h - half), Offset(w, h - half), sw)
            // left edge
            drawLine(stroke, Offset(half, if (left) c else 0f), Offset(half, h), sw)
        }

/** Plain outlined box with cut corners and open gaps, for IMG / VOX style buttons. */
fun Modifier.openButton(stroke: Color, cut: Dp = 7.dp): Modifier = this.openPanel(Color.Transparent, stroke, left = true, cut = cut)

/**
 * The CyberTxTs mark: a planet with an orbit ring and a moon. With spin = true the moon circles slowly
 * (used as a "linked" indicator).
 */
@Composable
fun Orbit(dimension: Dp, color: Color, modifier: Modifier = Modifier, spin: Boolean = false) {
    val angle = if (spin) {
        rememberInfiniteTransition(label = "orbit").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Restart),
            label = "orbitAngle"
        ).value
    } else 300f
    val moon = Holo.Accent

    Canvas(modifier.size(dimension)) {
        val c = center
        val r = size.minDimension / 2f
        drawCircle(color, radius = r * 0.92f, center = c, style = Stroke(width = r * 0.07f), alpha = 0.45f)
        drawCircle(color, radius = r * 0.42f, center = c, style = Stroke(width = r * 0.12f))
        drawCircle(color, radius = r * 0.12f, center = c)
        rotate(angle, c) {
            drawCircle(moon, radius = r * 0.15f, center = Offset(c.x + r * 0.92f, c.y))
        }
    }
}

/** Faint fixed starfield for backgrounds. */
@Composable
fun Starfield(modifier: Modifier = Modifier) {
    val star = Holo.Star
    val ring1 = Holo.Accent
    val ring2 = Holo.Primary
    Canvas(modifier) {
        val pts = listOf(
            0.08f to 0.17f, 0.23f to 0.25f, 0.9f to 0.2f, 0.71f to 0.31f, 0.38f to 0.38f, 0.95f to 0.46f,
            0.06f to 0.56f, 0.54f to 0.62f, 0.85f to 0.7f, 0.15f to 0.76f, 0.49f to 0.83f, 0.77f to 0.11f,
            0.31f to 0.11f, 0.64f to 0.5f, 0.26f to 0.66f
        )
        pts.forEachIndexed { i, (x, y) ->
            drawCircle(star, radius = if (i % 3 == 0) 1.6f else 1.1f, center = Offset(size.width * x, size.height * y), alpha = 0.35f + (i % 4) * 0.1f)
        }
        val oc = Offset(size.width * 0.92f, size.height * 0.27f)
        drawCircle(ring1, radius = 90.dp.toPx(), center = oc, style = Stroke(1f), alpha = 0.14f)
        drawCircle(ring2, radius = 120.dp.toPx(), center = oc, style = Stroke(1f), alpha = 0.07f)
    }
}
