package com.chronos.ui.common

import com.chronos.ui.*
import com.chronos.ui.setup.*
import com.chronos.ui.horizon.*
import com.chronos.ui.day.*
import com.chronos.ui.log.*
import com.chronos.ui.navigator.*

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * An instrument panel rather than a productivity app: cool paper, steel ink,
 * and colour reserved for goals and for warnings. Nothing decorative competes
 * with the two lines on the day plot, which are the point of the screen.
 */
object Palette {
    /**
     * Whether the dark theme is on (your request of 29 Oct 2026). Every colour below has a light and a
     * dark version: on the night paper the same hues come lighter and a little softer, so a goal keeps
     * its hue in both and the accents stay as far apart from each other as by day.
     */
    var dark by mutableStateOf(false)

    private fun pick(light: Long, dark: Long) = Color(if (this.dark) dark else light)

    val Paper get() = pick(0xFFF1F4F5, 0xFF11181C)
    val Panel get() = pick(0xFFFFFFFF, 0xFF1A2329)
    val Ink get() = pick(0xFF14202B, 0xFFE4ECEF)
    val Muted get() = pick(0xFF5C7180, 0xFF91A6B2)
    val Rule get() = pick(0xFFD3DCE0, 0xFF33434C)
    val Steel get() = pick(0xFF2F5D7C, 0xFF7DB2D6)
    val Moss get() = pick(0xFF5E8C6A, 0xFF86C095)
    val Brick get() = pick(0xFFA8452F, 0xFFEC8C74)
    val Sand get() = pick(0xFFB08D2A, 0xFFE3C060)

    /** Section accents for the Setup wizard: one hue per step, all from the goal palette. */
    val Indigo get() = pick(0xFF4A4E8C, 0xFFA3A7EC)
    val Plum get() = pick(0xFF8A6A9E, 0xFFCDA9E0)
    val Ochre get() = pick(0xFFB07A3C, 0xFFE8AE6E)
    val Teal get() = pick(0xFF3F7F87, 0xFF74C3CB)

    /** Words on a filled colour block: white by day; by night the fills are light, so near-black. */
    val OnFill get() = pick(0xFFFFFFFF, 0xFF0E1418)

    val PaperDark = Color(0xFF11181C)
    val PanelDark = Color(0xFF1A2329)
    val InkDark = Color(0xFFE4ECEF)

    /** Goal colours, assigned by position so a goal keeps its colour everywhere. */
    val goals get() = if (dark) listOf(
        Color(0xFFA3A7EC), Color(0xFF7DB2D6), Color(0xFF86C095), Color(0xFFCDA9E0),
        Color(0xFFE8AE6E), Color(0xFF74C3CB), Color(0xFFE29AAC)
    ) else listOf(
        Color(0xFF4A4E8C), Color(0xFF2F5D7C), Color(0xFF5E8C6A), Color(0xFF8A6A9E),
        Color(0xFFB07A3C), Color(0xFF3F7F87), Color(0xFF9E5A6B)
    )

    fun goalColor(index: Int): Color =
        if (index < 0) pick(0xFF93A7B2, 0xFF7E929C) else goals[index % goals.size]

    /**
     * A colour made for the day paper, as it should look on the night paper: the same hue, lighter
     * and a touch less saturated, so dark goal tones do not sink into the background.
     */
    fun forNight(c: Color): Color {
        if (!dark) return c
        val hsl = FloatArray(3)
        androidx.core.graphics.ColorUtils.colorToHSL(android.graphics.Color.argb(
            (c.alpha * 255).toInt(), (c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt()), hsl)
        return Color.hsl(hsl[0], (hsl[1] * 0.85f).coerceIn(0f, 1f), (0.42f + hsl[2] * 0.48f).coerceIn(0f, 0.86f), c.alpha)
    }
}

/** How the app looks: as the phone is set, or always light, or always dark (29 Oct 2026). */
enum class ThemeMode(val code: String, val label: String) {
    SYSTEM("system", "As the phone"), LIGHT("light", "Light"), DARK("dark", "Dark")
}

/**
 * Colours for goals and for the activities under them (your request of 22 Oct 2026).
 *
 * Each goal (as you see it: an umbrella) gets its own hue, the hues taken in an order that
 * keeps neighbours far apart on the colour wheel. Each activity under a
 * goal is a shade of that hue — a little lighter or darker, a few degrees warmer or cooler —
 * so two activities of one goal read as one family yet apart, and activities of different
 * goals never look alike. Internally a goal activity is the target of one Goal row whose
 * groupName is the goal you see; that is what the functions here take.
 */
object GoalColors {
    private val NEUTRAL get() = Palette.goalColor(-1)

    private fun groupOf(g: com.chronos.model.Goal) = g.groupName ?: g.name ?: ""

    /** The goals in the order they first appear, which is the order Setup shows them. */
    fun groups(goals: List<com.chronos.model.Goal>): List<String> = goals.map { groupOf(it) }.distinct()

    /**
     * Twelve goal colours, each differing from its neighbours in hue AND in lightness, so even
     * eleven goals stay apart (seen on the phone, 22 Oct 2026: hue alone left three reds).
     * Upkeep's green is left out; past twelve the round repeats a few degrees over.
     */
    private val TONES = arrayOf(
        floatArrayOf(218f, 0.55f, 0.45f), floatArrayOf(350f, 0.62f, 0.46f), floatArrayOf(278f, 0.45f, 0.52f),
        floatArrayOf(186f, 0.60f, 0.34f), floatArrayOf(318f, 0.48f, 0.58f), floatArrayOf(8f, 0.72f, 0.33f),
        floatArrayOf(52f, 0.65f, 0.40f), floatArrayOf(246f, 0.50f, 0.64f), floatArrayOf(200f, 0.70f, 0.48f),
        floatArrayOf(192f, 0.40f, 0.64f), floatArrayOf(298f, 0.42f, 0.32f), floatArrayOf(335f, 0.60f, 0.70f))

    private fun tone(i: Int): FloatArray = TONES[i % TONES.size].let { t ->
        floatArrayOf((t[0] + (i / TONES.size) * 9f) % 360f, t[1], t[2]) }

    private fun hue(i: Int) = tone(i)[0]

    /** A goal's own colour, from its place among the goals. */
    fun group(goals: List<com.chronos.model.Goal>, name: String?): Color {
        val i = groups(goals).indexOf(name ?: return NEUTRAL)
        return if (i < 0) NEUTRAL else tone(i).let { Palette.forNight(Color.hsl(it[0], it[1], it[2])) }
    }

    /** One activity's colour: its goal's hue, shaded by its place under that goal. */
    fun target(goals: List<com.chronos.model.Goal>, targetId: String?): Color {
        val t = goals.firstOrNull { it.id == targetId } ?: return NEUTRAL
        val name = groupOf(t)
        val i = groups(goals).indexOf(name)
        if (i < 0) return NEUTRAL
        val siblings = goals.filter { groupOf(it) == name }
        val base = tone(i)
        if (siblings.size <= 1) return Palette.forNight(Color.hsl(base[0], base[1], base[2]))
        val k = siblings.indexOfFirst { it.id == targetId }
        // 0, +1, −1, +2, −2 … steps away from the goal's own colour.
        val step = if (k == 0) 0 else if (k % 2 == 1) (k + 1) / 2 else -(k / 2)
        val h = ((hue(i) + step * 8f) % 360f + 360f) % 360f
        // Shades go away from the goal's own lightness, towards whichever side has room.
        val dir = if (base[2] > 0.5f) -1 else 1
        val light = (base[2] + dir * Math.abs(step) * 0.09f * (if (step < 0) 0.6f else 1f)).coerceIn(0.24f, 0.74f)
        val sat = (base[1] + (if (step % 2 == 0) 0.06f else -0.08f)).coerceIn(0.3f, 0.75f)
        return Palette.forNight(Color.hsl(h, sat, light))
    }
}

private val Light = lightColorScheme(
    primary = Palette.Steel,
    onPrimary = Color.White,
    background = Palette.Paper,
    onBackground = Palette.Ink,
    surface = Palette.Panel,
    onSurface = Palette.Ink,
    surfaceVariant = Color(0xFFE8EEF1),
    onSurfaceVariant = Palette.Muted,
    outline = Palette.Rule,
    error = Palette.Brick
)

private val Dark = darkColorScheme(
    primary = Color(0xFF7DB2D6),
    onPrimary = Color(0xFF07121A),
    primaryContainer = Color(0xFF1F3A4D),
    onPrimaryContainer = Color(0xFFCFE5F4),
    secondary = Color(0xFF86C095),
    onSecondary = Color(0xFF0C1A10),
    secondaryContainer = Color(0xFF263A2C),
    onSecondaryContainer = Color(0xFFD3EBD9),
    tertiary = Color(0xFFCDA9E0),
    onTertiary = Color(0xFF1E1426),
    background = Palette.PaperDark,
    onBackground = Palette.InkDark,
    surface = Palette.PanelDark,
    onSurface = Palette.InkDark,
    surfaceVariant = Color(0xFF243138),
    onSurfaceVariant = Color(0xFF9BB0BB),
    surfaceContainerLowest = Color(0xFF0D1316),
    surfaceContainerLow = Color(0xFF161E23),
    surfaceContainer = Color(0xFF1C262C),
    surfaceContainerHigh = Color(0xFF232E35),
    surfaceContainerHighest = Color(0xFF2A363E),
    inverseSurface = Color(0xFFE4ECEF),
    inverseOnSurface = Color(0xFF1A2329),
    outline = Color(0xFF3E515B),
    outlineVariant = Color(0xFF2C3A42),
    error = Color(0xFFEC8C74),
    onError = Color(0xFF2A0E07)
)

private val ChronosTypography = Typography(
    headlineMedium = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Medium),
    titleLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Normal)
)

@Composable
fun ChronosTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
    if (Palette.dark != dark) Palette.dark = dark
    // The status bar's icons: dark on the day paper, light on the night one.
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) androidx.compose.runtime.SideEffect {
        (view.context as? android.app.Activity)?.window?.let { w ->
            androidx.core.view.WindowCompat.getInsetsController(w, view).isAppearanceLightStatusBars = !dark
            androidx.core.view.WindowCompat.getInsetsController(w, view).isAppearanceLightNavigationBars = !dark
            @Suppress("DEPRECATION")
            w.navigationBarColor = android.graphics.Color.parseColor(if (dark) "#FF11181C" else "#FFF1F4F5")
        }
    }
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        typography = ChronosTypography,
        content = content
    )
}
