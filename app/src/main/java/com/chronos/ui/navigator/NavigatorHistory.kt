package com.chronos.ui.navigator

import com.chronos.ui.*
import com.chronos.ui.common.*
import com.chronos.ui.setup.*
import com.chronos.ui.horizon.*
import com.chronos.ui.day.*
import com.chronos.ui.log.*

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.chronos.navigator.estimate.History
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * How what the log taught got where it is: for each learned value a timeline
 * of every day since the first log, with its 10–90% band; open one for the
 * waterfall from your prior to today and, for each day, what that day's log
 * was and how far it moved the value.
 */

/** Whether a rise is good news: lower hours per unit is, higher adherence and capacity are. */
private fun upIsGood(s: History.Series) = s.kind() != "speed"

private fun title(s: History.Series): String = when (s.kind()) {
    "speed" -> s.subject()
    "adherence" -> if (s.subject().isEmpty()) "all goal work" else s.subject()
    else -> s.subject()
}

@Composable
private fun kindLabel(s: History.Series) = tr(when (s.kind()) {
    "speed" -> if (s.unit() == "×") "Time against plan" else "Hours per unit"
    "adherence" -> "Share of planned time worked"
    else -> "Goal hours on a day of this type"
})

private fun fmt(v: Double, unit: String) = when (unit) {
    "%" -> "${Math.round(v)}%"
    "×" -> "×" + trimNumber(Math.round(v * 100) / 100.0)
    else -> trimNumber(Math.round(v * 100) / 100.0) + " h"
}

@Composable
internal fun HistorySection(nav: NavigatorViewModel) {
    val all = nav.history
    LaunchedEffect(Unit) { if (all == null) nav.loadHistory() }
    Text(tr("How these came about"), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
    if (all == null) { LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 6.dp)); return }
    if (all.isEmpty()) { Hint("Nothing has moved yet: log a few sessions."); return }
    var open by remember { mutableStateOf<String?>(null) }
    all.sortedBy { listOf("speed", "adherence", "capacity").indexOf(it.kind()) }.forEach { s ->
        HistoryRow(s, open == s.key()) { open = if (open == s.key()) null else s.key() }
    }
}

@Composable
private fun HistoryRow(s: History.Series, expanded: Boolean, toggle: () -> Unit) {
    val delta = s.now() - s.prior()
    val good = if (upIsGood(s)) delta > 0 else delta < 0
    val tone = if (abs(delta) < 1e-6) Palette.Muted else if (good) Palette.Moss else Palette.Brick
    Column(Modifier.fillMaxWidth().clickable(onClick = toggle).padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title(s), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                Hint(kindLabel(s) + "  ·  " + tr("from") + " " + fmt(s.prior(), s.unit()) + " " + tr("to") + " " + fmt(s.now(), s.unit()))
            }
            Text(fmt(s.now(), s.unit()), style = MaterialTheme.typography.titleSmall, color = tone, fontWeight = FontWeight.Bold)
        }
        Ltr { Timeline(s, if (expanded) 150.dp else 44.dp, tone, showDots = expanded) }
        if (expanded) {
            Text(tr("What moved it, day by day"), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
            Ltr { Waterfall(s) }
            var all by remember { mutableStateOf(false) }
            val steps = s.steps().filter { abs(it.delta()) >= 1e-4 }.reversed()
            (if (all) steps else steps.take(8)).forEach { st ->
                val up = st.delta() > 0
                val ok = if (upIsGood(s)) up else !up
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(dateText(st.day()), style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(120.dp))
                    Column(Modifier.weight(1f)) {
                        Text((if (up) "▲ " else "▼ ") + fmt(abs(st.delta()), s.unit()).removePrefix("×"),
                            style = MaterialTheme.typography.labelSmall, color = if (ok) Palette.Moss else Palette.Brick)
                        Hint(st.why())
                    }
                }
            }
            if (steps.size > 8) TextButton(onClick = { all = !all }) { Text(if (all) "Show fewer" else tr("Show all") + " (${steps.size})") }
            Hint(when (s.kind()) {
                "speed" -> "Followed by a Kalman filter: each day's sessions pull the estimate toward what they showed, recent days most, and a steady trend carries it on between sessions."
                "adherence" -> "Each planned day counts as the share of its planned time you worked; a day's weight halves every week."
                else -> "Goal hours on the days you ticked as this type, against the figure you stated."
            })
        }
        HorizontalDivider(Modifier.padding(top = 4.dp))
    }
}

/** The value day by day with its 10–90% band; the prior as a dotted line. */
@Composable
private fun Timeline(s: History.Series, height: androidx.compose.ui.unit.Dp, tone: Color, showDots: Boolean) {
    val pts = s.points()
    if (pts.size < 2) return
    val band = Palette.Steel.copy(alpha = 0.15f)
    val faint = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(height).padding(vertical = 4.dp)) {
        val lo = min(pts.minOf { it.low() }, s.prior()); val hi = max(pts.maxOf { it.high() }, s.prior())
        val span = if (hi - lo < 1e-9) 1.0 else hi - lo
        fun x(i: Int) = i / (pts.size - 1f) * size.width
        fun y(v: Double) = (size.height - ((v - lo) / span * size.height)).toFloat()
        val area = Path().apply {
            moveTo(x(0), y(pts[0].high()))
            pts.indices.forEach { lineTo(x(it), y(pts[it].high())) }
            pts.indices.reversed().forEach { lineTo(x(it), y(pts[it].low())) }
            close()
        }
        drawPath(area, band)
        drawLine(faint, Offset(0f, y(s.prior())), Offset(size.width, y(s.prior())), strokeWidth = 1.dp.toPx(),
            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4f, 6f)))
        val line = Path().apply { moveTo(x(0), y(pts[0].value())); pts.indices.forEach { lineTo(x(it), y(pts[it].value())) } }
        drawPath(line, tone, style = Stroke(2.dp.toPx()))
        if (showDots) pts.indices.forEach { drawCircle(tone, 2.5.dp.toPx(), Offset(x(it), y(pts[it].value()))) }
    }
}

/** From the prior (grey) through each day's push, up or down, to today (blue). */
@Composable
private fun Waterfall(s: History.Series) {
    val steps = s.steps().filter { abs(it.delta()) >= 1e-4 }.takeLast(14)
    if (steps.isEmpty()) return
    val start = s.now() - steps.sumOf { it.delta() }
    Canvas(Modifier.fillMaxWidth().height(120.dp).padding(vertical = 4.dp)) {
        var level = start
        val levels = mutableListOf(start)
        steps.forEach { level += it.delta(); levels += level }
        val lo = min(levels.minOrNull() ?: 0.0, 0.0.takeIf { s.unit() == "%" } ?: levels.minOrNull() ?: 0.0)
        val hi = levels.maxOrNull() ?: 1.0
        val span = if (hi - lo < 1e-9) 1.0 else (hi - lo) * 1.1
        val n = steps.size + 2
        val w = size.width / n
        fun y(v: Double) = (size.height - ((v - lo) / span * size.height)).toFloat()
        drawRect(Palette.Muted.copy(alpha = 0.6f), Offset(w * 0.15f, y(start)), Size(w * 0.7f, size.height - y(start)))
        var cur = start
        steps.forEachIndexed { i, st ->
            val next = cur + st.delta()
            val good = if (upIsGood(s)) st.delta() > 0 else st.delta() < 0
            val top = y(max(cur, next)); val bottom = y(min(cur, next))
            drawRect((if (good) Palette.Moss else Palette.Brick).copy(alpha = 0.8f),
                Offset(w * (i + 1) + w * 0.15f, top), Size(w * 0.7f, max(1.5f, bottom - top)))
            cur = next
        }
        drawRect(Palette.Steel.copy(alpha = 0.8f), Offset(w * (n - 1) + w * 0.15f, y(cur)), Size(w * 0.7f, size.height - y(cur)))
    }
    Row(Modifier.fillMaxWidth()) {
        val truncated = s.steps().count { abs(it.delta()) >= 1e-4 } > steps.size
        Hint(tr(if (truncated) "14 changes ago" else "your prior") + " " + fmt(start, s.unit()))
        Spacer(Modifier.weight(1f))
        Hint(tr("now") + " " + fmt(s.now(), s.unit()))
    }
}
