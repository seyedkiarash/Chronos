package com.chronos.ui.navigator

import com.chronos.ui.*
import com.chronos.ui.common.*
import com.chronos.ui.setup.*
import com.chronos.ui.horizon.*
import com.chronos.ui.day.*
import com.chronos.ui.log.*

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.chronos.navigator.llm.Op
import com.chronos.navigator.store.NavProposals
import java.time.Instant
import java.time.ZoneId

/*
 * One proposal on a page of its own: what it is for, what it would change,
 * what that does to your deadlines (measured by the app), on what basis the
 * model proposed it — and a conversation with the model about it, where a
 * revision appears as the next version beside the one it replaces.
 */

private fun statusColor(s: String) = when (s) {
    "PENDING" -> Palette.Plum; "APPROVED" -> Palette.Moss; "REVISED", "OUTDATED" -> Palette.Muted; else -> Palette.Brick
}

/** The proposals inbox: a short row each; a tap opens the page. */
@Composable
internal fun InboxPanel(nav: NavigatorViewModel) {
    // Two spaces (your request of 29 Oct 2026): answers to what you asked, and what the app or the model
    // raised by itself — a weekly review, a reading of your log, a trigger — each with its own list.
    var mine by remember { mutableStateOf(true) }
    val all = nav.proposals.filter { it.askedByYou() == mine }
    val pending = all.filter { it.status() == "PENDING" }
    val past = all.filter { it.status() != "PENDING" }
    var showPast by remember { mutableStateOf(false) }
    Panel("Proposals", Palette.Plum) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
            Pill(tr("Your questions") + " (" + nav.proposals.count { it.askedByYou() && it.status() == "PENDING" } + ")", mine) { mine = true }
            Spacer(Modifier.width(6.dp))
            Pill(tr("Raised by the app") + " (" + nav.proposals.count { !it.askedByYou() && it.status() == "PENDING" } + ")", !mine) { mine = false }
        }
        Hint(if (mine) "Answers to what you asked the Navigator — a message, a question about the graph, a conversation on a proposal. " +
                 "Nothing has changed yet: open one to see what it would change and why, and talk it over before you decide."
             else "What the app and the model raised by themselves: the weekly review, what your log suggests, a goal whose " +
                 "odds moved. Nothing has changed yet; each waits for you.")
        if (pending.isEmpty()) Hint("Nothing is waiting.")
        pending.forEach { InboxRow(nav, it) }
        if (!mine && nav.appNotes.isNotEmpty()) {
            Text(tr("Notes from the app"), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
            nav.appNotes.takeLast(6).reversed().forEach { t ->
                val day = java.time.Instant.ofEpochMilli(t.at).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                Hint(dateText(day) + "  ·  " + com.chronos.navigator.llm.client.CrewRun.readable(t.text).lineSequence().first().take(160))
            }
        }
        RecheckRow(nav, pending.isNotEmpty())
        if (past.isNotEmpty()) {
            TextButton(onClick = { showPast = !showPast }) { Text(if (showPast) "Hide past proposals" else "Past proposals") }
            if (showPast) past.take(40).forEach { InboxRow(nav, it) }
        }
    }
}

@Composable
private fun InboxRow(nav: NavigatorViewModel, p: NavProposals.View) {
    val g = nav.graph
    val best = p.effects().maxByOrNull { Math.abs(it.after() - it.before()) }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { nav.openProposal = p.id() }
        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(36.dp).background(statusColor(p.status())))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(p.title() ?: subjectTitle(nav, p) ?: tr(triggerLabel(p.trigger(), p.task())),
                style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, maxLines = 2)
            Hint(listOfNotNull(
                tr(triggerLabel(p.trigger(), p.task())),
                tr(statusLabel(p.status())),
                if (p.version() > 1) "v" + p.version() else null,
                best?.let { e -> "P(" + (g?.node(e.nodeId())?.id ?: e.nodeId()) + ") " + signed(e.after() - e.before()) },
                if (p.thread().isNotEmpty()) "💬 " + p.thread().size else null
            ).joinToString("  ·  "))
        }
        Text("›", style = MaterialTheme.typography.titleMedium, color = Palette.Muted)
    }
    HorizontalDivider()
}

/**
 * Re-check against now: the app takes out what no longer applies and measures the rest
 * again; ticked, it also puts a few older ones back to the model (a call each).
 */
@Composable
private fun RecheckRow(nav: NavigatorViewModel, anything: Boolean) {
    var withModel by remember { mutableStateOf(true) }
    Column(Modifier.padding(top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { nav.recheck(withModel) }, enabled = anything && !nav.busy && !nav.rechecking) {
                Text(if (nav.rechecking) "Re-checking…" else "Re-check against now")
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = withModel, onCheckedChange = { withModel = it })
            Hint(tr("Also ask the model about older ones") + " (" + tr("at most") + " " +
                com.chronos.navigator.llm.client.Recheck.MODEL_MAX + " " + tr("calls") + ")")
        }
        Hint("Proposals whose subject is finished or gone, whose period or date has passed, or that refer to a " +
             "Chronos activity that no longer exists leave the inbox as outdated; the rest get today's measured effect.")
    }
}

private fun signed(d: Double): String { val n = Math.round(d * 100); return (if (n >= 0) "+" else "") + n }

private fun subjectTitle(nav: NavigatorViewModel, p: NavProposals.View): String? =
    p.subject()?.split(",")?.mapNotNull { nav.graph?.node(it)?.title }?.joinToString(", ")?.ifBlank { null }

/** The full page. */
@Composable
internal fun ProposalPage(nav: NavigatorViewModel, id: String) {
    val p = nav.proposals.firstOrNull { it.id() == id }
    BackHandler { nav.openProposal = null }
    TextButton(onClick = { nav.openProposal = null }) { Text("‹ " + tr("Back")) }
    if (p == null) { Hint("That proposal is gone."); return }
    val g = nav.graph
    val uri = LocalUriHandler.current
    val at = Instant.ofEpochMilli(p.createdAt()).atZone(ZoneId.systemDefault()).toLocalDate()

    // ---- header
    Panel(null, statusColor(p.status())) {
        Text(p.title() ?: subjectTitle(nav, p) ?: tr(triggerLabel(p.trigger(), p.task())),
            style = MaterialTheme.typography.titleMedium)
        FlowRowSimple {
            Tag(tr(statusLabel(p.status())), statusColor(p.status()))
            Tag(tr(triggerLabel(p.trigger(), p.task())), Palette.Ochre)
            if (p.version() > 1) Tag(tr("version") + " " + p.version(), Palette.Indigo)
            Tag(dateText(at), Palette.Muted)
        }
        p.summary()?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
        p.reason()?.let { Text(tr(if (p.status() == "OUTDATED") "No longer applies" else "Rejected by the checks") + ": " + it,
            style = MaterialTheme.typography.labelSmall, color = Palette.Brick) }
        if (p.status() == "OUTDATED")
            TextButton(onClick = { nav.restoreProposal(p.id()) }, enabled = !nav.busy) { Text("Put it back in the inbox") }
    }

    val ticked = remember(p.id()) {
        mutableStateOf((0 until (p.crew()?.getAsJsonArray("groups")?.size() ?: 0)).toSet())
    }
    // A crew package: what changes and the decision first; the details below, folded.
    if (p.fromCrew()) { CrewProposal(nav, p, ticked); CrewDecide(nav, p, ticked) }

    // ---- what it is for
    if (!p.fromCrew()) Panel("What it is for", Palette.Indigo) {
        val nodes = (p.subject()?.split(",").orEmpty() + p.ops().mapNotNull { op ->
            op.key().removePrefix("node.").substringBeforeLast('.').takeIf { op.key().startsWith("node.") }
        } + p.ops().filter { it.kind() == Op.DISCUSS }.map { it.key().removePrefix("discuss.") })
            .distinct().mapNotNull { g?.node(it) }
        val regimes = p.ops().filter { it.key().startsWith("regime.") }
            .map { it.key().removePrefix("regime.").substringBeforeLast('.') }.distinct()
        if (nodes.isEmpty() && regimes.isEmpty()) Hint("The whole graph.")
        nodes.forEach { n ->
            Column(Modifier.padding(vertical = 4.dp)) {
                Text(n.title ?: n.id, style = MaterialTheme.typography.bodySmall, color = typeColor(n.type),
                    fontWeight = FontWeight.Medium)
                Hint(listOfNotNull(n.id, n.type?.let { tr(typeLabel(it)) }, n.owner?.let { tr(ownerLabel(it)) },
                    n.phase?.let { tr("Phase") + " " + it }, n.deadline?.let { tr("due") + " " + dateText(it) }).joinToString("  ·  "))
                NowLine(n, nav.nodeNow[n.id])
                nav.nodeActivities[n.id]?.takeIf { it.isNotEmpty() }?.let {
                    Hint(tr("In Chronos") + ": " + it.joinToString(", "))
                }
            }
        }
        regimes.forEach { r ->
            val reg = g?.regime(r)
            Text(tr("Regime") + " " + r, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            reg?.let { Hint(dateText(it.from) + " – " + dateText(it.to) + (it.mix?.let { m -> "  ·  " + m.entries.joinToString(" / ") { e -> "${Math.round(100 * e.value)}%" } } ?: "")) }
        }
    }

    // ---- what changes
    if (!p.fromCrew()) Panel("What it would change", Palette.Teal) {
        if (p.ops().isEmpty()) Hint("Nothing: these are findings to read and questions to think about.")
        if (p.expansion()) ExpansionLines(p) else p.ops().forEach { OpLine(it) }
        val prev = p.parentId()?.let { pid -> nav.proposals.firstOrNull { it.id() == pid } }
        if (prev != null) {
            Text(tr("Changed from version") + " " + prev.version(), style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 8.dp))
            VersionDiff(prev, p)
        }
    }

    // ---- effect
    if (p.effects().isNotEmpty() && !p.fromCrew()) Panel("What it does to your deadlines", Palette.Moss) {
        p.effects().forEach { e ->
            val n = g?.node(e.nodeId())
            Text(n?.title ?: e.nodeId(), style = MaterialTheme.typography.bodySmall)
            EffectBars(e.before(), e.after())
        }
        Hint(tr("Measured when it was proposed") + " (" + dateText(at) + ") " +
             tr("by the app's own forecast, with and without it, on the same simulated futures. The model does not state these numbers; the node cards show tonight's newer forecast."))
    }

    // ---- basis
    if (!p.fromCrew()) Panel("On what basis", Palette.Steel) {
        p.rationale()?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        nav.calls.firstOrNull { it.id == p.callId() }?.let { c ->
            Hint(tr("Answered by") + " " + (c.model ?: "") + "  ·  $" + "%.3f".format(c.costUsd))
        }
        if (p.sources().isNotEmpty()) {
            Text(tr("Sources"), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
            p.sources().forEach { s ->
                Text(s.url() + (s.usedFor()?.let { " — $it" } ?: ""), style = MaterialTheme.typography.labelSmall, color = Palette.Steel,
                    modifier = Modifier.clickable { runCatching { uri.openUri(s.url()) } })
            }
        }
        if (p.checks().isNotEmpty()) Hint(tr("Checks passed") + ": " + p.checks().joinToString(", "))
        Hint("The model saw a compact snapshot of your graph — dates, titles, owners, the forecast and this week's " +
             "hours — never your log, and never the nodes that involve another person.")
    }

    // ---- conversation
    Panel("Talk it over with the model", Palette.Plum) {
        if (p.thread().isEmpty())
            Hint("Ask why, say what you disagree with, or suggest a change. The model answers with the same snapshot " +
                 "in front of it; if it agrees, it revises the proposal and the new version waits for you here.")
        p.thread().forEach { t -> Bubble(t.text(), mine = t.who() == "you") }
        var draft by remember(id) { mutableStateOf("") }
        // Yours not yet answered (27 Oct 2026): kept on the phone until a model answers, never lost.
        nav.outbox.filter { it.proposalId == p.id() }.forEach { n ->
            Bubble(n.text, mine = true)
            if (n.status == "FAILED") {
                Text(tr("Not sent") + ": " + (n.error ?: ""), style = MaterialTheme.typography.labelSmall, color = Palette.Brick)
                Row {
                    TextButton(onClick = { nav.resend(n.id) }) { Text("Send again") }
                    TextButton(onClick = { draft = n.text; nav.dropNote(n.id) }) { Text("Edit") }
                    TextButton(onClick = { nav.dropNote(n.id) }) { Text("Delete") }
                }
            } else Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                Hint(if (n.error == null) tr("The model is answering… You may leave this page; the answer comes here.")
                     else tr("Trying again by itself") + " (" + n.tries + "): " + n.error)
            }
        }
        OutlinedTextField(value = draft, onValueChange = { draft = it }, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(tr("Ask why, or say what you would change…"), style = MaterialTheme.typography.bodySmall) },
            minLines = 2,
            trailingIcon = { SendIcon(draft.isNotBlank()) {
                val text = draft.trim(); draft = ""; nav.discuss(p.id(), text) } })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { val text = draft.trim(); draft = ""; nav.discuss(p.id(), text) },
                enabled = draft.isNotBlank()) { Text("Send") }
            Spacer(Modifier.width(8.dp))
            Hint(tr("Each message is one call to the model, within your budget. If the provider in use is busy, the others you set up answer it."))
        }
        if (p.status() != "PENDING")
            Hint("This version is no longer waiting: the model can still explain it, but not revise it.")
    }

    if (p.fromCrew()) CrewDetails(p)

    // ---- decide
    if (!p.fromCrew() && p.status() == "PENDING") Panel {
        var copyFrom by remember(p.id()) {
            mutableStateOf(nav.nodeActivities[p.subject()]?.firstOrNull()?.let { a ->
                nav.activities.firstOrNull { it.trim().lowercase() == a }
            })
        }
        if (p.expansion())
            Choice("Set up the new tasks in Chronos like", copyFrom, nav.activities.map { it to it },
                hint = "Each task becomes its own goal and activity, with this activity's windows, block lengths " +
                       "and load. It gets hours only from a week you accept.") { copyFrom = it }
        Row {
            Button(onClick = { nav.decide(p.id(), true, copyFrom); nav.openProposal = null },
                enabled = !nav.busy && (!p.expansion() || copyFrom != null)) {
                Text(if (p.noteOnly()) "Noted" else "Approve")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { nav.decide(p.id(), false); nav.openProposal = null }, enabled = !nav.busy) { Text("Reject") }
        }
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    Box(Modifier.padding(end = 4.dp, top = 4.dp).clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.13f))
        .padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

/** Before and after, as two bars on the same 0–100% scale. */
@Composable
private fun EffectBars(before: Double, after: Double) {
    val d = Math.round((after - before) * 100)
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("now"), style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(52.dp))
            Box(Modifier.weight(1f)) { Meter(before.toFloat(), Palette.Muted) }
            Text(" ${Math.round(before * 100)}%", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(44.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("with it"), style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(52.dp))
            Box(Modifier.weight(1f)) { Meter(after.toFloat(), if (d > 0) Palette.Moss else if (d < 0) Palette.Brick else Palette.Muted) }
            Text(" ${Math.round(after * 100)}%", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                modifier = Modifier.width(44.dp))
        }
    }
}

/** What a revision changed: each value of the previous version against this one. */
@Composable
private fun VersionDiff(prev: NavProposals.View, now: NavProposals.View) {
    val before = prev.ops().associateBy { it.key() }
    val after = now.ops().associateBy { it.key() }
    (before.keys + after.keys).distinct().forEach { key ->
        val a = before[key]; val b = after[key]
        val text = when {
            a == null -> "+ " + (b?.note() ?: key)
            b == null -> "− " + (a.note() ?: key)
            a.values() != b.values() -> key + ": " + a.values().entries.joinToString { "${it.key}=${trimNumber(Math.round(it.value * 100) / 100.0)}" } +
                "  →  " + b.values().entries.joinToString { "${it.key}=${trimNumber(Math.round(it.value * 100) / 100.0)}" }
            else -> null
        }
        text?.let { Hint(it) }
    }
    val pb = prev.effects().associateBy { it.nodeId() }
    now.effects().forEach { e ->
        pb[e.nodeId()]?.let { o -> if (Math.round(o.after() * 100) != Math.round(e.after() * 100))
            Hint("P(" + e.nodeId() + "): " + Math.round(o.after() * 100) + "% → " + Math.round(e.after() * 100) + "%") }
    }
}

@Composable
internal fun Bubble(text: String, mine: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Box(Modifier.fillMaxWidth(0.85f).clip(RoundedCornerShape(12.dp))
            .background(if (mine) Palette.Indigo.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 6.dp)) {
            // Long-press selects and copies.
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(text, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
