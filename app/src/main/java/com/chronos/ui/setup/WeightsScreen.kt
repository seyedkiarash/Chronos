package com.chronos.ui.setup

import com.chronos.ui.*
import com.chronos.ui.common.*
import com.chronos.ui.horizon.*
import com.chronos.ui.day.*
import com.chronos.ui.log.*
import com.chronos.ui.navigator.*

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.chronos.engine.Priorities
import com.chronos.model.Enums
import com.chronos.model.Goal

/*
 * Priorities as levels and an order (your choice of 26 Oct 2026) instead of comparing every pair:
 * four levels, one list. Hold a row's handle and drag it — within its level, or past a level's
 * heading into another. A new target is placed by the app with its reasons, marked until you keep it.
 */

internal val TIER_COLORS get() = listOf(Palette.Brick, Palette.Sand, Palette.Steel, Palette.Muted)

/** The levels as answers to "if this is not done in time, how bad is it?" */
internal val TIER_ANSWERS = listOf("A disaster", "Serious", "A nuisance", "It hardly matters")

/** What each level means, said as what missing it would cost. */
internal val TIER_MEANING = listOf(
    "Missing it would be a disaster", "Missing it would be serious", "Missing it would be a nuisance", "It hardly matters if it slips")

private sealed interface Entry { val key: String }
private data class Head(val tier: Int) : Entry { override val key = "head$tier" }
private data class Item(val id: String) : Entry { override val key = id }

@Composable
fun WeightsScreen(vm: ChronosViewModel) {
    vm.observeConfig()
    val cfg = vm.config
    val today = java.time.LocalDate.now()
    val playing = cfg.goals.filter { vm.outOfPlay(it, today) == null }
    val resting = cfg.goals.mapNotNull { g -> vm.outOfPlay(g, today)?.let { g to it } }
    fun label(g: Goal) = (g.name ?: "?") + (g.groupName?.takeIf { it != g.name }?.let { "  ·  $it" } ?: "")

    ScreenHeader("Priorities",
        "Four levels, one order. The higher a target sits, the more of your best hours it gets when there are not enough for everything.")

    if (playing.isEmpty()) {
        Verdict(Tone.Warn, "Nothing to put in order", "Add goal activities in Setup and they will appear here.")
        RestingPanel(resting)
        return
    }

    val auto = playing.filter { it.tierAuto }
    if (auto.isNotEmpty()) Panel(null, Palette.Sand) {
        Text(tr("Chosen by the app: {}").replace("{}", localiseDigits(vm.lang, auto.size.toString())),
            style = MaterialTheme.typography.titleSmall, color = Palette.Sand)
        Hint("Each is marked below with why. Drag it, change its level, or keep what was chosen.")
        Button(onClick = { vm.confirmPriorities(auto.map { it.id }) }) { Text("Keep them all") }
    }

    // The list as it is drawn: a heading per level, its targets in order under it.
    val rows = remember(vm.revision) {
        mutableStateListOf<Entry>().apply {
            for (t in 0..3) {
                add(Head(t))
                Priorities.level(cfg, t).filter { g -> playing.any { it.id == g.id } }.forEach { add(Item(it.id)) }
            }
        }
    }
    val tops = remember { mutableStateMapOf<String, Float>() }
    val heights = remember { mutableStateMapOf<String, Float>() }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dy by remember { mutableFloatStateOf(0f) }
    var open by remember { mutableStateOf<String?>(null) }

    fun commit(touched: String) {
        var tier = 0
        val order = mutableListOf<Pair<String, Int>>()
        rows.forEach { e -> when (e) { is Head -> tier = e.tier; is Item -> order += e.id to tier } }
        vm.reorderPriorities(order, listOf(touched))
    }

    /** Moves a row one place up or down (a heading it passes changes its level). */
    fun step(id: String, down: Boolean): Boolean {
        val i = rows.indexOfFirst { it.key == id }
        val j = if (down) i + 1 else i - 1
        if (j < 1 || j >= rows.size) return false       // nothing goes above the first heading
        val other = rows[j]
        rows[j] = rows[i]; rows[i] = other
        val h = heights[id] ?: 0f
        val oh = heights[other.key] ?: 0f
        if (down) { tops[other.key] = (tops[other.key] ?: 0f) - h; tops[id] = (tops[id] ?: 0f) + oh }
        else { tops[other.key] = (tops[other.key] ?: 0f) + h; tops[id] = (tops[id] ?: 0f) - oh }
        return true
    }

    Panel {
        Hint("Hold ⠿ and drag. A target dragged past a heading moves to that level.")
        Column(Modifier.fillMaxWidth()) {
            rows.forEach { e ->
                key(e.key) {
                    val lifted = dragging == e.key
                    Box(Modifier.fillMaxWidth()
                        .onGloballyPositioned { c -> if (!lifted) { tops[e.key] = c.positionInParent().y; heights[e.key] = c.size.height.toFloat() } }
                        .zIndex(if (lifted) 1f else 0f)
                        .graphicsLayer { translationY = if (lifted) dy else 0f }) {
                        when (e) {
                            is Head -> LevelHeading(e.tier, rows)
                            is Item -> cfg.goal(e.id)?.let { g ->
                                TargetRow(vm, g, label(g), lifted, open == g.id,
                                    onTap = { open = if (open == g.id) null else g.id },
                                    handle = Modifier.pointerInput(g.id) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = { dragging = g.id; dy = 0f },
                                            onDrag = { change, amount ->
                                                change.consume()
                                                dy += amount.y
                                                val mid = (tops[g.id] ?: 0f) + dy + (heights[g.id] ?: 0f) / 2
                                                val i = rows.indexOfFirst { it.key == g.id }
                                                rows.getOrNull(i + 1)?.let { n ->
                                                    val nh = heights[n.key] ?: 0f
                                                    if (mid > (tops[n.key] ?: 0f) + nh / 2 && step(g.id, true)) dy -= nh
                                                }
                                                rows.getOrNull(i - 1)?.takeIf { i - 1 >= 1 }?.let { p ->
                                                    val ph = heights[p.key] ?: 0f
                                                    if (mid < (tops[p.key] ?: 0f) + ph / 2 && step(g.id, false)) dy += ph
                                                }
                                            },
                                            onDragEnd = { dragging = null; dy = 0f; commit(g.id) },
                                            onDragCancel = { dragging = null; dy = 0f; commit(g.id) })
                                    },
                                    move = { down -> if (step(g.id, down)) commit(g.id) })
                            }
                        }
                    }
                }
            }
        }
    }

    RestingPanel(resting)
}

@Composable
private fun LevelHeading(tier: Int, rows: List<Entry>) {
    val i = rows.indexOfFirst { it is Head && it.tier == tier }
    val empty = rows.getOrNull(i + 1).let { it == null || it is Head }
    Column(Modifier.fillMaxWidth().padding(top = if (tier == 0) 2.dp else 14.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(TIER_COLORS[tier]))
            Spacer(Modifier.width(8.dp))
            Text(tr(Priorities.NAMES[tier]), style = MaterialTheme.typography.titleSmall, color = TIER_COLORS[tier])
            Spacer(Modifier.width(8.dp))
            Text(tr(TIER_MEANING[tier]), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (empty) Hint("Nothing here. Drag a target in, or leave it empty.")
        HorizontalDivider(Modifier.padding(top = 4.dp), color = TIER_COLORS[tier].copy(alpha = 0.35f))
    }
}

@Composable
private fun TargetRow(vm: ChronosViewModel, g: Goal, label: String, lifted: Boolean, expanded: Boolean,
                      onTap: () -> Unit, handle: Modifier, move: (Boolean) -> Unit) {
    val cfg = vm.config
    val lang = LocalLang.current
    val color = GoalColors.target(cfg.goals, g.id)
    val surface = if (lifted) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(surface)) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onTap).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("⠿", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = handle.padding(horizontal = 8.dp, vertical = 4.dp))
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                val due = if (g.mode == Enums.GoalMode.CLOSED) g.deadline?.let { tr("due") + " " + dateText(it) }
                          else g.weeklyFloorH?.let { trimNumber(it) + " " + tr("h per week") }
                due?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (g.tierAuto) Text(tr("chosen by the app"), style = MaterialTheme.typography.labelSmall, color = Palette.Sand)
            }
            Text(localiseDigits(vm.lang, Math.round(g.weight * 100).toString() + "%"), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 6.dp))
        }
        if (expanded) Column(Modifier.padding(start = 34.dp, bottom = 8.dp)) {
            if (g.tierAuto) {
                val s = Priorities.suggest(cfg, g, java.time.LocalDate.now())
                Hint(tr("Why the app put it here") + ": " + s.why().joinToString("، ") { translate(lang, it) })
            }
            FlowRowSimple {
                (0..3).forEach { t -> Pill(tr(Priorities.NAMES[t]), g.tier == t) { vm.setTier(g.id, t) } }
            }
            Row {
                TextButton(onClick = { move(false) }) { Text("▲ " + tr("Up")) }
                TextButton(onClick = { move(true) }) { Text("▼ " + tr("Down")) }
                if (g.tierAuto) TextButton(onClick = { vm.confirmPriorities(listOf(g.id)) }) { Text("Keep it here") }
            }
        }
    }
}

/** Targets with no place in the order now, each with why; folded until tapped. */
@Composable
private fun RestingPanel(resting: List<Pair<Goal, String>>) {
    if (resting.isEmpty()) return
    var open by rememberSaveable { mutableStateOf(false) }
    Panel("Not in the order now") {
        Hint("These no longer need a priority. Move a deadline later or raise an amount in Setup and the activity comes back with its level.")
        TextButton(onClick = { open = !open }) { Text(if (open) tr("Hide") else tr("Show") + " (" + resting.size + ")") }
        if (open) resting.forEach { (g, why) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text((g.name ?: "?") + (g.groupName?.takeIf { it != g.name }?.let { "  ·  $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Text(tr(why), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
