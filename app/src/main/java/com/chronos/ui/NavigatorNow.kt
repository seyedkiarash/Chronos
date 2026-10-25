package com.chronos.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.chronos.navigator.bridge.Sync
import com.chronos.navigator.model.Node
import java.time.LocalDate

/*
 * Chronos and the Navigator side by side: where they disagree, and each
 * node's numbers as they stand now (the forecast, the log, Chronos's goal).
 */

private fun pc(p: Double?) = if (p == null) "—" else "${Math.round(p * 100)}%"

private fun pTone(p: Double?): Color = when {
    p == null -> Palette.Muted
    p >= 0.7 -> Palette.Moss
    p >= 0.4 -> Palette.Sand
    else -> Palette.Brick
}

// ------------------------------------------------------------------- sync

@Composable
internal fun SyncPanel(nav: NavigatorViewModel) {
    val diffs = nav.syncDiffs
    if (diffs.isEmpty()) return
    Panel("Chronos ⇄ Navigator", Palette.Ochre) {
        Hint("Where a node and the Chronos goal it is worked through disagree. Nothing changes on either side until " +
             "you choose which one is right — or keep both as they are.")
        diffs.forEach { d -> SyncRow(nav, d) }
    }
}

@Composable
private fun SyncRow(nav: NavigatorViewModel, d: Sync.Diff) {
    val lang = LocalLang.current
    fun show(v: String) = if (d.kind == Sync.Kind.DEADLINE) formatDate(lang, LocalDate.parse(v)) else translate(lang, v)
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(tr(when (d.kind) {
            Sync.Kind.DEADLINE -> "Different deadlines"
            Sync.Kind.UNITS -> "Different number of units"
            Sync.Kind.HOURS_PER_UNIT -> "Hours per unit: the log disagrees with Chronos"
            Sync.Kind.FINISHED_IN_CHRONOS -> "Finished in Chronos, open in the graph"
            Sync.Kind.FINISHED_IN_GRAPH -> "Done in the graph, units left in Chronos"
        }), style = MaterialTheme.typography.labelMedium, color = Palette.Ochre)
        Text(d.nodeTitle + "  ⇄  " + d.goalName, style = MaterialTheme.typography.bodySmall)
        Hint(tr(if (d.kind == Sync.Kind.HOURS_PER_UNIT) "Learned from your log" else "Graph") + ": " + show(d.graph) +
             "   ·   Chronos: " + show(d.chronos))
        d.note?.let { Hint(it) }
        if (d.kind == Sync.Kind.HOURS_PER_UNIT) {
            Hint(tr("Chronos plans") + " " + d.chronos + " h " + tr("per") + " " + (d.unit ?: tr("unit")) + "; " +
                 tr("your log says") + " " + d.graph + " h" + trendText(d.trendPerWeek) + ".")
            Hint("With the learned value Chronos plans enough time to meet the deadline.")
        }
        FlowRowSimple {
            if (d.toGraph) OutlinedButton(onClick = { nav.applySync(d, false) }, enabled = !nav.busy,
                modifier = Modifier.padding(end = 6.dp)) { Text("Use Chronos's in the graph") }
            if (d.toChronos) Button(onClick = { nav.applySync(d, true) }, enabled = !nav.busy,
                modifier = Modifier.padding(end = 6.dp)) {
                Text(if (d.kind == Sync.Kind.HOURS_PER_UNIT) "Use it in Chronos" else "Use the graph's in Chronos")
            }
            TextButton(onClick = { nav.keepBoth(d) }, enabled = !nav.busy) { Text("Keep both") }
        }
        HorizontalDivider(Modifier.padding(top = 4.dp))
    }
}

// --------------------------------------------------------- a node's numbers

/** One line under a card's title: the numbers worth a glance. */
@Composable
internal fun NowLine(n: Node, now: NavigatorViewModel.NodeNow?) {
    if (now == null) return
    val parts = mutableListOf<String>()
    if (now.unitsTotal != null) parts += trimNumber(now.unitsDone ?: 0.0) + "/" + trimNumber(now.unitsTotal) + " " + (now.unit ?: "")
    now.hoursPerUnit?.let { parts += trimNumber(Math.round(it * 10) / 10.0) + " h/" + (now.unit ?: tr("unit")) + trend(now.trendPerWeek) }
    now.bias?.let { parts += "×" + trimNumber(Math.round(it * 100) / 100.0) + trend(now.trendPerWeek) }
    now.adherence?.let { parts += pc(it) + " " + tr("kept") }
    now.level?.takeIf { n.levelTo != null }?.let { parts += tr("level") + " " + trimNumber(Math.round(it * 10) / 10.0) + "/" + trimNumber(n.levelTo) }
    now.weekHours?.let { parts += tr("this week") + " " + trimNumber(it) + " h" }
    Row(Modifier.padding(top = 2.dp)) {
        if (now.p != null && n.deadline != null) {
            Text("P " + pc(now.p) + arrow(now.p, now.pWeekAgo) + "  ", style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold, color = pTone(now.p))
        }
        if (parts.isNotEmpty()) Text(parts.joinToString("  ·  "), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun arrow(p: Double, before: Double?): String {
    if (before == null) return ""
    val d = Math.round((p - before) * 100)
    return if (d == 0L) "" else if (d > 0) " ▲$d" else " ▼${-d}"
}

private fun trend(week: Double?): String {
    if (week == null || Math.abs(week) < 0.02) return ""
    return if (week < 0) " ↓" else " ↑"
}

/** The full picture in a card's details: forecast, Chronos, the log. */
@Composable
internal fun NowDetails(n: Node, now: NavigatorViewModel.NodeNow?) {
    if (now == null) return
    Column(Modifier.padding(top = 6.dp)) {
        Text(tr("Now"), style = MaterialTheme.typography.labelMedium, color = Palette.Teal)
        if (n.deadline != null && now.p != null)
            Hint(tr("Chance by the deadline") + " " + pc(now.p) +
                (now.pWeekAgo?.let { "  (" + tr("a week ago") + " " + pc(it) + ")" } ?: ""))
        if (now.likely != null || now.p10 != null)
            Hint(tr("Likely finish") + " " + (now.likely?.let { dateText(it) } ?: "—") + "   ·   10%–90%: " +
                (now.p10?.let { dateText(it) } ?: "—") + " – " + (now.p90?.let { dateText(it) } ?: "—"))
        now.critical?.takeIf { it > 0.05 }?.let { Hint(tr("Critical in") + " " + pc(it) + " " + tr("of the futures")) }
        if (n.deadline != null && now.chronosDeadline != null && now.chronosDeadline != n.deadline)
            Text(tr("Deadline in the graph") + " " + dateText(n.deadline) + "  ·  " + tr("in Chronos") + " " + dateText(now.chronosDeadline),
                style = MaterialTheme.typography.labelSmall, color = Palette.Brick)
        if (now.unitsTotal != null)
            Hint(tr("Done") + ": " + trimNumber(now.unitsDone ?: 0.0) + " / " + trimNumber(now.unitsTotal) + " " + (now.unit ?: "") +
                "  (" + tr("from Chronos") + ")")
        now.hoursPerUnit?.let { h ->
            Hint(tr("Learned speed") + ": " + trimNumber(Math.round(h * 100) / 100.0) + " h " + tr("per") + " " + (now.unit ?: tr("unit")) +
                (now.chronosHoursPerUnit?.let { "  ·  Chronos " + trimNumber(it) } ?: "") + trendText(now.trendPerWeek))
        }
        now.bias?.let { b ->
            Hint(tr("Takes") + " ×" + trimNumber(Math.round(b * 100) / 100.0) + " " + tr("of the time planned") + trendText(now.trendPerWeek))
        }
        now.adherence?.let { Hint(tr("Share of planned time worked") + ": " + pc(it) + "  (" + trimNumber(now.adherenceDays ?: 0.0) + " " + tr("days") + ")") }
        now.hoursLogged?.takeIf { it > 0 }?.let { Hint(tr("Hours logged") + ": " + trimNumber(Math.round(it * 10) / 10.0)) }
        now.weekHours?.let { Hint(tr("Hours this week") + ": " + trimNumber(it)) }
    }
}

@Composable
private fun trendText(week: Double?): String {
    if (week == null || Math.abs(week) < 0.01) return ""
    return "  ·  " + tr(if (week < 0) "getting faster" else "getting slower") + " " + Math.round(Math.abs(week) * 100) + "% " + tr("a week")
}
