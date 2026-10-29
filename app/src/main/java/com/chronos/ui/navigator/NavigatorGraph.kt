package com.chronos.ui.navigator

import com.chronos.ui.*
import com.chronos.ui.common.*
import com.chronos.ui.setup.*
import com.chronos.ui.horizon.*
import com.chronos.ui.day.*
import com.chronos.ui.log.*

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.chronos.navigator.model.Graph
import com.chronos.navigator.model.Kinds
import com.chronos.navigator.model.Kinds.EdgeKind
import com.chronos.navigator.model.Kinds.NodeType
import com.chronos.navigator.model.Node
import com.chronos.navigator.model.Requirements
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/*
 * The goal graph as a wizard: one page per phase, a coloured card per node.
 * Colour says what kind of node it is; the small line under the title says
 * who owns it and when it is due. Tapping a card opens its details, and
 * tapping a node named there jumps to its page.
 */

/** One colour per node type, used for the card's rule, its chip and the legend. */
internal fun typeColor(t: NodeType?): Color = when (t) {
    NodeType.STREAM -> Palette.Steel
    NodeType.SKILL -> Palette.Teal
    NodeType.TASK -> Palette.Indigo
    NodeType.MILESTONE -> Palette.Ochre
    NodeType.GATE -> Palette.forNight(Color(0xFF9E5A7A))
    NodeType.DECISION -> Palette.Plum
    NodeType.TERMINAL -> Palette.Moss
    NodeType.EXTERNAL_TRACK -> Palette.forNight(Color(0xFF34495E))
    null -> Palette.Muted
}

internal fun typeLabel(t: NodeType) = when (t) {
    NodeType.STREAM -> "Stream"; NodeType.SKILL -> "Skill"; NodeType.TASK -> "Task"
    NodeType.MILESTONE -> "Milestone"; NodeType.GATE -> "Gate"; NodeType.DECISION -> "Decision"
    NodeType.TERMINAL -> "Final goal"; NodeType.EXTERNAL_TRACK -> "External track"
}

/** What a node of this type is, in one sentence — for the legend and the Missing-data explanations. */
internal fun typeMeaning(t: NodeType) = when (t) {
    NodeType.STREAM -> "Ongoing work with no end, paced by hours per week (coursework, a language)."
    NodeType.SKILL -> "A level from 0 to 10 you are raising; it finishes when the target level is reached."
    NodeType.TASK -> "Bounded work: it finishes once its hours are spent."
    NodeType.MILESTONE -> "A checkpoint, reached when what it depends on is done."
    NodeType.GATE -> "An outcome someone else decides (an admission, an offer); it has a chance of success."
    NodeType.DECISION -> "A choice between options that you make on a set date."
    NodeType.TERMINAL -> "The final goal at the end of the graph."
    NodeType.EXTERNAL_TRACK -> "A container of tasks your advisor assigns; scheduled, never restructured."
}

internal fun ownerLabel(o: Kinds.Owner) = when (o) {
    Kinds.Owner.ADVISOR -> "advisor"; Kinds.Owner.USER -> "you"
    Kinds.Owner.SYSTEM_SUGGESTED -> "suggested"; Kinds.Owner.EXTERNAL -> "outside"
}

internal fun ownerColor(o: Kinds.Owner?) = when (o) {
    Kinds.Owner.ADVISOR -> Palette.Indigo; Kinds.Owner.USER -> Palette.Steel
    Kinds.Owner.SYSTEM_SUGGESTED -> Palette.Teal; else -> Palette.Muted
}

private val PHASE_COLORS get() = listOf(Palette.Steel, Palette.Indigo, Palette.Moss, Palette.Ochre, Palette.Teal, Palette.Plum)

private fun phaseColor(index: Int) = PHASE_COLORS[index % PHASE_COLORS.size]

/** A phase's name if the file gives one under meta.phases, e.g. {"P1": "Now: advisor track"}. */
private fun phaseName(g: Graph, phase: String): String? =
    g.raw?.getAsJsonObject("meta")?.getAsJsonObject("phases")?.get(phase)
        ?.takeIf { it.isJsonPrimitive }?.asString

/** Dates a node is pinned to: its window, its expansion, its decision. */
private fun datesOf(n: Node) = listOfNotNull(n.earliest, n.deadline, n.decideBy, n.expandAt)

@Composable
internal fun GraphWizard(g: Graph, nav: NavigatorViewModel? = null) {
    val phases = remember(g) { g.nodes.values.map { it.phase ?: "?" }.distinct().sorted() }
    if (phases.isEmpty()) return
    var page by rememberSaveable { mutableIntStateOf(0) }
    var focus by rememberSaveable { mutableStateOf<String?>(null) }
    page = page.coerceIn(0, phases.lastIndex)
    val phase = phases[page]
    val accent = phaseColor(page)
    val jump: (String) -> Unit = { id ->
        g.node(id)?.let { target -> page = phases.indexOf(target.phase ?: "?").coerceAtLeast(0); focus = id }
    }

    Panel("Graph", accent) {
        Hint("One page per phase. The colour of a card says what kind of node it is; tap a card for its " +
             "details, and tap a node named there to go to it.")
        PhaseBar(phases, page) { page = it; focus = null }
        Legend()

        val nodes = g.nodes.values.filter { (it.phase ?: "?") == phase }
        val dates = nodes.flatMap { datesOf(it) }.sorted()
        Text((phaseName(g, phase)?.let { "$phase · $it" } ?: (tr("Phase") + " " + phase)),
            style = MaterialTheme.typography.titleMedium, color = accent,
            modifier = Modifier.padding(top = 10.dp))
        val lang = LocalLang.current
        val counts = nodes.groupBy { it.type }.entries.sortedBy { it.key?.ordinal ?: 99 }
            .joinToString("  ·  ") { (t, ns) -> "${ns.size} " + translate(lang, t?.let { typeLabel(it) } ?: "?") }
        Hint(counts)
        if (dates.isNotEmpty())
            Hint(tr("Dates in this phase run from") + " " + dateText(dates.first()) + " " +
                 tr("to") + " " + dateText(dates.last()))

        // A node sits under its container when both are in this phase.
        val parentOf = g.edges.filter { it.kind() == EdgeKind.CHILD }.associate { it.from() to it.to() }
        fun underParent(n: Node) = parentOf[n.id]?.let { g.node(it)?.phase == n.phase } == true
        nodes.filter { !underParent(it) }.forEach { n ->
            NodeCard(g, n, 0, focus == n.id, jump, nav)
            nodes.filter { parentOf[it.id] == n.id }.forEach { NodeCard(g, it, 1, focus == it.id, jump, nav) }
        }

        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { page--; focus = null }, enabled = page > 0) { Text("Back") }
            Spacer(Modifier.weight(1f))
            Text("${page + 1} / ${phases.size}", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Button(onClick = { page++; focus = null }, enabled = page < phases.lastIndex,
                colors = ButtonDefaults.buttonColors(containerColor = phaseColor((page + 1) % phases.size))) {
                Text(if (page < phases.lastIndex) tr("Next") + ": " + phases[page + 1] else tr("Next"))
            }
        }
    }
}

@Composable
private fun PhaseBar(phases: List<String>, current: Int, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically) {
        phases.forEachIndexed { i, p ->
            val c = phaseColor(i)
            val selected = i == current
            Box(
                Modifier.padding(end = 6.dp).clip(RoundedCornerShape(16.dp))
                    .background(if (selected) c else c.copy(alpha = 0.10f))
                    .border(BorderStroke(1.dp, c.copy(alpha = if (selected) 1f else 0.45f)), RoundedCornerShape(16.dp))
                    .clickable { onPick(i) }
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            ) {
                Text(p, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                    color = if (selected) Palette.OnFill else c)
            }
        }
    }
}

@Composable
private fun Legend() {
    var open by remember { mutableStateOf(false) }
    FlowRowSimple {
        NodeType.entries.forEach { t ->
            Row(Modifier.padding(end = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(typeColor(t)))
                Spacer(Modifier.width(4.dp))
                Text(typeLabel(t), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    TextButton(onClick = { open = !open }) { Text(if (open) "Hide" else "What do the kinds mean?") }
    if (open) NodeType.entries.forEach { t ->
        Row(Modifier.padding(vertical = 2.dp)) {
            Text(tr(typeLabel(t)) + ": ", style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold, color = typeColor(t))
            Text(typeMeaning(t), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun Chip(text: String, color: Color, filled: Boolean = false) {
    Box(
        Modifier.padding(end = 4.dp, bottom = 4.dp).clip(RoundedCornerShape(10.dp))
            .background(if (filled) color else color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = if (filled) Palette.OnFill else color)
    }
}

@Composable
private fun NodeCard(g: Graph, n: Node, depth: Int, focused: Boolean, jump: (String) -> Unit, nav: NavigatorViewModel?) {
    var open by remember(n.id) { mutableStateOf(false) }
    LaunchedEffect(focused) { if (focused) open = true }
    val color = typeColor(n.type)
    val surface = MaterialTheme.colorScheme.surface
    val today = LocalDate.now()
    Card(
        modifier = Modifier.fillMaxWidth().padding(start = (depth * 18).dp, top = 4.dp, bottom = 4.dp)
            .clickable { open = !open },
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = if (focused) 0.16f else 0.06f).compositeOver(surface)),
        border = BorderStroke(if (focused) 2.dp else 1.dp, color.copy(alpha = if (focused) 1f else 0.35f)),
        shape = RoundedCornerShape(6.dp)
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(5.dp).fillMaxHeight().background(color))
            Column(Modifier.weight(1f).padding(10.dp)) {
                Text(n.title ?: n.id, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                FlowRowSimple {
                    n.type?.let { Chip(tr(typeLabel(it)), color, filled = true) }
                    n.owner?.let { Chip(tr(ownerLabel(it)), ownerColor(it)) }
                    n.status?.let { Chip(it, Palette.Muted) }
                    if (Requirements.pausedOn(g, n, today)) Chip(tr("paused now"), Palette.Sand)
                    n.deadline?.let { d ->
                        val left = ChronoUnit.DAYS.between(today, d)
                        Chip(tr("due") + " " + dateText(d) + if (left in 0..60) " (" + left + " " + tr("days") + ")" else "",
                            if (left in 0..14) Palette.Brick else Palette.Muted)
                    }
                }
                if (nav != null) NowLine(n, nav.nodeNow[n.id])
                Text(n.id, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (open) {
                    if (nav != null) NowDetails(n, nav.nodeNow[n.id])
                    NodeDetails(g, n, jump)
                    if (nav != null) NodeActions(nav, n)
                }
            }
        }
    }
}

@Composable
internal fun NodeDetails(g: Graph, n: Node, jump: (String) -> Unit) {
    Column(Modifier.padding(top = 6.dp)) {
        n.type?.let { Hint(typeMeaning(it)) }
        n.earliest?.let { Hint(tr("Not before") + " " + dateText(it)) }
        n.expandAt?.let { Hint(tr("To be broken down on") + " " + dateText(it)) }
        n.decideBy?.let { Hint(tr("Decide by") + " " + dateText(it)) }
        if (n.effortP50 != null) Hint(tr("Effort") + ": " + trimNumber(n.effortP50) + "–" +
            trimNumber(n.effortP90 ?: n.effortP50) + " " + tr("hours (median to 90th percentile)"))
        if (n.perUnitPrior != null) Hint(tr("Hours per unit") + ": " + trimNumber(n.perUnitPrior))
        if (n.unitsTotal != null) Hint(tr("Units") + ": " + trimNumber(n.unitsDone ?: 0.0) + " / " +
            trimNumber(n.unitsTotal) + " " + (n.unitName ?: ""))
        if (n.levelFrom != null) Hint(tr("Level") + ": " + trimNumber(n.levelFrom) + " → " + trimNumber(n.levelTo ?: n.levelFrom))
        if (n.weeklyFloorH != null) Hint(tr("Weekly floor") + ": " + trimNumber(n.weeklyFloorH) + " h" +
            (n.maxHPerDay?.let { "  ·  " + tr("at most") + " " + trimNumber(it) + " h/" + tr("day") } ?: ""))
        if (n.successA != null && n.successB != null) Hint(tr("Chance of success") + ": " +
            Math.round(100 * n.successA / (n.successA + n.successB)) + "%")
        if (n.activities.isNotEmpty()) Hint(tr("Chronos activities") + ": " + n.activities.joinToString(", ") { it.trim() })
        if (n.consentRequired) Hint(tr("Only through") + ": " + (n.consentChannel ?: ""))
        if (n.pausedDuring.isNotEmpty()) Hint(tr("Paused during") + ": " + n.pausedDuring.joinToString(", "))
        n.raw?.get("note")?.takeIf { it.isJsonPrimitive }?.let { Hint(it.asString) }

        LinkLine(g, "Needs", g.into(n.id, EdgeKind.AND).map { it.from() }, jump)
        g.into(n.id, EdgeKind.OR_GROUP).groupBy { it.group() }.values.forEachIndexed { i, grp ->
            LinkLine(g, if (i == 0) "Needs one of" else "or", grp.map { it.from() }, jump)
        }
        LinkLine(g, "Helped by", g.into(n.id, EdgeKind.SOFT).map { it.from() }, jump)
        LinkLine(g, "Feeds", g.outOf(n.id).filter { it.kind() == EdgeKind.CONTRIBUTES }.map { it.to() }, jump)
        LinkLine(g, "Leads to", g.outOf(n.id).filter { it.kind() == EdgeKind.AND || it.kind() == EdgeKind.OR_GROUP }
            .map { it.to() }, jump)
    }
}

/** What you can do with a node from its card: tick a task done, or have a coarse one broken down now. */
@Composable
private fun NodeActions(nav: NavigatorViewModel, n: Node) {
    val done = n.status?.lowercase()?.let { it.startsWith("done") || it.startsWith("reached") } == true
    val owned = n.owner != Kinds.Owner.ADVISOR && n.owner != Kinds.Owner.EXTERNAL
    n.raw?.get("deliverable")?.takeIf { it.isJsonPrimitive }?.let { Hint(tr("Done when") + ": " + it.asString) }
    n.raw?.get("expanded_from")?.takeIf { it.isJsonPrimitive }?.let { Hint(tr("A task of") + " " + it.asString) }
    if (n.expanded) Hint("Broken down: its tasks carry the work, and it is reached when they are done.")
    PriorityRow(nav, n)
    Row {
        if (n.type == NodeType.TASK && !n.expanded)
            TextButton(onClick = { nav.markDone(n.id, !done) }, enabled = !nav.busy) {
                Text(if (done) "Open again" else "Mark done")
            }
        if (!done && !n.expanded && owned && n.plannable() && (n.type == NodeType.TASK || n.type == NodeType.MILESTONE) &&
                (n.expandAt != null || n.granularity == "coarse" || n.granularity == "medium"))
            TextButton(onClick = { nav.askExpand(n.id) }, enabled = !nav.busy) { Text("Break it down now") }
    }
}

@Composable
private fun LinkLine(g: Graph, label: String, ids: List<String>, jump: (String) -> Unit) {
    if (ids.isEmpty()) return
    Text(tr(label), style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    FlowRowSimple {
        ids.distinct().forEach { id ->
            val m = g.node(id)
            Box(Modifier.padding(end = 4.dp, bottom = 4.dp).clip(RoundedCornerShape(10.dp))
                .border(BorderStroke(1.dp, typeColor(m?.type)), RoundedCornerShape(10.dp))
                .clickable { jump(id) }.padding(horizontal = 8.dp, vertical = 3.dp)) {
                Text(m?.title?.let { if (it.length > 42) it.take(40) + "…" else it } ?: id,
                    style = MaterialTheme.typography.labelSmall, color = typeColor(m?.type))
            }
        }
    }
}

/**
 * Where a node sits, for the Missing-data explanations: its phase, what comes
 * before it, and everything downstream that cannot be forecast without it.
 */
internal fun downstream(g: Graph, id: String): List<Node> {
    val seen = LinkedHashSet<String>()
    val queue = ArrayDeque(listOf(id))
    while (queue.isNotEmpty()) {
        val at = queue.removeFirst()
        for (e in g.outOf(at)) if (e.kind() != EdgeKind.CREDIT && seen.add(e.to())) queue.add(e.to())
    }
    return seen.mapNotNull { g.node(it) }
}
