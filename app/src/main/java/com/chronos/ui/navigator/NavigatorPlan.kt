package com.chronos.ui.navigator

import com.chronos.ui.*
import com.chronos.ui.common.*
import com.chronos.ui.setup.*
import com.chronos.ui.horizon.*
import com.chronos.ui.day.*
import com.chronos.ui.log.*

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.chronos.navigator.model.Graph
import com.chronos.navigator.store.NavStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/*
 * What the Navigator concludes, on its tab: the coming week's hours (accept
 * them and Chronos plans your days by them), the forecast for every deadline,
 * and what the log has taught so far.
 */

private fun pColor(p: Double?): Color = when {
    p == null -> Palette.Muted
    p >= 0.7 -> Palette.Moss
    p >= 0.4 -> Palette.Sand
    else -> Palette.Brick
}

private fun pct(p: Double?) = if (p == null) "—" else "${Math.round(p * 100)}%"

// ---------------------------------------------------------------- the week

@Composable
internal fun WeekPanel(nav: NavigatorViewModel, g: Graph) {
    val meta = nav.week.firstOrNull { it.nodeId == NavStore.WEEK_META }
    val rows = nav.week.filter { it.nodeId != NavStore.WEEK_META }.sortedByDescending { it.plannedH }
    var editing by remember(meta?.savedAt) { mutableStateOf(false) }
    val edits = remember(meta?.savedAt) { mutableStateMapOf<String, Double?>() }

    Panel("This week's hours", Palette.Indigo) {
        if (meta == null) {
            Hint("No week planned yet. The Navigator plans each coming week on Thursday night; you can plan one now.")
            Button(onClick = { nav.planWeek() }, enabled = !nav.busy) { Text("Plan the coming week") }
            if (nav.computing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
            return@Panel
        }
        val start = LocalDate.parse(meta.week)
        val parts = (meta.reason ?: "0|0|0").split("|")
        val free = parts.getOrNull(0)?.toDoubleOrNull() ?: 0.0
        val over = parts.getOrNull(1)?.toDoubleOrNull() ?: 0.0
        val accepted = meta.status != "PROPOSED"
        Text(dateText(start) + " – " + dateText(start.plusDays(6)), style = MaterialTheme.typography.titleSmall)
        Hint(tr("Expected goal hours") + " " + trimNumber(Math.round(meta.plannedH * 2) / 2.0) + " h  ·  " +
             tr("left free") + " " + trimNumber(Math.round(free * 2) / 2.0) + " h")
        if (over > 0.25) Text(tr("The sprint's own plan is more than the week is expected to hold, by") + " " +
            trimNumber(Math.round(over * 2) / 2.0) + " h", style = MaterialTheme.typography.labelSmall, color = Palette.Brick)
        Text(if (accepted) tr("Accepted — Chronos's weekly floors follow it.") else tr("Waiting for you to accept it."),
            style = MaterialTheme.typography.labelMedium, color = if (accepted) Palette.Moss else Palette.Sand,
            modifier = Modifier.padding(vertical = 4.dp))

        rows.forEach { r ->
            val n = g.node(r.nodeId)
            Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(n?.title ?: r.nodeId, style = MaterialTheme.typography.bodySmall, color = typeColor(n?.type))
                        r.reason?.let { Hint(it) }
                    }
                    if (editing) {
                        // A field you emptied stays empty while you type the new number: an edited
                        // row is read from the edits even when its value is null.
                        Box(Modifier.width(96.dp)) {
                            NumberField("h", if (r.nodeId in edits) edits[r.nodeId] else r.plannedH) { v ->
                                edits[r.nodeId] = v?.takeIf { it >= 0 }
                            }
                        }
                    } else Text(trimNumber(r.plannedH) + " h", style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold)
                }
                if (n != null && n.takesHours() && nav.nodeActivities[r.nodeId].isNullOrEmpty()) {
                    Text(tr("No Chronos activity does this node's work, so its hours cannot reach your days. Link one:"),
                        style = MaterialTheme.typography.labelSmall, color = Palette.Brick)
                    var pick by remember(r.nodeId) { mutableStateOf<String?>(null) }
                    Row(verticalAlignment = Alignment.Bottom) {
                        Choice("Chronos activity", pick, nav.activities.map { it to it }, modifier = Modifier.weight(1f)) { pick = it }
                        TextButton(onClick = { pick?.let { nav.linkNode(r.nodeId, it) } }, enabled = pick != null) { Text("Link") }
                    }
                    Hint("Or add goal work for it in Setup (a goal and an activity), then link it here.")
                }
            }
            HorizontalDivider()
        }

        val blank = edits.any { it.value == null }
        if (blank) Text(tr("Fill in every hour you emptied (0 gives the node nothing this week)."),
            style = MaterialTheme.typography.labelSmall, color = Palette.Brick, modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                nav.acceptWeek(meta.week, edits.filterValues { it != null }.mapValues { it.value!! })
                editing = false
            }, enabled = !nav.busy && !blank) { Text(if (accepted) "Apply again" else "Accept") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { editing = !editing }) { Text(if (editing) "Done editing" else "Change hours") }
        }
        TextButton(onClick = { nav.planWeek() }, enabled = !nav.busy) { Text("Plan the week again") }
        if (nav.computing) LinearProgressIndicator(Modifier.fillMaxWidth())
        Hint("Accepting sets the weekly floor of each ongoing Chronos goal to its nodes' hours (a goal whose nodes get " +
             "nothing this week waits), rebuilds the plan and sends it to your calendar. Goals with a deadline keep " +
             "their own pace.")

        if (nav.changes.isNotEmpty()) {
            Text(tr("What changed in Chronos"), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
            nav.changes.forEach { c ->
                Hint((c.goalName() ?: "") + ":  " + tr("weekly floor") + " " + h(c.floorBefore()) + " → " + h(c.floorAfter()) +
                     "  ·  " + tr("daily ceiling") + " " + h(c.ceilingBefore()) + " → " + h(c.ceilingAfter()))
            }
        }
        if (nav.following) TextButton(onClick = { nav.stopFollowing() }, enabled = !nav.busy) {
            Text("Stop following the Navigator", color = Palette.Brick)
        }
    }
}

private fun h(v: Double?) = if (v == null) "—" else trimNumber(v) + " h"

// ------------------------------------------------------------- the forecast

@Composable
internal fun ForecastPanel(nav: NavigatorViewModel, g: Graph) {
    val f = nav.forecast
    var all by remember { mutableStateOf(false) }
    Panel("Forecast", Palette.Teal) {
        if (f == null) {
            Hint("Not run yet. It runs every night by itself, or now:")
            Button(onClick = { nav.forecastNow() }, enabled = !nav.busy) { Text("Forecast now") }
            if (nav.computing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
            return@Panel
        }
        val at = Instant.ofEpochMilli(f.run().runAt).atZone(ZoneId.systemDefault()).toLocalDate()
        Hint(tr("Run") + " " + dateText(at) + "  ·  " + f.run().runs + " " + tr("simulated futures") + "  ·  " +
             trimNumber(f.run().millis / 100 / 10.0) + " s")
        Hint("For each deadline: in how many of the simulated futures it is met, the likely finishing date, and the " +
             "change since a week ago.")
        val today = LocalDate.now()
        val dated = g.nodes.values.filter { n ->
            n.deadline != null && !n.deadline.isBefore(today) && f.nodes()[n.id]?.pByDeadline != null && n.plannable()
        }.sortedBy { it.deadline }
        (if (all) dated else dated.take(8)).forEach { n -> ForecastRow(nav, n, f) }
        val below = g.params["trigger.prob_drop"]?.get("below") ?: 0.6
        val atRisk = dated.filter { (f.nodes()[it.id]?.pByDeadline ?: 1.0) < below }.take(4)
        if (atRisk.isNotEmpty()) {
            OutlinedButton(onClick = { nav.askReplan(atRisk.map { it.id }) }, enabled = !nav.busy,
                modifier = Modifier.padding(top = 6.dp)) { Text("Ask the LLM for ways to recover") }
            Hint(tr("For") + " " + atRisk.joinToString(", ") { it.id } + ". " +
                tr("The same question goes out by itself at night when a deadline falls below 60%."))
        }
        if (dated.size > 8) TextButton(onClick = { all = !all }) {
            Text(if (all) "Show fewer" else tr("Show all") + " (${dated.size})")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { nav.forecastNow() }, enabled = !nav.busy) { Text("Forecast again") }
            if (nav.computing) CircularProgressIndicator(Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ForecastRow(nav: NavigatorViewModel, n: com.chronos.navigator.model.Node, f: NavStore.StoredForecast) {
    val row = f.nodes()[n.id] ?: return
    val before = nav.weekOld?.nodes()?.get(n.id)?.pByDeadline
    var open by remember(n.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(n.title ?: n.id, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                Hint(tr("due") + " " + dateText(n.deadline!!) +
                     (row.p50?.let { "  ·  " + tr("likely") + " " + dateText(LocalDate.parse(it)) } ?: "  ·  " + tr("not finished in most futures")))
            }
            if (before != null && row.pByDeadline != null) {
                val d = Math.round((row.pByDeadline - before) * 100)
                if (d != 0L) Text((if (d > 0) "▲" else "▼") + " " + Math.abs(d), style = MaterialTheme.typography.labelSmall,
                    color = if (d > 0) Palette.Moss else Palette.Brick, modifier = Modifier.padding(end = 8.dp))
            }
            Text(pct(row.pByDeadline), style = MaterialTheme.typography.titleMedium, color = pColor(row.pByDeadline),
                fontWeight = FontWeight.Bold)
        }
        Meter((row.pByDeadline ?: 0.0).toFloat(), pColor(row.pByDeadline))
        if (open) {
            Hint(tr("Finishing date") + ": 10% " + (row.p10?.let { dateText(LocalDate.parse(it)) } ?: "—") + "  ·  50% " +
                 (row.p50?.let { dateText(LocalDate.parse(it)) } ?: "—") + "  ·  90% " + (row.p90?.let { dateText(LocalDate.parse(it)) } ?: "—"))
            Hint(tr("Critical in") + " " + pct(row.critical) + " " + tr("of the futures: being late here cost a deadline further on."))
        }
    }
}

// ----------------------------------------------------------- what was learned

@Composable
internal fun LearnedPanel(nav: NavigatorViewModel) {
    val post = nav.params.filter { it.key.startsWith("post.") }.associateBy { it.key }
    if (post.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Panel("What your log has taught", Palette.Moss) {
        post["post.adherence"]?.let { a ->
            Text(tr("You work") + " " + pct(a.get("mean")) + " " + tr("of the goal time you plan") +
                 "  (" + trimNumber(a.get("days") ?: 0.0) + " " + tr("planned days, recent ones counting most") + ")",
                style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { open = !open }) { Text(if (open) "Hide" else "Details") }
        if (!open) return@Panel
        post.values.filter { it.key.startsWith("post.bias.") }.forEach { b ->
            val week = b.get("slope")?.let { Math.exp(it * 7) - 1 }
            Hint(b.key.removePrefix("post.bias.") + ": " + tr("takes") + " ×" + trimNumber(Math.round((b.get("median") ?: 1.0) * 100) / 100.0) +
                 " " + tr("of what is planned") + "  (" + trimNumber(b.get("n") ?: 0.0) + " " + tr("days") + ")" +
                 (week?.takeIf { Math.abs(it) >= 0.01 }?.let { "  ·  " + tr(if (it < 0) "getting faster" else "getting slower") + " " +
                     Math.round(Math.abs(it) * 100) + "% " + tr("a week") } ?: ""))
        }
        post.values.filter { it.key.startsWith("post.adherence.") }.forEach { a ->
            Hint(a.key.removePrefix("post.adherence.") + ": " + pct(a.get("mean")) + " " + tr("of its planned time worked") +
                 "  (" + trimNumber(a.get("days") ?: 0.0) + " " + tr("days") + ")")
        }
        listOf("IDEAL" to "Ideal day", "BUSY" to "Busy day", "STRESS" to "Stressful day").forEach { (k, label) ->
            post["post.capacity.$k"]?.let { c ->
                Hint(tr(label) + ": " + trimNumber(Math.round((c.get("mean") ?: 0.0) * 10) / 10.0) + " " + tr("goal hours") +
                     "  (" + trimNumber(c.get("n") ?: 0.0) + " " + tr("days ticked") + ")")
            }
        }
        post["post.energy"]?.let { e ->
            val best = e.values.filterKeys { it.startsWith("h") }.maxByOrNull { it.value }
            if (best != null) Hint(tr("Sharpest hour so far") + ": " + "%02d:00".format(best.key.removePrefix("h").toInt()))
        }
        HistorySection(nav)
        Hint("Your priors stay as you gave them; these are recomputed from them and the whole log every night. " +
             "Speed is followed by a Kalman filter that also tracks its trend, so recent days count most.")
    }
}
