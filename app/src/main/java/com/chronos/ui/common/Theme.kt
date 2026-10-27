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
    val Paper = Color(0xFFF1F4F5)
    val Panel = Color(0xFFFFFFFF)
    val Ink = Color(0xFF14202B)
    val Muted = Color(0xFF5C7180)
    val Rule = Color(0xFFD3DCE0)
    val Steel = Color(0xFF2F5D7C)
    val Moss = Color(0xFF5E8C6A)
    val Brick = Color(0xFFA8452F)
    val Sand = Color(0xFFB08D2A)

    /** Section accents for the Setup wizard: one hue per step, all from the goal palette. */
    val Indigo = Color(0xFF4A4E8C)
    val Plum = Color(0xFF8A6A9E)
    val Ochre = Color(0xFFB07A3C)
    val Teal = Color(0xFF3F7F87)

    val PaperDark = Color(0xFF12191D)
    val PanelDark = Color(0xFF1A242A)
    val InkDark = Color(0xFFE6EDF0)

    /** Goal colours, assigned by position so a goal keeps its colour everywhere. */
    val goals = listOf(
        Color(0xFF4A4E8C), Color(0xFF2F5D7C), Color(0xFF5E8C6A), Color(0xFF8A6A9E),
        Color(0xFFB07A3C), Color(0xFF3F7F87), Color(0xFF9E5A6B)
    )

    fun goalColor(index: Int): Color =
        if (index < 0) Color(0xFF93A7B2) else goals[index % goals.size]
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
    private val NEUTRAL = Color(0xFF93A7B2)

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
        return if (i < 0) NEUTRAL else tone(i).let { Color.hsl(it[0], it[1], it[2]) }
    }

    /** One activity's colour: its goal's hue, shaded by its place under that goal. */
    fun target(goals: List<com.chronos.model.Goal>, targetId: String?): Color {
        val t = goals.firstOrNull { it.id == targetId } ?: return NEUTRAL
        val name = groupOf(t)
        val i = groups(goals).indexOf(name)
        if (i < 0) return NEUTRAL
        val siblings = goals.filter { groupOf(it) == name }
        val base = tone(i)
        if (siblings.size <= 1) return Color.hsl(base[0], base[1], base[2])
        val k = siblings.indexOfFirst { it.id == targetId }
        // 0, +1, −1, +2, −2 … steps away from the goal's own colour.
        val step = if (k == 0) 0 else if (k % 2 == 1) (k + 1) / 2 else -(k / 2)
        val h = ((hue(i) + step * 8f) % 360f + 360f) % 360f
        // Shades go away from the goal's own lightness, towards whichever side has room.
        val dir = if (base[2] > 0.5f) -1 else 1
        val light = (base[2] + dir * Math.abs(step) * 0.09f * (if (step < 0) 0.6f else 1f)).coerceIn(0.24f, 0.74f)
        val sat = (base[1] + (if (step % 2 == 0) 0.06f else -0.08f)).coerceIn(0.3f, 0.75f)
        return Color.hsl(h, sat, light)
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
    primary = Color(0xFF7FB3D5),
    onPrimary = Color(0xFF07121A),
    background = Palette.PaperDark,
    onBackground = Palette.InkDark,
    surface = Palette.PanelDark,
    onSurface = Palette.InkDark,
    surfaceVariant = Color(0xFF243138),
    onSurfaceVariant = Color(0xFF9BB0BB),
    outline = Color(0xFF35464F),
    error = Color(0xFFE08A76)
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
fun ChronosTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        typography = ChronosTypography,
        content = content
    )
}
