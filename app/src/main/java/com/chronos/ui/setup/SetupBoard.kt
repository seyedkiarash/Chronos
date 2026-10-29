package com.chronos.ui.setup

import com.chronos.ui.*
import com.chronos.ui.common.*
import com.chronos.ui.horizon.*
import com.chronos.ui.day.*
import com.chronos.ui.log.*
import com.chronos.ui.navigator.*

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chronos.engine.Slots
import com.chronos.model.Activity
import com.chronos.model.Commitment
import com.chronos.model.Enums
import com.chronos.model.Goal
import com.chronos.validate.Readiness
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/*
 * Setup's goals, goal work, upkeep and commitments on one board.
 *
 * Each goal is a card with its progress and the activities that serve it;
 * upkeep and commitments sit in rows below. Tapping anything opens it in a
 * sheet that walks through it a step at a time; adding something new starts
 * from a kind (a meal, a class, deep study …) whose starting values are shown
 * and can be changed before anything is saved. A week view draws the same
 * things on a timetable, to see what overlaps and where there is room.
 *
 * The sheet edits a copy; nothing reaches the configuration until Save.
 */

/** What the sheet is open on. */
internal sealed class Editing {
    /** A goal as you see it: an umbrella over activities, only its name (your decision of 17 Oct 2026). */
    data class OfGroup(val name: String, val isNew: Boolean) : Editing()
    /** An activity, with its own target when it is goal work (start, deadline, units, or hours a week). */
    data class OfActivity(val activity: Activity, val isNew: Boolean, val target: Goal? = null,
                          val startAt: String? = null) : Editing()
    /** @param startAt the step to open on ("Basics", "Times"), when the timetable already knows what you want */
    data class OfCommitment(val commitment: Commitment, val isNew: Boolean, val startAt: String? = null) : Editing()
}

/** Where the board's issues come from in Readiness. */
internal val BOARD_WHERES = setOf("goals", "activities", "upkeep", "leisure", "commitments")

private val UPKEEP get() = Palette.Moss
private val LEISURE get() = Palette.Plum
private val COMMIT get() = Palette.Ochre

/** A goal's colour, shared by every activity under it. */
/** One goal activity's colour: a shade of its goal's (see [GoalColors]). */
internal fun goalColor(cfg: com.chronos.model.Config, goalId: String?): Color {
    if (cfg.goals.none { it.id == goalId }) return Palette.Muted
    return GoalColors.target(cfg.goals, goalId)
}

internal fun groupColor(cfg: com.chronos.model.Config, group: String?): Color {
    if (cfg.goals.none { (it.groupName ?: it.name) == group }) return Palette.Muted
    return GoalColors.group(cfg.goals, group)
}

/** A new target for a goal activity, under a goal. Its numbers wait for you; it starts with an average weight. */
internal fun newTarget(cfg: com.chronos.model.Config, group: String) = Goal().apply {
    id = java.util.UUID.randomUUID().toString(); name = ""; groupName = group
    mode = Enums.GoalMode.CLOSED; doneUnits = 0.0; hard = true
    weight = cfg.goals.map { it.weight }.average().takeIf { !it.isNaN() } ?: 0.0
}

@Composable
internal fun SetupBoard(vm: ChronosViewModel, issues: List<Readiness.Issue>) {
    vm.observeConfig()
    val cfg = vm.config
    var editing by remember { mutableStateOf<Editing?>(null) }
    // A goal was just named: its first activity, with its own target, opens next.
    LaunchedEffect(vm.pendingNewActivity, editing) {
        val group = vm.pendingNewActivity
        if (group != null && editing == null) {
            vm.pendingNewActivity = null
            val t = newTarget(cfg, group)
            editing = Editing.OfActivity(newActivity(Enums.ActivityKind.GOAL, t.id), true, t)
        }
    }
    // A fix chosen elsewhere (Horizon's "What would fix it"): open what it changes, at the right step.
    LaunchedEffect(vm.pendingFix, editing) {
        val fix = vm.pendingFix ?: return@LaunchedEffect
        if (editing != null) return@LaunchedEffect
        vm.pendingFix = null
        val (kind, id) = fix.split(":", limit = 2).let { it[0] to it.getOrNull(1) }
        when (kind) {
            "when", "effort" -> cfg.activity(id)?.let { a ->
                editing = Editing.OfActivity(a.copy(), false, a.goalId?.let { cfg.goal(it) }?.copy(),
                    if (kind == "when") "When" else "Effort") }
            "target" -> cfg.goal(id)?.let { g ->
                val a = com.chronos.engine.Umbrellas.activityOf(cfg, g)
                if (a != null) editing = Editing.OfActivity(a.copy(), false, g.copy(), "Target") }
        }
    }
    var week by remember { mutableStateOf(false) }
    val flagged = issues.mapNotNull { it.subjectId() }.toSet()

    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Pill(tr("Cards"), !week) { week = false }
        Pill(tr("Week"), week) { week = true }
    }
    SplitReview(vm) { editing = it }
    if (week) WeekBoard(vm) { editing = it }
    else CardsBoard(vm, flagged) { editing = it }

    editing?.let { e -> EditorSheet(vm, e, issues) { editing = null } }
}

// ------------------------------------------------------------------ cards

@Composable
private fun CardsBoard(vm: ChronosViewModel, flagged: Set<String>, open: (Editing) -> Unit) {
    val cfg = vm.config
    SectionTitle("Goals", Palette.Indigo)
    Hint("A goal gathers the activities that serve it; each activity has its own target — a deadline and an amount, or hours a week.")
    com.chronos.engine.Umbrellas.groups(cfg).forEach { (name, targets) -> GroupCard(vm, name, targets, flagged, open) }
    AddTile("New goal", Palette.Indigo) { open(Editing.OfGroup("", true)) }

    val orphans = cfg.activities.filter { !it.nap && it.kind != Enums.ActivityKind.MAINTENANCE &&
        (it.goalId == null || cfg.goal(it.goalId) == null) }
    if (orphans.isNotEmpty()) {
        SectionTitle("Goal work that serves no goal", Palette.Brick)
        ChipRow { orphans.forEach { a -> ItemChip(a.name ?: "?", Palette.Brick, a.id in flagged) {
            open(Editing.OfActivity(a.copy(), false)) } } }
    }

    SectionTitle("Upkeep", UPKEEP)
    Hint("Meals, prayer, exercise, chores: no progress, but they keep you able to work.")
    val today = java.time.LocalDate.now()
    val ended = cfg.activities.filter { it.kind == Enums.ActivityKind.MAINTENANCE && !it.nap && it.endedBy(today) }
    ChipRow {
        cfg.activities.filter { it.kind == Enums.ActivityKind.MAINTENANCE && !it.nap && !it.leisure && !it.endedBy(today) }.forEach { a ->
            ItemChip((a.name ?: "?") + if (a.archived) " · " + tr("archived") else if (a.pausedOn(java.time.LocalDate.now())) " · " + tr("paused") else "", UPKEEP, a.id in flagged,
                a.restOnly || a.archived) { open(Editing.OfActivity(a.copy(), false)) }
        }
        AddChip("Upkeep", UPKEEP) { open(Editing.OfActivity(newActivity(Enums.ActivityKind.MAINTENANCE, null), true)) }
    }
    if (ended.isNotEmpty()) {
        var showEnded by remember { mutableStateOf(false) }
        TextButton(onClick = { showEnded = !showEnded }) {
            Text((if (showEnded) "▴ " else "▾ ") + tr("Periods that are over") + " (" + ended.size + ")")
        }
        if (showEnded) ChipRow {
            ended.forEach { a ->
                ItemChip((a.name ?: "?") + " · " + tr("until") + " " + dateText(a.endDate!!), UPKEEP, a.id in flagged, true) {
                    open(Editing.OfActivity(a.copy(), false)) }
            }
        }
    }

    LeisureSection(vm, flagged, open)

    SectionTitle("Classes and fixed times", COMMIT)
    Hint("Classes and anything else whose time is not yours to choose, and the travel around them.")
    // Your choice of 20 Oct 2026: the week as a university timetable rather than a row of chips.
    ClassTimetable(vm, flagged, open)
}

/**
 * Leisure (your request of 25 Oct 2026): what the rests are chosen from, and the ceiling of
 * stimulation a day may reach. Never planned on its own — picked for free time and for a break
 * inside long work — and how stimulating each is comes from the log's questions about after.
 */
@Composable
private fun LeisureSection(vm: ChronosViewModel, flagged: Set<String>, open: (Editing) -> Unit) {
    val cfg = vm.config
    val today = java.time.LocalDate.now()
    SectionTitle("Leisure", LEISURE)
    Hint("Rests are chosen from these: what used to stay an open buffer, and a short break inside long work.")
    ChipRow {
        cfg.activities.filter { it.leisure && !it.nap && !it.endedBy(today) }.forEach { a ->
            val dop = a.dopamine?.let { " · " + trimNumber(Math.round(it * 100) / 100.0) } ?: ""
            ItemChip((a.name ?: "?") + dop + if (a.archived) " · " + tr("archived") else "", LEISURE, a.id in flagged, a.archived) {
                open(Editing.OfActivity(a.copy(), false)) }
        }
        AddChip("Leisure", LEISURE) {
            open(Editing.OfActivity(newActivity(Enums.ActivityKind.MAINTENANCE, null).apply { leisure = true; dopamine = 0.5 }, true))
        }
    }
    if (cfg.activities.any { it.leisure }) {
        var settings by remember { mutableStateOf(false) }
        TextButton(onClick = { settings = !settings }) {
            Text((if (settings) "▴ " else "▾ ") + tr("Ceiling of stimulation") + " · " +
                trimNumber(cfg.params.dopCapOr()))
        }
        if (settings) {
            LevelSlider("Daily ceiling", cfg.params.dopCapOr(), 0.3..2.0, 0.05, "strict", "loose", LEISURE,
                hint = "The most stimulation a day may reach. No rest is planned that would take it past this.") { v ->
                vm.edit { c -> c.params.dopCap = v } }
            LevelSlider("How fast it fades", cfg.params.dopHalfLifeOr(), 0.5..4.0, 0.25, "½ h", "4 h", LEISURE,
                format = { trimNumber(it) + " h" },
                hint = "Hours for it to halve by itself; goal work uses it up twice as fast.") { v ->
                vm.edit { c -> c.params.dopHalfLifeH = v } }
            LevelSlider("How much it dulls goal work", cfg.params.aDOr(), 0.0..0.4, 0.01, "not at all", "a lot", LEISURE,
                hint = "Capacity taken from goal work per unit of stimulation still there.") { v ->
                vm.edit { c -> c.params.aD = v } }
        }
    }
}

private fun newActivity(kind: Enums.ActivityKind, goalId: String?) = Activity().apply {
    id = java.util.UUID.randomUUID().toString()
    name = ""
    this.kind = kind
    this.goalId = goalId
    blocksMode = Enums.BlocksMode.AUTO
}

@Composable
private fun SectionTitle(text: String, color: Color) {
    Text(tr(text), style = MaterialTheme.typography.titleMedium, color = color,
        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) = FlowRowSimple(content)

@Composable
private fun ItemChip(text: String, color: Color, flagged: Boolean, dashed: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.padding(end = 6.dp, bottom = 6.dp).clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = if (dashed) 0.05f else 0.13f))
            .border(BorderStroke(1.dp, if (flagged) Palette.Brick else color.copy(alpha = 0.4f)), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (flagged) { Box(Modifier.size(7.dp).clip(CircleShape).background(Palette.Brick)); Spacer(Modifier.width(6.dp)) }
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
private fun AddChip(what: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.padding(end = 6.dp, bottom = 6.dp).clip(RoundedCornerShape(14.dp))
            .border(BorderStroke(1.dp, color.copy(alpha = 0.5f)), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp)
    ) { Text("+ " + tr(what), style = MaterialTheme.typography.labelMedium, color = color) }
}

@Composable
private fun AddTile(text: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(10.dp))
            .border(BorderStroke(1.dp, color.copy(alpha = 0.45f)), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick).padding(14.dp),
        contentAlignment = Alignment.Center
    ) { Text("+ " + tr(text), color = color, style = MaterialTheme.typography.labelLarge) }
}

/** A goal: its name and, for each activity serving it, that activity's own target and progress. */
@Composable
private fun GroupCard(vm: ChronosViewModel, name: String, targets: List<Goal>, flagged: Set<String>, open: (Editing) -> Unit) {
    val cfg = vm.config
    val color = groupColor(cfg, name)
    val today = LocalDate.now()
    val note = targets.firstNotNullOfOrNull { it.note }
    Card(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.06f)),
        border = BorderStroke(1.dp, if (targets.any { it.id in flagged }) Palette.Brick else color.copy(alpha = 0.4f)),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth().clickable { open(Editing.OfGroup(name, false)) }, verticalAlignment = Alignment.CenterVertically) {
                Text(name.ifBlank { tr("Unnamed goal") }, style = MaterialTheme.typography.titleSmall, color = color,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("✎", color = Palette.Muted)
            }
            if (note != null && targets.any { it.id in flagged }) Hint(note)
            targets.forEach { t ->
                val a = com.chronos.engine.Umbrellas.activityOf(cfg, t)
                val closed = t.mode == Enums.GoalMode.CLOSED
                val fraction = if (closed && (t.totalUnits ?: 0.0) > 0) ((t.doneUnits ?: 0.0) / t.totalUnits!!).toFloat() else null
                Row(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(8.dp)).clickable {
                    open(Editing.OfActivity(a?.copy() ?: newActivity(Enums.ActivityKind.GOAL, t.id).also { it.name = t.name }, a == null, t.copy()))
                }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    ProgressRing(fraction, goalColor(cfg, t.id), Modifier.size(34.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text((a?.name ?: t.name ?: "?") +
                            (if (a?.archived == true) " · " + tr("archived") else if (a?.pausedOn(today) == true) " · " + tr("paused") else ""),
                            style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val sub = if (a == null) tr("no activity yet") else if (closed) {
                            val left = t.deadline?.let { ChronoUnit.DAYS.between(today, it) }
                            trimNumber(t.doneUnits ?: 0.0) + " / " + trimNumber(t.totalUnits ?: 0.0) + " " + (t.unit ?: "") +
                                (t.deadline?.let { "  ·  " + dateText(it) } ?: "") +
                                (left?.takeIf { it >= 0 }?.let { "  ·  $it " + tr("days left") } ?: "")
                        } else (t.weeklyFloorH?.let { trimNumber(it) + " " + tr("h per week") } ?: tr("Ongoing"))
                        Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (t.id in flagged || a?.id in flagged) Box(Modifier.size(9.dp).clip(CircleShape).background(Palette.Brick))
                }
            }
            AddChip("Activity", color) {
                val t = newTarget(cfg, name)
                open(Editing.OfActivity(newActivity(Enums.ActivityKind.GOAL, t.id), true, t))
            }
        }
    }
}

@Composable
private fun ProgressRing(fraction: Float?, color: Color, modifier: Modifier) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val s = 5.dp.toPx()
            val box = Size(size.width - s, size.height - s)
            drawArc(track, -90f, 360f, false, Offset(s / 2, s / 2), box, style = Stroke(s))
            if (fraction == null)
                drawArc(color, -90f, 360f, false, Offset(s / 2, s / 2), box,
                    style = Stroke(s, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))))
            else drawArc(color, -90f, 360f * fraction.coerceIn(0f, 1f), false, Offset(s / 2, s / 2), box,
                style = Stroke(s, cap = StrokeCap.Round))
        }
        Text(if (fraction == null) "∞" else "${Math.round(fraction * 100)}%", style = MaterialTheme.typography.labelSmall,
            color = color, fontWeight = FontWeight.Bold)
    }
}

// ------------------------------------------------------------------- week

/** Saturday to Friday in a letter or two, as Persian calendars print them. */
private val FA_SHORT_DOW = arrayOf("ش", "۱ش", "۲ش", "۳ش", "۴ش", "۵ش", "ج")

/**
 * The week, two ways (your request of 23 Oct 2026: names on the boxes, not in a legend).
 * Planned: the coming seven days as the planner arranged them — blocks never overlap, so each
 * carries its name. Allowed: one weekday at a time, each activity a row with the hours it may use
 * as a bar, so overlapping windows read as rows instead of a tangle of lanes.
 */
@Composable
private fun WeekBoard(vm: ChronosViewModel, open: (Editing) -> Unit) {
    var allowed by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Pill(tr("Planned week"), !allowed) { allowed = false }
        Spacer(Modifier.width(6.dp))
        Pill(tr("Allowed hours"), allowed) { allowed = true }
    }
    if (allowed) AllowedHours(vm, open) else PlannedWeek(vm, open)
}

private fun wakeBed(p: com.chronos.model.Params): Pair<Int, Int> {
    val wake = p.sleepEnd?.let { Slots.parse(it) * Slots.SLOT_MIN } ?: 6 * 60
    val bed = p.sleepStart?.let { Slots.parse(it) * Slots.SLOT_MIN }?.let { if (it <= wake) it + 1440 else it } ?: 24 * 60
    return wake to bed
}

@Composable
private fun PlannedWeek(vm: ChronosViewModel, open: (Editing) -> Unit) {
    val cfg = vm.config
    val lang = LocalLang.current
    if (vm.bundle == null) { Hint("No plan yet: build one on the Horizon tab and the coming week shows here."); return }
    val days = (0..6).map { vm.today.plusDays(it.toLong()) }
    LaunchedEffect(vm.bundle, vm.revision) { days.forEach { vm.requestDay(it) } }
    val (wake, bed) = wakeBed(cfg.params)
    val columns = days.map { d ->
        val blocks = vm.dayResult(d)?.blocks().orEmpty().filter { !it.gap && it.start >= 0 }
        blocks.map { b ->
            val a = if (b.fixed) null else cfg.activities.firstOrNull { it.name == b.name && (b.goalId == null || it.goalId == b.goalId) }
            val cls = if (b.fixed) cfg.commitments.firstOrNull { it.kind == Enums.CommitmentKind.FIXED && it.name == b.name } else null
            val color = when {
                a != null && a.leisure -> LEISURE
                a != null && a.kind == Enums.ActivityKind.MAINTENANCE -> UPKEEP
                a != null -> goalColor(cfg, a.goalId)
                cls != null -> classColor(cfg, cls.id)
                else -> Palette.Muted
            }
            var from = b.start * Slots.SLOT_MIN
            if (from < wake - 120) from += 1440        // past midnight: drawn after the evening
            TimeItem(from, from + b.dur * Slots.SLOT_MIN, (b.name ?: "?").trim(), color, a != null || cls != null) {
                when {
                    a != null -> open(Editing.OfActivity(a.copy(), false))
                    cls != null -> open(Editing.OfCommitment(cls.copy(), false, "Times"))
                }
            }
        }
    }
    Hint("The coming seven days as they are arranged. Tap a block to open what it belongs to.")
    TimeColumns(days.map { d -> (if (lang.rtl) FA_SHORT_DOW[Slots.dow(d)] else Slots.DOW[Slots.dow(d)]) + " " + d.dayOfMonth },
        columns, wake, bed, hourHeight = 34.dp)
}

@Composable
private fun AllowedHours(vm: ChronosViewModel, open: (Editing) -> Unit) {
    val cfg = vm.config
    val lang = LocalLang.current
    val today = LocalDate.now()
    var dow by remember { mutableIntStateOf(Slots.dow(today)) }
    val (wake, bed) = wakeBed(cfg.params)
    fun min(t: String?) = t?.let { Slots.parse(it) * Slots.SLOT_MIN }
    FlowRowSimple {
        (0..6).forEach { d ->
            Box(Modifier.padding(end = 4.dp, bottom = 4.dp)) {
                Pill(if (lang.rtl) FA_SHORT_DOW[d] else Slots.DOW[d], dow == d) { dow = d }
            }
        }
    }
    Hint("Each activity on its own row: the pale bar is where it may be placed that day, a solid one a fixed time. Tap a row to open it.")
    data class RowOf(val name: String, val color: Color, val spans: List<Pair<Int, Int>>, val solid: Boolean, val open: Editing)
    val rows = buildList {
        cfg.commitments.filter { it.kind == Enums.CommitmentKind.FIXED }.forEach { c ->
            val spans = c.sessions.filter { it.day == dow }.mapNotNull { s ->
                val a = min(s.start) ?: return@mapNotNull null; val b = min(s.end) ?: return@mapNotNull null
                if (b > a) a to b else null }
            if (spans.isNotEmpty()) add(RowOf(c.name ?: "?", classColor(cfg, c.id), spans, true, Editing.OfCommitment(c.copy(), false, "Times")))
        }
        vm.activitiesOn(today).filter { !it.nap && dow in it.days }.forEach { a ->
            val color = if (a.leisure) LEISURE else if (a.kind == Enums.ActivityKind.MAINTENANCE) UPKEEP else goalColor(cfg, a.goalId)
            val pins = a.pins.filter { it.day == dow }
            val spans = if (pins.isNotEmpty()) pins.mapNotNull { pin -> min(pin.start)?.let { it to it + (a.minBlock ?: 30) } }
                else (if (a.windowsByDay()) a.windowsOn(dow)
                    else a.windows.ifEmpty { listOf(Activity.Window(Slots.formatMinutes(wake), Slots.formatMinutes(bed))) })
                    .mapNotNull { w -> val s0 = min(w.from) ?: return@mapNotNull null
                        var t = min(w.to) ?: return@mapNotNull null; if (t == 0) t = 1440; if (t > s0) s0 to t else null }
            if (spans.isNotEmpty()) add(RowOf((a.name ?: "?").trim(), color, spans, pins.isNotEmpty(), Editing.OfActivity(a.copy(), false)))
        }
    }
    val track = MaterialTheme.colorScheme.surfaceVariant
    val span = (bed - wake).coerceAtLeast(60).toFloat()
    Ltr {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(start = 112.dp)) {
                BoxWithConstraints(Modifier.fillMaxWidth().height(14.dp)) {
                    val w = maxWidth
                    var h = (wake + 59) / 60 * 60
                    while (h < bed) {
                        if ((h / 60) % 3 == 0) Text("%02d".format((h / 60) % 24), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.offset(x = w * ((h - wake) / span) - 6.dp))
                        h += 60
                    }
                }
            }
            rows.forEach { r ->
                Row(Modifier.fillMaxWidth().clickable { open(r.open) }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(r.name, style = MaterialTheme.typography.labelSmall, color = r.color, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.width(108.dp).padding(end = 4.dp))
                    BoxWithConstraints(Modifier.weight(1f).height(16.dp).clip(RoundedCornerShape(4.dp)).background(track)) {
                        val w = maxWidth
                        r.spans.forEach { (a, b) ->
                            val x0 = ((a - wake) / span).coerceIn(0f, 1f); val x1 = ((b - wake) / span).coerceIn(0f, 1f)
                            if (x1 > x0) Box(Modifier.offset(x = w * x0).width(w * (x1 - x0)).fillMaxHeight()
                                .clip(RoundedCornerShape(4.dp)).background(if (r.solid) r.color else r.color.copy(alpha = 0.45f)))
                        }
                    }
                }
            }
        }
    }
    if (rows.isEmpty()) Hint("Nothing is allowed on this day.")
}

/**
 * Activities whose physical/mental split the app suggested when fatigue got
 * its two kinds. Each can be opened and changed, or all kept at once.
 */
@Composable
private fun SplitReview(vm: ChronosViewModel, open: (Editing) -> Unit) {
    val pending = vm.config.activities.filter { !it.splitConfirmed && it.physLoad != null }
    if (pending.isEmpty()) return
    Panel("Mental and physical fatigue", Palette.Sand) {
        Hint("Fatigue now has two kinds. For each activity the app suggested how much it tires (and rests) your " +
             "mind and your body, from what it is. Until you confirm, the planner uses the suggestion. Tap one to change it.")
        pending.forEach { a ->
            Row(Modifier.fillMaxWidth().clickable { open(Editing.OfActivity(a.copy(), false)) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(a.name ?: "", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Text(tr("mind") + " " + trimNumber(a.load ?: 0.0) + "/" + trimNumber(a.recovery ?: 0.0) + "  ·  " +
                     tr("body") + " " + trimNumber(a.physLoad ?: 0.0) + "/" + trimNumber(a.physRecovery ?: 0.0),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Hint("load / recovery per hour")
        Button(onClick = { vm.edit { c -> c.activities.forEach { it.splitConfirmed = true } } }) { Text("Keep all as suggested") }
    }
}
