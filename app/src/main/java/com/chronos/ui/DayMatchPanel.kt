package com.chronos.ui

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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.chronos.engine.DayMatch
import com.chronos.engine.Slots
import com.chronos.model.Block
import java.time.LocalDate

/*
 * How the lived day matched its plan, drawn right under the boxes it is read
 * from: a ring for the whole, a bar for each of the four parts, the expected
 * and the reported energy hour by hour, and the blocks one by one.
 */

private fun matchColor(v: Double?): Color = when {
    v == null -> Palette.Muted
    v >= 0.8 -> Palette.Moss
    v >= 0.5 -> Palette.Sand
    else -> Palette.Brick
}

private fun pct(v: Double?) = if (v == null) "—" else "${Math.round(v * 100)}%"

@Composable
internal fun DayMatchPanel(vm: ChronosViewModel, day: LocalDate, blocks: List<Block>?) {
    if (blocks == null) return
    val result = vm.dayResult(day)
    val m = remember(day, blocks, vm.logs, vm.revision) {
        DayMatch.of(vm.config, day, blocks, result?.score()?.capacity(), result?.score()?.fatigue(), result?.score()?.physical(), vm.logs)
    }
    var showBlocks by remember(day) { mutableStateOf(false) }

    Panel("How the day matched the plan", Palette.Steel) {
        if (m.empty()) {
            Hint("Fill in the boxes above — how long each block took, when it started, how drained you were — " +
                 "and rate a few hours of energy. The match is drawn here as you go.")
            if (m.unreported() > 0) Hint("${m.unreported()} blocks not reported yet")
            return@Panel
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Ring(m.overall()!!, Modifier.size(96.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                PartBar("Duration", m.duration())
                PartBar("Start time", m.start())
                PartBar("Energy", m.energy())
                PartBar("Fatigue", m.fatigue())
            }
        }
        Spacer(Modifier.height(6.dp))
        Hint("${m.reported()} blocks reported · ${m.skipped()} skipped · ${m.unreported()} not reported")
        Hint("Duration: minutes done against planned. Start: an hour off counts as nothing. Energy and " +
             "fatigue: what the plan expected of you against what you reported.")

        if (m.hours().isNotEmpty()) {
            Text(tr("Energy: expected and reported"), style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 10.dp))
            Ltr { EnergyMatchChart(m.hours()) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp, 3.dp).padding(0.dp)) {
                    Canvas(Modifier.fillMaxSize()) { drawRect(Palette.Steel) }
                }
                Text("  " + tr("expected") + "     ", style = MaterialTheme.typography.labelSmall)
                Canvas(Modifier.size(8.dp)) { drawCircle(Palette.Ochre) }
                Text("  " + tr("you said"), style = MaterialTheme.typography.labelSmall)
            }
        }

        TextButton(onClick = { showBlocks = !showBlocks }) {
            Text(if (showBlocks) "Hide the blocks" else "Block by block")
        }
        if (showBlocks) m.rows().forEach { r -> MatchRow(r) }
    }
}

/** The overall match as a ring, its colour from red through sand to green. */
@Composable
private fun Ring(value: Double, modifier: Modifier) {
    val color = matchColor(value)
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 10.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(track, -90f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            drawArc(color, -90f, (360 * value).toFloat(), false, Offset(inset, inset), arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(pct(value), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color)
            Text(tr("match"), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PartBar(label: String, value: Double?) {
    Column(Modifier.padding(vertical = 3.dp)) {
        Row {
            Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            Text(pct(value), style = MaterialTheme.typography.labelSmall, color = matchColor(value))
        }
        if (value == null) Text(tr("not reported"), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        else Meter(value.toFloat(), matchColor(value))
    }
}

/** Expected capacity as a line over the rated hours, the ratings as dots. Left to right whatever the language. */
@Composable
private fun EnergyMatchChart(hours: List<DayMatch.Hour>) {
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    Column {
        Canvas(Modifier.fillMaxWidth().height(110.dp).padding(vertical = 6.dp)) {
            val first = hours.first().hour
            val last = hours.last().hour.coerceAtLeast(first + 1)
            fun x(h: Int) = (h - first + 0.5f) / (last - first + 1f) * size.width
            fun y(v: Double) = ((1 - v) * size.height).toFloat()
            for (g in listOf(0.2, 0.6, 1.0)) drawLine(grid, Offset(0f, y(g)), Offset(size.width, y(g)), 1f)
            for (i in 1 until hours.size)
                drawLine(Palette.Steel, Offset(x(hours[i - 1].hour), y(hours[i - 1].expected())),
                    Offset(x(hours[i].hour), y(hours[i].expected())), 3.dp.toPx(), StrokeCap.Round)
            hours.forEach { h ->
                drawLine(Palette.Ochre.copy(alpha = 0.35f), Offset(x(h.hour), y(h.expected())),
                    Offset(x(h.hour), y(h.reported())), 2.dp.toPx())
                drawCircle(Palette.Ochre, 5.dp.toPx(), Offset(x(h.hour), y(h.reported())))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text("%02d:00".format(hours.first().hour), style = MaterialTheme.typography.labelSmall, color = label)
            Spacer(Modifier.weight(1f))
            Text("%02d:00".format(hours.last().hour), style = MaterialTheme.typography.labelSmall, color = label)
        }
    }
}

@Composable
private fun MatchRow(r: DayMatch.Row) {
    val planned = Slots.format(r.plannedStart()) + " · " + r.plannedMin() + " min"
    val actual = when {
        r.skipped() -> tr("skipped")
        r.actualAt() == null -> tr("not reported")
        else -> r.actualAt() + " · " + (r.actualMin() ?: r.plannedMin()) + " min"
    }
    val score = if (r.duration() == null) null
        else listOfNotNull(r.duration(), r.start()).average()
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(10.dp)) { drawCircle(matchColor(score)) }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(r.name() ?: "", style = MaterialTheme.typography.bodySmall)
            Ltr {
                Text(planned + "  →  " + actual, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(pct(score), style = MaterialTheme.typography.labelSmall, color = matchColor(score))
    }
}
