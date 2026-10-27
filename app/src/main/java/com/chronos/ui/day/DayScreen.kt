package com.chronos.ui.day

import com.chronos.ui.*
import com.chronos.ui.common.*
import com.chronos.ui.setup.*
import com.chronos.ui.horizon.*
import com.chronos.ui.log.*
import com.chronos.ui.navigator.*

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chronos.engine.Jalali
import com.chronos.engine.Slots
import com.chronos.model.Block
import java.time.LocalDate

@Composable
fun DayScreen(vm: ChronosViewModel) {
    val rev = vm.revision
    var offset by remember { mutableIntStateOf(0) }
    var picked by remember { mutableStateOf<Block?>(null) }
    val date: LocalDate = vm.today.plusDays(offset.toLong())

    LaunchedEffect(date, vm.bundle, rev) { vm.requestDay(date) }
    val result = vm.dayResult(date)

    ScreenHeader(
        dateText(date),
        "The hours for this day come from the horizon plan. What gets decided here is only the " +
        "arrangement: where each block sits so it buys the most value per unit of fatigue."
    )

    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { offset = (offset - 1).coerceAtLeast(0); picked = null },
                enabled = offset > 0) { Text("Previous") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { offset += 1; picked = null }) { Text("Next") }
            Spacer(Modifier.width(12.dp))
            Text("Day ${offset + 1}", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (date == vm.logDay && vm.restToday)
        Verdict(Tone.Good, "Rest day", "Goal work is paused; only upkeep and rest activities are planned.")

    if (result == null) {
        Panel { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            CircularProgressIndicator(Modifier.size(28.dp))
        } }
        return
    }

    Panel {
        // A time axis runs left to right in both languages; mirroring it would
        // put midnight on the right and make the whole plot unreadable.
        Ltr { DayPlot(
            capacity = result.score().capacity(),
            fatigue = result.score().fatigue(),
            physical = result.score().physical(),
            dopamine = result.score().dopamine(),
            blocks = result.blocks(),
            gaps = result.gaps(),
            goals = vm.config.goals,
            safeLine = vm.config.params.fSafe ?: 0.62,
            selected = picked,
            onPick = { picked = it }
        ) }
        Spacer(Modifier.height(6.dp))
        FlowRowSimple {
            LegendDot("Capacity", Palette.Steel)
            LegendDot("Mental fatigue", Palette.Brick)
            LegendDot("Physical fatigue", Palette.Ochre)
            if (result.blocks().any { it.leisure }) {
                LegendDot("Leisure", Palette.Plum)
                LegendDot("Stimulation", Palette.Plum.copy(alpha = 0.6f))
            }
            LegendDot("Fixed", MaterialTheme.colorScheme.outline)
            LegendDot("Open", MaterialTheme.colorScheme.surfaceVariant)
        }
    }

    val effective = result.effectiveMinutes()
    val elapsed = result.elapsedMinutes()
    Row(Modifier.fillMaxWidth()) {
        Stat("Effective work", "%.1fh".format(effective / 60),
            "${if (elapsed > 0) (100 * effective / elapsed).toInt() else 0}% of " +
            "${"%.1f".format(elapsed / 60)}h at the desk", Modifier.weight(1f))
        Stat("Above the safe line", "%.2f".format(result.score().excess()),
            "unit-hours of overload", Modifier.weight(1f))
    }

    result.warnings().forEach { Verdict(Tone.Warn, "Worth knowing about this day", it) }

    if (result.unplaced().isNotEmpty()) {
        Panel("${result.unplaced().size} item(s) did not fit", Palette.Brick) {
            result.unplaced().forEach { MissingRow(it.name ?: "", it.reason ?: "") }
        }
    }

    picked?.let { block ->
        Panel(if (block.gap) "About this gap" else "Why this block is here") {
            Detail("Slot", "${Slots.format(block.start)}–${Slots.format(block.start + block.dur)}" +
                " (${block.dur * Slots.SLOT_MIN} min)")
            if (block.gap) Detail("Reason", block.why ?: "")
            if (!block.gap && block.goalId != null) {
                Detail("Mean capacity", "%.2f".format(block.meanEta))
                Detail("Effective output",
                    "%.0f of %d minutes".format(block.yieldMin, block.dur * Slots.SLOT_MIN))
            }
            if (!block.gap && block.goalId == null) {
                Detail("Effect on capacity",
                    "load %.2f, recovery %.2f per hour".format(block.load, block.recovery))
            }
            if (block.leisure) {
                block.why?.let { Detail("Why", it) }
                Detail("Stimulation", "%.2f per hour".format(block.dopamine))
            }
            if (block.fixed) Detail("Type", "Fixed commitment, not negotiable")
            if (block.forced) Detail("Placement", "Forced in: travel is not optional")
        }
    }

    Panel("Timetable") {
        (result.blocks() + result.gaps()).sortedBy { it.start }.forEach { b ->
            Row(
                Modifier.fillMaxWidth().clickable { picked = b }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${Slots.format(b.start)}–${Slots.format(b.start + b.dur)}",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.width(96.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text((b.name ?: "") + if (b.leisure) " · " + tr("leisure") else "", style = MaterialTheme.typography.bodyMedium,
                    color = if (b.leisure) Palette.Plum else androidx.compose.ui.graphics.Color.Unspecified,
                    modifier = Modifier.weight(1f))
                Text(if (b.goalId != null) "%.2f".format(b.meanEta) else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(140.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun LegendDot(label: String, color: androidx.compose.ui.graphics.Color) {
    Row(Modifier.padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color,
            androidx.compose.foundation.shape.RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
