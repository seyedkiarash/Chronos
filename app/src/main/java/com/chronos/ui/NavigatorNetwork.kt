package com.chronos.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chronos.navigator.model.Graph
import com.chronos.navigator.model.Kinds.EdgeKind
import com.chronos.navigator.model.Kinds.NodeType
import com.chronos.navigator.model.Node
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.min

/*
 * The whole path in one picture: a grid whose columns are stretches of time —
 * months while they are close, then quarters, then years — and whose lanes
 * are the graph's phases. Each node sits in the column of its likely
 * finishing date; nodes that share a column and a lane are stacked, never
 * overlapped. Colour is the chance of meeting its deadline. Tap a node: the
 * chain that decides its date — each step back the dependency that finishes
 * last — turns red, and the path to it opens below. Drag sideways to move,
 * pinch to zoom.
 */

private fun pFill(p: Double?): Color = when {
    p == null -> Palette.Muted
    p >= 0.7 -> Palette.Moss
    p >= 0.4 -> Palette.Sand
    else -> Palette.Brick
}

private fun done(n: Node) = n.status?.lowercase()?.let { it.startsWith("done") || it.startsWith("reached") } == true

/** A stretch of time on the map: a month while it is near, a quarter within the year, then a year. */
private data class Column(val key: String, val label: String, val start: LocalDate)

private fun columnOf(d: LocalDate?, today: LocalDate): Column {
    if (d == null) return Column("~", "later", LocalDate.MAX)
    val months = ChronoUnit.MONTHS.between(today.withDayOfMonth(1), d.withDayOfMonth(1))
    return when {
        months < 4 -> Column("m" + d.year + "-" + d.monthValue, d.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() },
            d.withDayOfMonth(1))
        months < 12 -> {
            val q = (d.monthValue - 1) / 3
            Column("q" + d.year + "-" + q, "Q" + (q + 1) + " " + d.year, LocalDate.of(d.year, q * 3 + 1, 1))
        }
        else -> {
            // A year the quarters already reached into starts where they end, so the columns stay in order.
            val quartersEnd = today.withDayOfMonth(1).plusMonths(12)
            val jan = LocalDate.of(d.year, 1, 1)
            if (jan.isBefore(quartersEnd)) Column("y" + d.year, "rest of " + d.year, quartersEnd)
            else Column("y" + d.year, d.year.toString(), jan)
        }
    }
}

/** A node's place on the map at the normal zoom, in pixels. */
private data class Spot(val node: Node, val x: Float, val y: Float, val col: Int, val p: Double?, val likely: LocalDate?)

private data class Layout(val spots: List<Spot>, val columns: List<Column>, val laneTops: List<Float>, val laneNames: List<String>,
                          val width: Float, val height: Float, val colW: Float, val left: Float)

private fun labelOf(n: Node, p: Double?): String =
    n.id + (p?.takeIf { n.deadline != null }?.let { " " + Math.round(it * 100) + "%" } ?: "")

@Composable
internal fun NetworkPanel(nav: NavigatorViewModel, g: Graph) {
    val today = LocalDate.now()
    val f = nav.forecast
    var selected by remember { mutableStateOf<String?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()

    // Ongoing streams have no finishing date: they are listed above the map, not placed on it.
    val streams = remember(g) { g.nodes.values.filter { it.plannable() && it.type == NodeType.STREAM && !done(it) } }

    val layout = remember(g, f, density) {
        with(density) {
            val colW = 104.dp.toPx(); val rowH = 40.dp.toPx(); val head = 26.dp.toPx(); val left = 26.dp.toPx()
            val nodes = g.nodes.values.filter { it.plannable() && it.type != NodeType.STREAM }.map { n ->
                val r = f?.nodes()?.get(n.id)
                val likely = r?.p50?.let { LocalDate.parse(it) }
                val date = if (done(n)) today else likely ?: n.deadline ?: n.decideBy ?: n.expandAt
                Triple(n, columnOf(date, today), r)
            }
            val columns = nodes.map { it.second }.distinctBy { it.key }.sortedBy { it.start }
            val laneNames = nodes.map { it.first.phase ?: "?" }.distinct().sorted()
            // rows: per lane, as many as its most crowded column needs
            val cells = nodes.groupBy { (it.first.phase ?: "?") to it.second.key }
            val rows = laneNames.associateWith { lane -> cells.filterKeys { it.first == lane }.values.maxOfOrNull { it.size } ?: 1 }
            val tops = mutableListOf<Float>()
            var y = head
            for (lane in laneNames) { tops += y; y += rows.getValue(lane) * rowH + 6.dp.toPx() }
            val spots = cells.flatMap { (key, list) ->
                val lane = laneNames.indexOf(key.first)
                val col = columns.indexOfFirst { it.key == key.second }
                list.sortedBy { it.third?.p50 ?: "~" }.mapIndexed { i, (n, _, r) ->
                    Spot(n, left + col * colW + 14.dp.toPx(), tops[lane] + i * rowH + rowH * 0.38f,
                        col, r?.pByDeadline, r?.p50?.let { LocalDate.parse(it) })
                }
            }
            Layout(spots, columns, tops, laneNames, left + columns.size * colW + 8.dp.toPx(), y + 4.dp.toPx(), colW, left)
        }
    }
    val byId = layout.spots.associateBy { it.node.id }

    // The chain that decides the selected node's date: back through the dependency that finishes last.
    val chain = remember(selected, layout) {
        val out = mutableListOf<Pair<String, String>>()
        var at = selected
        val seen = mutableSetOf<String>()
        while (at != null && seen.add(at)) {
            // What it waits for: every AND dependency, and of its "any of" groups the one done first.
            val and = g.into(at, EdgeKind.AND).mapNotNull { byId[it.from()] }
            val groups = g.into(at, EdgeKind.OR_GROUP).groupBy { it.group() }.values
                .map { grp -> grp.mapNotNull { byId[it.from()] } }.filter { it.isNotEmpty() }
            val firstWay = groups.minByOrNull { grp -> grp.maxOf { it.likely ?: LocalDate.MAX } }
            val latest = (and + (firstWay ?: emptyList())).maxByOrNull { it.likely ?: LocalDate.MAX } ?: break
            out += latest.node.id to at
            at = latest.node.id
        }
        out.toSet()
    }

    Panel("Map of the whole path", Palette.Indigo) {
        Hint("Columns are stretches of time — months while they are near, then quarters, then years — and rows are " +
             "the phases. Each node sits where it will likely finish; colour is its chance of meeting its deadline. " +
             "Tap a node: the chain that decides its date turns red. Drag sideways to move, pinch to zoom.")
        if (streams.isNotEmpty()) {
            Text(tr("Ongoing"), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
            FlowRowSimple {
                streams.forEach { n ->
                    val now = nav.nodeNow[n.id]
                    Box(Modifier.padding(end = 6.dp, bottom = 4.dp).clip(RoundedCornerShape(10.dp))
                        .background(typeColor(n.type).copy(alpha = 0.12f)).clickable { selected = n.id }
                        .padding(horizontal = 8.dp, vertical = 3.dp)) {
                        Text(n.id + (now?.weekHours?.let { " · " + trimNumber(it) + " h" } ?: "") +
                             (now?.adherence?.let { " · " + Math.round(it * 100) + "%" } ?: ""),
                            style = MaterialTheme.typography.labelSmall, color = typeColor(n.type))
                    }
                }
            }
        }
        val labelColor = MaterialTheme.colorScheme.onSurface
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        val faint = MaterialTheme.colorScheme.outlineVariant
        val laneColor = MaterialTheme.colorScheme.surfaceVariant
        val mapHeight = with(density) { layout.height.toDp() }
        val lang = LocalLang.current
        Ltr {
            Box(Modifier.fillMaxWidth().height(mapHeight).clip(RoundedCornerShape(8.dp))
                .border(0.5.dp, faint, RoundedCornerShape(8.dp))) {
                Canvas(Modifier.fillMaxSize()
                    // Two fingers zoom and move; one finger moves sideways only, so a vertical drag scrolls the page.
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val event = awaitPointerEvent()
                                if (event.changes.count { it.pressed } >= 2) {
                                    scale = (scale * event.calculateZoom()).coerceIn(0.4f, 3f)
                                    offset += event.calculatePan()
                                    event.changes.forEach { it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures { change, dx -> offset += Offset(dx, 0f); change.consume() }
                    }
                    .pointerInput(layout, scale, offset) {
                        detectTapGestures(
                            onDoubleTap = { scale = 1f; offset = Offset.Zero },
                            onTap = { tap ->
                                val hit = layout.spots.minByOrNull { (Offset(it.x, it.y) * scale + offset - tap).getDistance() }
                                if (hit != null && (Offset(hit.x, hit.y) * scale + offset - tap).getDistance() < 26.dp.toPx())
                                    selected = if (selected == hit.node.id) null else hit.node.id
                            })
                    }
                ) {
                    fun at(x: Float, y: Float) = Offset(x * scale + offset.x, y * scale + offset.y)
                    val ts = scale.coerceIn(0.7f, 1.6f)
                    val small = TextStyle(fontSize = (9 * ts).sp, color = labelColor)
                    val tiny = TextStyle(fontSize = (8 * ts).sp, color = muted)
                    // lanes
                    layout.laneNames.forEachIndexed { i, name ->
                        val top = at(0f, layout.laneTops[i]).y
                        val bottom = at(0f, if (i + 1 < layout.laneTops.size) layout.laneTops[i + 1] else layout.height).y
                        if (i % 2 == 0) drawRect(laneColor.copy(alpha = 0.35f), Offset(0f, top), Size(size.width, bottom - top))
                        drawText(measurer.measure(name, tiny, softWrap = false, maxLines = 1), topLeft = Offset(3.dp.toPx(), top + 2.dp.toPx()))
                    }
                    // columns
                    layout.columns.forEachIndexed { c, col ->
                        val x = at(layout.left + c * layout.colW, 0f).x
                        drawLine(faint, Offset(x, 0f), Offset(x, size.height), strokeWidth = 0.5f)
                        drawText(measurer.measure(translate(lang, col.label), tiny, softWrap = false, maxLines = 1),
                            topLeft = Offset(x + 3.dp.toPx(), 5.dp.toPx() + offset.y))
                    }
                    // edges: thin and grey; the selected node's deciding chain red and thick
                    for (e in g.edges) {
                        if (e.kind() != EdgeKind.AND && e.kind() != EdgeKind.OR_GROUP && e.kind() != EdgeKind.SOFT) continue
                        val a = byId[e.from()] ?: continue
                        val b = byId[e.to()] ?: continue
                        val pa = at(a.x, a.y); val pb = at(b.x, b.y)
                        val onChain = (a.node.id to b.node.id) in chain
                        val touches = selected != null && (a.node.id == selected || b.node.id == selected)
                        val color = when { onChain -> Palette.Brick; touches -> labelColor.copy(alpha = 0.6f); else -> faint }
                        val path = Path().apply {
                            moveTo(pa.x, pa.y)
                            val mx = (pa.x + pb.x) / 2
                            cubicTo(mx, pa.y, mx, pb.y, pb.x, pb.y)
                        }
                        drawPath(path, color, style = Stroke(
                            width = (if (onChain) 3f else 1f) * 1.dp.toPx() * min(1.5f, ts),
                            pathEffect = if (e.kind() == EdgeKind.AND) null else PathEffect.dashPathEffect(floatArrayOf(8f, 6f))))
                    }
                    // nodes
                    for (s in layout.spots) {
                        val c = at(s.x, s.y)
                        if (c.x < -60 || c.x > size.width + 60 || c.y < -30 || c.y > size.height + 30) continue
                        val r = (if (s.node.takesHours()) 8 else 6).dp.toPx() * ts
                        val fill = if (done(s.node)) Palette.Moss else if (s.node.deadline == null) Palette.Muted else pFill(s.p)
                        val dim = selected != null && s.node.id != selected && chain.none { it.first == s.node.id }
                        drawCircle(fill.copy(alpha = if (dim) 0.45f else 0.9f), r, c)
                        drawCircle(typeColor(s.node.type), r, c, style = Stroke(if (selected == s.node.id) 3.dp.toPx() else 1.5.dp.toPx()))
                        val layoutText = measurer.measure(labelOf(s.node, s.p),
                            if (selected == s.node.id) small.copy(fontWeight = FontWeight.Bold) else small,
                            softWrap = false, maxLines = 1)
                        // Under the dot, where no line runs (lines leave the dot sideways); never wrapped.
                        drawText(layoutText, topLeft = Offset(c.x - r, c.y + r + 1.dp.toPx()),
                            alpha = if (dim) 0.5f else 1f)
                    }
                }
                Row(Modifier.align(Alignment.BottomEnd).padding(4.dp)) {
                    SmallButton("−") { scale = (scale / 1.3f).coerceAtLeast(0.4f) }
                    SmallButton("+") { scale = (scale * 1.3f).coerceAtMost(3f) }
                    SmallButton("⟲") { scale = 1f; offset = Offset.Zero }
                }
            }
        }
        Legend()
        selected?.let { id -> g.node(id)?.let { PathTree(nav, g, it) { selected = it } } }
            ?: Hint("Tap a node to see what leads to it.")
    }
}

@Composable
private fun SmallButton(text: String, onClick: () -> Unit) {
    Box(Modifier.padding(2.dp).size(32.dp).clip(RoundedCornerShape(6.dp))
        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
        .border(0.5.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
        .clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun Legend() {
    FlowRowSimple {
        listOf(Palette.Moss to "70% or more", Palette.Sand to "40–70%", Palette.Brick to "under 40%", Palette.Muted to "no deadline")
            .forEach { (c, t) ->
                Row(Modifier.padding(end = 10.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(c))
                    Spacer(Modifier.width(4.dp))
                    Text(t, style = MaterialTheme.typography.labelSmall)
                }
            }
    }
    Hint("Dashed red tail: from the deadline (◇) to a likely finish after it. Dashed line: one of several ways in, or only helps.")
}

// ------------------------------------------------------- the path to a node

/**
 * What a node needs, as a tree read from right to left in time: the node,
 * then what it waits for, and what those wait for, a few levels down. Each
 * line has its chance and likely date; tap one to make it the root.
 */
@Composable
private fun PathTree(nav: NavigatorViewModel, g: Graph, root: Node, pick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)
        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(8.dp)).padding(10.dp)) {
        Text(tr("The path to") + " " + (root.title ?: root.id), style = MaterialTheme.typography.titleSmall)
        NowLine(root, nav.nodeNow[root.id])
        val seen = mutableSetOf(root.id)
        TreeLevel(nav, g, root, 1, seen, pick)
        val leads = g.outOf(root.id).filter { it.kind() == EdgeKind.AND || it.kind() == EdgeKind.OR_GROUP }.mapNotNull { g.node(it.to()) }
        if (leads.isNotEmpty()) {
            Text(tr("It leads to"), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            leads.forEach { TreeRow(nav, it, 0, null, pick) }
        }
    }
}

@Composable
private fun TreeLevel(nav: NavigatorViewModel, g: Graph, n: Node, depth: Int, seen: MutableSet<String>, pick: (String) -> Unit) {
    if (depth > 4) return
    val and = g.into(n.id, EdgeKind.AND).mapNotNull { g.node(it.from()) }
    val groups = g.into(n.id, EdgeKind.OR_GROUP).groupBy { it.group() }.values.map { grp -> grp.mapNotNull { g.node(it.from()) } }
    for (m in and) {
        TreeRow(nav, m, depth, if (and.size > 1) "needs all" else "needs", pick)
        if (seen.add(m.id)) TreeLevel(nav, g, m, depth + 1, seen, pick)
    }
    groups.forEachIndexed { i, grp ->
        grp.forEach { m ->
            TreeRow(nav, m, depth, if (groups.size > 1) "one of the ways " + (i + 1) else "one way", pick)
            if (seen.add(m.id)) TreeLevel(nav, g, m, depth + 1, seen, pick)
        }
    }
}

@Composable
private fun TreeRow(nav: NavigatorViewModel, m: Node, depth: Int, how: String?, pick: (String) -> Unit) {
    val now = nav.nodeNow[m.id]
    Row(Modifier.fillMaxWidth().clickable { pick(m.id) }.padding(start = (depth * 14).dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp))
            .background(if (done(m)) Palette.Moss else if (m.deadline == null) Palette.Muted else pFill(now?.p)))
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(m.title ?: m.id, style = MaterialTheme.typography.bodySmall, maxLines = 2, color = typeColor(m.type))
            Hint(listOfNotNull(
                how?.let { tr(it) },
                if (done(m)) tr("done") else null,
                now?.p?.takeIf { m.deadline != null }?.let { "P " + Math.round(it * 100) + "%" },
                now?.likely?.let { tr("likely") + " " + dateText(it) },
                now?.weekHours?.let { tr("this week") + " " + trimNumber(it) + " h" }
            ).joinToString("  ·  "))
        }
    }
}
