package com.stellarite.apps.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class ThemeId(val key: String, val label: String) {
    CYBER("cyber", "STELLARITE CYBER"),
    STEAM("steam", "BRASSWORKS STEAMPUNK");

    companion object {
        fun fromKey(key: String?): ThemeId = entries.firstOrNull { it.key == key } ?: CYBER
    }
}

private class Palette(
    val void: Color,
    val panel: Color,
    val panelRaised: Color,
    val nav: Color,
    val line: Color,
    val cardLine: Color,
    val line2: Color,
    val line3: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val installed: Color,
    val update: Color,
    val alert: Color,
    val decor: Color,
)

private val CyberPalette = Palette(
    void = Color(0xFF0C0F14), panel = Color(0xFF131820), panelRaised = Color(0xFF1A212B),
    nav = Color(0xFF0A0D12), line = Color(0xFF232B36), cardLine = Color(0xFF2A3A44),
    line2 = Color(0xFF2D3743), line3 = Color(0xFF3A4552), text = Color(0xFFE6E9EE),
    muted = Color(0xFF98A2B0), accent = Color(0xFF4DE8F4), installed = Color(0xFF4DE8F4),
    update = Color(0xFFF2384F), alert = Color(0xFFF2384F), decor = Color(0xFF17343C),
)

private val SteamPalette = Palette(
    void = Color(0xFF1B1410), panel = Color(0xFF2A1F17), panelRaised = Color(0xFF3A2B1E),
    nav = Color(0xFF140E0A), line = Color(0xFF5A4431), cardLine = Color(0xFF7A5F3C),
    line2 = Color(0xFF7A5F3C), line3 = Color(0xFFB08D57), text = Color(0xFFF0E3C8),
    muted = Color(0xFFBCA98A), accent = Color(0xFFD9824A), installed = Color(0xFF5FB3A1),
    update = Color(0xFFE3B24F), alert = Color(0xFFE8745F), decor = Color(0xFF2E2218),
)

/**
 * Live theme. All colors are derived from [theme], which is Compose state, so changing it
 * recomposes and redraws everything that read a color, including draw-phase lambdas.
 */
object SA {
    var theme: ThemeId by mutableStateOf(ThemeId.CYBER)

    val isSteam: Boolean get() = theme == ThemeId.STEAM
    private val p: Palette get() = if (isSteam) SteamPalette else CyberPalette

    val Void: Color get() = p.void
    val Panel: Color get() = p.panel
    val PanelRaised: Color get() = p.panelRaised
    val Nav: Color get() = p.nav
    val Line: Color get() = p.line
    val CardLine: Color get() = p.cardLine
    val Line2: Color get() = p.line2
    val Line3: Color get() = p.line3
    val Text: Color get() = p.text
    val Muted: Color get() = p.muted

    /** Primary interactive color: buttons, active tab, progress, focus. */
    val Accent: Color get() = p.accent
    /** Color for modules that are installed and current. */
    val Installed: Color get() = p.installed
    /** Color for "update available" and in-flight transfers. */
    val Update: Color get() = p.update
    /** Color for destructive or failed actions. */
    val Alert: Color get() = p.alert
    /** Faint decoration (orbit rings / gear outline). */
    val Decor: Color get() = p.decor

    val wordmark: Color get() = if (isSteam) Color(0xFFE8CB92) else p.text

    /** Font for small caps labels and numbers. */
    val monoFont: FontFamily get() = if (isSteam) FontFamily.Serif else FontFamily.Monospace
    /** Font for body copy. */
    val bodyFont: FontFamily get() = if (isSteam) FontFamily.Serif else FontFamily.Default

    val tileShape: Shape get() = if (isSteam) CircleShape else RoundedCornerShape(2.dp)
    val tileBorder: Dp get() = if (isSteam) 2.dp else 1.dp
    val controlShape: RoundedCornerShape get() = RoundedCornerShape(if (isSteam) 3.dp else 2.dp)
    /** Neon glow is a cyber-only effect. */
    val glow: Boolean get() = !isSteam
}

@Composable
fun StellariteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = SA.Accent,
            onPrimary = SA.Void,
            background = SA.Void,
            onBackground = SA.Text,
            surface = SA.Panel,
            onSurface = SA.Text,
            error = SA.Alert,
        ),
    ) {
        CompositionLocalProvider(
            LocalTextStyle provides LocalTextStyle.current.merge(TextStyle(fontFamily = SA.bodyFont, color = SA.Text)),
            content = content,
        )
    }
}
