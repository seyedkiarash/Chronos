package com.chronos.ui.setup

import com.chronos.ui.*
import com.chronos.ui.common.*
import com.chronos.ui.horizon.*
import com.chronos.ui.day.*
import com.chronos.ui.log.*
import com.chronos.ui.navigator.*

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.chronos.engine.Slots
import com.chronos.model.Activity
import com.chronos.model.Commitment
import com.chronos.model.Enums
import com.chronos.model.Goal
import com.chronos.validate.Readiness

/*
 * The sheet that edits one goal, activity or commitment a step at a time.
 * It works on a copy; Save writes it back in one edit, Cancel leaves the
 * configuration as it was.
 */

// ------------------------------------------------------------- templates

/**
 * A starting point for a new activity. Every value it fills is shown on the
 * next steps and can be changed before saving; "Start blank" fills nothing.
 */
private data class Template(
    val label: String, val icon: String, val name: String,
    val minBlock: Int? = null, val maxBlock: Int? = null, val load: Double? = null, val recovery: Double? = null,
    val windows: List<Pair<String, String>> = emptyList(), val days: Set<Int> = emptySet(),
    val changeover: Int? = null, val highFocus: Boolean = false, val away: Boolean = false, val sessions: Int? = null,
    val physLoad: Double? = null, val physRecovery: Double? = null,
    /** Leisure only: a first guess at how stimulating an hour of it is (learned from the log after). */
    val dopamine: Double? = null
)

private val ALL_DAYS = (0..6).toSet()

private val GOAL_TEMPLATES = listOf(
    Template("Deep study", "◆", "Study", 45, 120, 0.7, 0.0, listOf("08:00" to "12:30", "16:00" to "20:00"), ALL_DAYS, highFocus = true, physLoad = 0.05, physRecovery = 0.0),
    Template("Lectures or videos", "▶", "Lectures", 30, 120, 0.6, 0.0, listOf("08:00" to "21:00"), ALL_DAYS, highFocus = true, physLoad = 0.05, physRecovery = 0.0),
    Template("Language practice", "✎", "Language practice", 20, 60, 0.4, 0.0, listOf("07:00" to "22:00"), ALL_DAYS, physLoad = 0.03, physRecovery = 0.0),
    Template("Coding or a project", "⌘", "Project", 60, 150, 0.7, 0.0, listOf("09:00" to "13:00", "15:00" to "20:00"), ALL_DAYS, highFocus = true, physLoad = 0.05, physRecovery = 0.0),
    Template("Writing", "¶", "Writing", 45, 120, 0.7, 0.0, listOf("08:00" to "13:00"), ALL_DAYS, highFocus = true, physLoad = 0.05, physRecovery = 0.0),
    Template("Planning and admin", "☰", "Planning", 15, 60, 0.3, 0.0, listOf("08:00" to "22:00"), ALL_DAYS, physLoad = 0.03, physRecovery = 0.0),
)

private val UPKEEP_TEMPLATES = listOf(
    Template("Meal", "◔", "Meal", 20, 45, 0.0, 0.4, listOf("12:00" to "14:30"), ALL_DAYS, changeover = 5, sessions = 1, physLoad = 0.0, physRecovery = 0.3),
    Template("Prayer", "◌", "Prayer", 10, 20, 0.0, 0.2, listOf("05:00" to "22:00"), ALL_DAYS, sessions = 1, physLoad = 0.0, physRecovery = 0.1),
    Template("Exercise", "△", "Exercise", 45, 90, 0.05, 0.3, listOf("16:00" to "20:00"), setOf(0, 4), changeover = 15, sessions = 1, physLoad = 0.6, physRecovery = 0.0),
    Template("Chores", "□", "Chores", 15, 45, 0.3, 0.1, listOf("09:00" to "21:00"), ALL_DAYS, sessions = 1, physLoad = 0.3, physRecovery = 0.0),
    Template("Walk or break", "○", "Walk", 15, 30, 0.0, 0.5, listOf("10:00" to "20:00"), ALL_DAYS, sessions = 1, physLoad = 0.15, physRecovery = 0.0),
)

/** Leisure (25 Oct 2026): first guesses at how stimulating each kind is, on the scale where a day's ceiling is 1. */
private val LEISURE_TEMPLATES = listOf(
    Template("Film or series", "▭", "Film", 30, 120, 0.0, 0.4, listOf("18:00" to "23:00"), ALL_DAYS, sessions = 1, physLoad = 0.0, physRecovery = 0.2, dopamine = 0.5),
    Template("Games", "◇", "Games", 20, 90, 0.1, 0.3, listOf("16:00" to "23:00"), ALL_DAYS, sessions = 1, physLoad = 0.0, physRecovery = 0.1, dopamine = 1.0),
    Template("Social media or short videos", "≋", "Social media", 10, 30, 0.1, 0.2, listOf("09:00" to "23:00"), ALL_DAYS, sessions = 2, physLoad = 0.0, physRecovery = 0.1, dopamine = 1.4),
    Template("Music or a hobby", "♪", "Music", 15, 60, 0.0, 0.4, listOf("09:00" to "22:00"), ALL_DAYS, sessions = 1, physLoad = 0.0, physRecovery = 0.2, dopamine = 0.35),
    Template("Walk outside", "○", "Walk outside", 15, 45, 0.0, 0.5, listOf("07:00" to "20:00"), ALL_DAYS, sessions = 1, physLoad = 0.15, physRecovery = 0.0, dopamine = 0.15),
    Template("Friends or family", "◎", "Friends", 30, 120, 0.05, 0.5, listOf("16:00" to "23:00"), ALL_DAYS, sessions = 1, physLoad = 0.0, physRecovery = 0.2, dopamine = 0.4),
)

private val TEMPLATE_NAMES by lazy { (GOAL_TEMPLATES + UPKEEP_TEMPLATES + LEISURE_TEMPLATES).map { it.name } }

private fun Activity.apply(t: Template, lang: Lang) {
    // The name is the user's from here on, so it starts in their language.
    if (name.isNullOrBlank() || TEMPLATE_NAMES.any { translate(lang, it) == name }) name = translate(lang, t.name)
    minBlock = t.minBlock; maxBlock = t.maxBlock; load = t.load; recovery = t.recovery
    physLoad = t.physLoad; physRecovery = t.physRecovery; splitConfirmed = true
    windows.clear(); t.windows.forEach { windows.add(Activity.Window(it.first, it.second)) }
    days.clear(); days.addAll(t.days)
    changeoverMin = t.changeover
    needsHighEnergy = t.highFocus; away = t.away
    if (t.sessions != null) { blocksMode = Enums.BlocksMode.FIXED; maxBlocks = t.sessions }
    if (t.dopamine != null) dopamine = t.dopamine
}

// ---------------------------------------------------------------- sheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditorSheet(vm: ChronosViewModel, e: Editing, issues: List<Readiness.Issue>, close: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val lang = LocalLang.current
    val direction = androidx.compose.ui.platform.LocalLayoutDirection.current
    ModalBottomSheet(onDismissRequest = close, sheetState = state) {
        // The sheet is its own window; the language and the reading direction are handed on explicitly.
        CompositionLocalProvider(LocalLang provides lang,
            androidx.compose.ui.platform.LocalLayoutDirection provides direction) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)
            .padding(bottom = 28.dp)) {
            when (e) {
                is Editing.OfGroup -> GroupEditor(vm, e, close)
                is Editing.OfActivity -> ActivityEditor(vm, e, issues, close)
                is Editing.OfCommitment -> CommitmentEditor(vm, e, issues, close)
            }
        }
        }
    }
}

/** Step dots, the step's name, and what the saved item still lacks. */
@Composable
private fun StepHeader(title: String, steps: List<String>, step: Int, color: Color, itemIssues: List<Readiness.Issue>) {
    Text(title, style = MaterialTheme.typography.titleLarge, color = color)
    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        steps.forEachIndexed { i, _ ->
            Box(Modifier.padding(end = 6.dp).size(if (i == step) 22.dp else 8.dp, 8.dp).clip(RoundedCornerShape(4.dp))
                .background(if (i <= step) color else color.copy(alpha = 0.2f)))
        }
        Spacer(Modifier.width(6.dp))
        Text(tr("Step") + " ${step + 1}/${steps.size} · " + tr(steps[step]), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    itemIssues.forEach { Text("· " + it.what(), style = MaterialTheme.typography.labelSmall, color = Palette.Brick) }
}

/** Back, next, and on the last step (or at any step of something already saved) save. */
@Composable
private fun StepFooter(step: Int, last: Int, isNew: Boolean, canSave: Boolean, color: Color,
                       onStep: (Int) -> Unit, onSave: () -> Unit, onCancel: () -> Unit, onRemove: (() -> Unit)?) {
    Spacer(Modifier.height(14.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (step > 0) OutlinedButton(onClick = { onStep(step - 1) }) { Text("Back") }
        else OutlinedButton(onClick = onCancel) { Text("Cancel") }
        Spacer(Modifier.weight(1f))
        if (step < last) {
            if (!isNew) TextButton(onClick = onSave, enabled = canSave) { Text("Save") }
            Button(onClick = { onStep(step + 1) }, colors = ButtonDefaults.buttonColors(containerColor = color)) { Text("Next") }
        } else Button(onClick = onSave, enabled = canSave, colors = ButtonDefaults.buttonColors(containerColor = color)) {
            Text("Save")
        }
    }
    if (onRemove != null) {
        var sure by remember { mutableStateOf(false) }
        TextButton(onClick = { if (sure) onRemove() else sure = true }) {
            Text(if (sure) tr("Tap again to remove") else tr("Remove"), color = Palette.Brick)
        }
    }
}

@Composable
private fun KindTile(label: String, icon: String, selected: Boolean, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.padding(4.dp).clip(RoundedCornerShape(10.dp))
            .background(if (selected) color.copy(alpha = 0.15f) else Color.Transparent)
            .border(BorderStroke(if (selected) 2.dp else 1.dp, if (selected) color else color.copy(alpha = 0.35f)), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(icon, style = MaterialTheme.typography.titleLarge, color = color)
        Text(tr(label), style = MaterialTheme.typography.labelMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

// --------------------------------------------------------------- inputs

private fun minutesOf(t: String?): Int? = t?.let { val p = it.split(":"); p[0].toInt() * 60 + p[1].toInt() }
private fun clock(m: Int) = "%02d:%02d".format((m / 60) % 24, m % 60)

/**
 * A span of the day on one bar, both ends dragged. Ending at 24:00 is stored
 * as 00:00, the planner's midnight.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimeRangeBar(from: String?, to: String?, color: Color, onChange: (String, String) -> Unit) {
    val a = minutesOf(from) ?: 8 * 60
    val b = (minutesOf(to) ?: 12 * 60).let { if (it == 0) 1440 else it }
    var range by remember(from, to) { mutableStateOf(a.toFloat()..maxOf(a, b).toFloat()) }
    Column {
        Ltr {
            Text(clock(range.start.toInt()) + "  –  " + (if (range.endInclusive.toInt() >= 1440) "24:00" else clock(range.endInclusive.toInt())),
                style = MaterialTheme.typography.titleMedium, color = color)
        }
        Ltr {
            RangeSlider(
                value = range, valueRange = 0f..1440f, steps = 1440 / 15 - 1,
                onValueChange = { range = it },
                onValueChangeFinished = { onChange(clock(range.start.toInt()), clock(range.endInclusive.toInt() % 1440)) },
                colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = color)
            )
        }
    }
}

/** A number on a slider with the ends named in words, or "not set" until touched. */
@Composable
internal fun LevelSlider(label: String, value: Double?, range: ClosedFloatingPointRange<Double>, step: Double,
                         low: String, high: String, color: Color, format: (Double) -> String = { trimNumber(it) },
                         hint: String? = null, onChange: (Double) -> Unit) {
    var v by remember(value) { mutableStateOf((value ?: (range.start + range.endInclusive) / 2).toFloat()) }
    Column(Modifier.padding(vertical = 6.dp)) {
        Row {
            Text(tr(label), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            Text(if (value == null) tr("Not set") else format(v.toDouble()), style = MaterialTheme.typography.labelMedium,
                color = if (value == null) Palette.Brick else color)
        }
        Ltr {
            Slider(value = v, onValueChange = { v = it },
                onValueChangeFinished = { onChange(Math.round(v / step) * step) },
                valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
                steps = ((range.endInclusive - range.start) / step).toInt() - 1,
                colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = color))
        }
        Row {
            Text(tr(low), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(tr(high), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (hint != null) Hint(hint)
    }
}

/**
 * The weekdays a window holds on, among the days the activity happens: "Every day" (null), or
 * some of them. A day tapped while it is every day keeps that day alone.
 */
@Composable
private fun WindowDays(chosen: Set<Int>?, days: Set<Int>, lang: Lang, onChange: (java.util.TreeSet<Int>?) -> Unit) {
    FlowRowSimple {
        Pill(tr("Every day"), chosen == null) { onChange(null) }
        days.sorted().forEach { d ->
            Pill(weekday(lang, d), chosen != null && d in chosen) {
                val next = when {
                    chosen == null -> setOf(d)
                    d in chosen -> chosen - d
                    else -> chosen + d
                }
                onChange(if (next.isEmpty() || next.containsAll(days)) null else java.util.TreeSet(next))
            }
        }
    }
}

/** Whether a target has what the planner needs from you. */
private fun Goal.filled(): Boolean = hoursPerUnit != null && maxPerDayH != null &&
    (if (mode == Enums.GoalMode.OPEN) weeklyFloorH != null else totalUnits != null && unit != null && deadline != null)

/** Shortest and longest useful block on one bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BlockLengthBar(min: Int?, max: Int?, color: Color, onChange: (Int, Int) -> Unit) {
    var r by remember(min, max) { mutableStateOf((min ?: 30).toFloat()..(max ?: 90).toFloat()) }
    Column(Modifier.padding(vertical = 6.dp)) {
        Row {
            Text(tr("Block length"), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            Text(if (min == null || max == null) tr("Not set") else "${r.start.toInt()}–${r.endInclusive.toInt()} " + tr("min"),
                style = MaterialTheme.typography.labelMedium, color = if (min == null || max == null) Palette.Brick else color)
        }
        Ltr {
            RangeSlider(value = r, onValueChange = { r = it }, valueRange = 5f..240f, steps = (240 - 5) / 5 - 1,
                onValueChangeFinished = { onChange(r.start.toInt(), r.endInclusive.toInt()) },
                colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = color))
        }
        Hint("Shortest: below this a session is not worth starting. Longest: past this, attention fades faster than work gets done.")
    }
}

// ----------------------------------------------------------------- goal

/** A goal: only its name now; its activities carry the targets. */
@Composable
private fun GroupEditor(vm: ChronosViewModel, e: Editing.OfGroup, close: () -> Unit) {
    var name by remember { mutableStateOf(e.name) }
    val color = if (e.isNew) Palette.Indigo else groupColor(vm.config, e.name)
    Text(if (e.isNew) tr("New goal") else e.name, style = MaterialTheme.typography.titleMedium, color = color,
        modifier = Modifier.padding(vertical = 8.dp))
    TextField("Name", name) { v -> name = v }
    Hint("A goal gathers the activities that serve it. Each activity has its own target: an amount by a date, or hours a week.")
    val taken = name.isNotBlank() && name.trim() != e.name &&
        com.chronos.engine.Umbrellas.groups(vm.config).containsKey(name.trim())
    if (taken) Text(tr("There is already a goal with this name."), style = MaterialTheme.typography.labelSmall, color = Palette.Brick)
    var confirm by remember { mutableStateOf(false) }
    Row(Modifier.padding(top = 12.dp)) {
        Button(onClick = {
            val n = name.trim()
            if (e.isNew) vm.pendingNewActivity = n
            else vm.edit { c -> c.goals.filter { (it.groupName ?: it.name) == e.name }.forEach { it.groupName = n } }
            close()
        }, enabled = name.isNotBlank() && !taken) { Text(if (e.isNew) "Next: its first activity" else "Save") }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = close) { Text("Cancel") }
    }
    if (!e.isNew) MoveActivities(vm, e.name, close)
    if (!e.isNew) {
        TextButton(onClick = { confirm = true }) { Text(tr("Remove this goal and its activities"), color = Palette.Brick) }
        if (confirm) {
            Text(tr("Its activities are removed too; their log stays."), style = MaterialTheme.typography.labelSmall,
                color = Palette.Brick)
            Button(onClick = {
                val ids = vm.config.goals.filter { (it.groupName ?: it.name) == e.name }.map { it.id }.toSet()
                vm.edit { c -> c.goals.removeIf { it.id in ids }; c.activities.removeIf { it.goalId in ids } }
                ids.forEach { vm.forgetGoal(it) }
                close()
            }) { Text("Remove") }
        }
    }
}

/**
 * Some of a goal's activities moved under another goal (your request of 27 Oct 2026). Each keeps its
 * days, hours, lengths and log; its target belonged to this goal, so it is entered again for the new
 * one ([com.chronos.engine.Umbrellas.move]), and the first one opens at its Target step for that.
 */
@Composable
private fun MoveActivities(vm: ChronosViewModel, group: String, close: () -> Unit) {
    val cfg = vm.config
    val mine = cfg.goals.filter { (it.groupName ?: it.name) == group }
    val others = com.chronos.engine.Umbrellas.groups(cfg).keys.filter { it != group }
    if (mine.isEmpty() || others.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) { Text(tr("Move activities to another goal")) }
    if (!open) return
    val chosen = remember { mutableStateListOf<String>() }
    var to by remember { mutableStateOf<String?>(null) }
    mine.forEach { g ->
        val name = com.chronos.engine.Umbrellas.activityOf(cfg, g)?.name ?: g.name ?: "?"
        Row(Modifier.fillMaxWidth().clickable { if (g.id in chosen) chosen.remove(g.id) else chosen.add(g.id) },
            verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = g.id in chosen, onCheckedChange = { if (it) chosen.add(g.id) else chosen.remove(g.id) })
            Text(name.trim(), style = MaterialTheme.typography.bodyMedium)
        }
    }
    Choice<String?>("To the goal", to, others.map { it to it }) { to = it }
    Hint("Each one keeps its days, hours, lengths, loads and log. Its target — the amount, the deadline or hours a week, " +
         "the daily ceiling, its priority — was set for this goal, so you enter it again for the new one; the planner " +
         "waits until you do. A goal left with no activity is gone.")
    Button(onClick = {
        val ids = chosen.toSet()
        val dest = to ?: return@Button
        vm.edit { c -> c.goals.filter { it.id in ids }.forEach { com.chronos.engine.Umbrellas.move(it, dest) } }
        mine.firstOrNull { it.id in ids }?.let { vm.pendingFix = "target:" + it.id }
        close()
    }, enabled = chosen.isNotEmpty() && to != null) {
        Text(if (chosen.size <= 1) tr("Move") else tr("Move {}").replace("{}", chosen.size.toString()))
    }
}

// ------------------------------------------------------------- activity

@Composable
private fun ActivityEditor(vm: ChronosViewModel, e: Editing.OfActivity, issues: List<Readiness.Issue>, close: () -> Unit) {
    val a = e.activity
    val minWord = tr("min")
    val lang = LocalLang.current
    var tick by remember { mutableIntStateOf(0) }
    fun upd(f: Activity.() -> Unit) { a.f(); tick++ }
    tick
    val upkeep = a.kind == Enums.ActivityKind.MAINTENANCE
    val leisure = upkeep && a.leisure
    val cfg = vm.config
    // Goal work has its own target, edited here with it and saved together.
    var t by remember(e) { mutableStateOf(e.target ?: a.goalId?.let { cfg.goal(it) }?.copy()) }
    fun updT(f: Goal.() -> Unit) { t?.f(); tick++ }
    val color = if (leisure) Palette.Plum else if (upkeep) Palette.Moss else groupColor(cfg, t?.groupName).takeIf { it != Palette.Muted } ?: Palette.Indigo
    val steps = (if (e.isNew) listOf("Kind") else emptyList()) + listOf("Basics") +
        (if (upkeep) emptyList() else listOf("Target")) + listOf("When", "Effort")
    var step by remember { mutableIntStateOf(steps.indexOf(e.startAt).coerceAtLeast(0)) }
    var template by remember { mutableStateOf<String?>(null) }
    val title = if (e.isNew) tr(if (leisure) "New leisure" else if (upkeep) "New upkeep" else "New goal work") else (a.name ?: "")
    StepHeader(title, steps, step, color, issues.filter { it.subjectId() == a.id || it.subjectId() == t?.id })

    when (steps[step]) {
        "Kind" -> {
            Text(tr("What is it?"), style = MaterialTheme.typography.labelMedium)
            val list = if (leisure) LEISURE_TEMPLATES else if (upkeep) UPKEEP_TEMPLATES else GOAL_TEMPLATES
            list.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { t ->
                        KindTile(t.label, t.icon, template == t.label, color, Modifier.weight(1f)) {
                            template = t.label; upd { apply(t, lang) } }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            KindTile("Start blank", "＋", template == "", color, Modifier.fillMaxWidth()) { template = "" }
            Hint("A kind fills in starting values — hours, block length, how draining it is. Every one of them " +
                 "is shown on the next steps for you to check; nothing is saved until you press Save.")
        }
        "Basics" -> {
            TextField("Name", a.name ?: "") { v -> upd { name = v } }
            if (!upkeep) {
                val groups = (com.chronos.engine.Umbrellas.groups(cfg).keys.toList() + listOfNotNull(t?.groupName)).distinct()
                if (groups.isEmpty()) Hint("Add a goal first; goal work has to serve one.")
                else Choice<String?>("Serves", t?.groupName, groups.map { it to it }) { g ->
                    if (g != null) {
                        if (t == null) { t = newTarget(cfg, g); upd { goalId = t!!.id } }
                        // Work that already served a goal moves (27 Oct 2026): its target is entered again for this one.
                        else if (!e.isNew && cfg.goal(t!!.id) != null) {
                            // From what is saved, so going back to its own goal brings its target back as it was.
                            val was = cfg.goal(t!!.id)!!.copy()
                            com.chronos.engine.Umbrellas.move(was, g)
                            t = was; tick++
                        }
                        else updT { groupName = g }
                    }
                }
                val saved = t?.let { cfg.goal(it.id) }
                if (!e.isNew && saved != null && t?.groupName != (saved.groupName ?: saved.name))
                    Text(tr("Moved from \"{}\": its target starts again empty; fill it in on the Target step.")
                        .replace("{}", saved.groupName ?: saved.name ?: ""),
                        style = MaterialTheme.typography.labelSmall, color = Palette.Sand)
            }
            val deadline = t?.takeIf { !upkeep && it.mode == Enums.GoalMode.CLOSED }?.deadline
            Row(verticalAlignment = Alignment.Bottom) {
                DateField("Starts on", a.startDate, Modifier.weight(1.4f)) { d -> upd { startDate = d } }
                if (a.startDate != null) {
                    Spacer(Modifier.width(8.dp))
                    TimeField("At", a.startTime, Modifier.weight(1f)) { t -> upd { startTime = t } }
                }
                if (a.startDate != null) TextButton(onClick = { upd { startDate = null; startTime = null } }) { Text("From now") }
            }
            if (a.startDate != null && a.startTime != null) Row(verticalAlignment = Alignment.CenterVertically) {
                Hint(tr("Nothing of it is planned before this hour on its first day."))
                TextButton(onClick = { upd { startTime = null } }) { Text("Whole day") }
            }
            when {
                a.startDate == null -> Hint("Planned from now. Pick a date to keep it out of the plan until then.")
                deadline != null && !a.startDate!!.isBefore(deadline) ->
                    Text(tr("This start is not before the goal's deadline") + " (" + dateText(deadline) + "). " +
                         tr("The planner stays locked until it is moved earlier."),
                        style = MaterialTheme.typography.labelSmall, color = Palette.Brick)
                else -> Hint("Not planned before this date; its goal's hours are spread over the days from then on.")
            }
            if (upkeep && !leisure) Toggle("Only on rest days", a.restOnly,
                "A walk, a film, seeing family: planned on rest days alongside the usual upkeep, and never on a normal day.") { v ->
                upd { restOnly = v } }
            // Leisure is upkeep in kind (25 Oct 2026); an upkeep you already have may become one.
            if (upkeep && !a.nap) Toggle("Leisure", a.leisure,
                "A rest the app chooses: never planned on its own, but picked for free time and breaks inside long work, " +
                "as long as the day stays under your ceiling of stimulation.") { v ->
                upd { this.leisure = v; if (v) { restOnly = false; sacrifice = null; if (dopamine == null) dopamine = 0.5 } } }
            if (a.pauseUntil != null && !a.pauseUntil!!.isBefore(java.time.LocalDate.now())) {
                Hint(tr("Paused by the Navigator") + ": " + dateText(a.pauseFrom!!) + " – " + dateText(a.pauseUntil!!))
                TextButton(onClick = { upd { pauseFrom = null; pauseUntil = null } }) { Text("End the pause") }
            }
            // An upkeep's period (your request of 22 Oct 2026): planned up to its last day, kept with its log after.
            if (upkeep && !e.isNew) {
                val today = java.time.LocalDate.now()
                Row(verticalAlignment = Alignment.Bottom) {
                    DateField("Last day", a.endDate, Modifier.weight(1f)) { d -> upd { endDate = d } }
                    if (a.endDate != null) TextButton(onClick = { upd { endDate = null } }) { Text("No end") }
                }
                when {
                    a.endDate == null -> {
                        Hint("Planned with no end. Give it a last day when its period is over — its log is kept, it is just not planned after that.")
                        OutlinedButton(onClick = { upd { endDate = today.minusDays(1) } }) { Text("Its period is over") }
                    }
                    a.endedBy(today) -> Hint(tr("Ended") + ": " + tr("not planned any more; its log and learned values are kept."))
                    else -> Hint("Planned up to and including this day, not after it.")
                }
            }
            if (!e.isNew) Toggle("Archived", a.archived,
                "Not planned on any day, but kept with its log. The Navigator archives instead of deleting.") { v ->
                upd { archived = v } }
            if (!e.isNew && !leisure) {
                TextButton(onClick = { upd {
                    if (upkeep) {
                        kind = Enums.ActivityKind.GOAL; restOnly = false
                        if (t == null) t = newTarget(cfg, com.chronos.engine.Umbrellas.groups(cfg).keys.firstOrNull() ?: (name ?: ""))
                        goalId = t!!.id
                    } else { kind = Enums.ActivityKind.MAINTENANCE; goalId = null }
                } }) { Text(if (upkeep) "Move to goal work" else "Move to upkeep") }
            }
        }
        "Target" -> {
            val g = t
            if (g == null) Hint("Choose the goal it serves first.")
            else {
                g.note?.let { Text(translate(lang, it), style = MaterialTheme.typography.labelSmall, color = Palette.Sand) }
                Text(tr("What kind of target?"), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
                Row {
                    KindTile("With a deadline", "◷", g.mode == Enums.GoalMode.CLOSED, color, Modifier.weight(1f)) {
                        updT { mode = Enums.GoalMode.CLOSED } }
                    KindTile("Ongoing", "∞", g.mode == Enums.GoalMode.OPEN, color, Modifier.weight(1f)) {
                        updT { mode = Enums.GoalMode.OPEN } }
                }
                Hint(if (g.mode == Enums.GoalMode.OPEN) "Never finishes; paced by hours a week (a language, coursework)."
                     else "A set amount of work by a date (30 videos by 12 Oct).")
                val hPerWeek = tr("h per week")
                if (g.mode == Enums.GoalMode.CLOSED) {
                    Row {
                        NumberField("Total amount", g.totalUnits, modifier = Modifier.weight(1f)) { v -> updT { totalUnits = v } }
                        Spacer(Modifier.width(8.dp))
                        NumberField("Already done", g.doneUnits, modifier = Modifier.weight(1f)) { v -> updT { doneUnits = v ?: 0.0 } }
                    }
                    TextField("Unit", g.unit ?: "", "Papers, problem sets, chapters, videos.") { v -> updT { unit = v } }
                    NumberField("Hours per unit", g.hoursPerUnit,
                        "Your honest estimate on a good day. The planner discounts it downward itself.") { v -> updT { hoursPerUnit = v } }
                    // A date and, if it matters, the hour (your request of 22 Oct 2026): a deadline at noon leaves the afternoon out.
                    Row(verticalAlignment = Alignment.Bottom) {
                        DateField("Deadline", g.deadline, Modifier.weight(1.4f)) { d -> updT { deadline = d } }
                        Spacer(Modifier.width(8.dp))
                        TimeField("At", g.deadlineTime, Modifier.weight(1f)) { t -> updT { deadlineTime = t } }
                    }
                    if (g.deadlineTime != null) Row(verticalAlignment = Alignment.CenterVertically) {
                        Hint(tr("Nothing of it is planned after this hour on the deadline day."))
                        TextButton(onClick = { updT { deadlineTime = null } }) { Text("Whole day") }
                    }
                    // An extended deadline on work whose units are all logged (25 Oct 2026): it stays in the
                    // log's lists, but the planner gives it no hours until the total says something is left.
                    if ((g.totalUnits ?: 0.0) > 0 && g.remainingUnits() <= 0 && g.deadline?.isBefore(java.time.LocalDate.now()) == false) {
                        Text(tr("Every unit is marked done, so nothing of it is planned. If work is left, raise the total."),
                            style = MaterialTheme.typography.labelSmall, color = Palette.Sand)
                        Row {
                            listOf(1.0, 2.0, 3.0).forEach { more ->
                                TextButton(onClick = { updT { totalUnits = (doneUnits ?: 0.0) + more } }) {
                                    Text(tr("{} left").replace("{}", trimNumber(more)))
                                }
                            }
                        }
                    }
                } else {
                    LevelSlider("Weekly floor", g.weeklyFloorH, 0.0..40.0, 0.5, "0 h", "40 h", color,
                        format = { trimNumber(it) + " " + hPerWeek },
                        hint = "An ongoing target never finishes; this is what paces it.") { v -> updT { weeklyFloorH = v } }
                    NumberField("Hours per unit", g.hoursPerUnit,
                        "For ongoing work one unit is one hour, so this is usually 1.") { v -> updT { hoursPerUnit = v } }
                }
                LevelSlider("Ceiling per day", g.maxPerDayH, 0.5..12.0, 0.5, "½ h", "12 h", color,
                    format = { trimNumber(it) + " h" }, hint = "Respected even when a deadline is close.") { v -> updT { maxPerDayH = v } }
                Toggle("Hard", g.hard, "Must be met whatever else happens; a soft target gives way first.") { v -> updT { hard = v } }
                // Its priority level (your choice of 26 Oct 2026), asked as what missing it would cost. Until
                // you pick, the app places it and says why; its place within the level is set in Priorities.
                val dated = g.mode == Enums.GoalMode.CLOSED
                Text(tr(if (dated) "If this is not done in time, how bad is it?" else "How much does this matter?"),
                    style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 10.dp))
                FlowRowSimple {
                    (0..3).forEach { k ->
                        Pill(tr(if (dated) TIER_ANSWERS[k] else com.chronos.engine.Priorities.NAMES[k]), g.tier == k && !g.tierAuto) {
                            updT { if (tier != k) { tier = k; rank = Int.MAX_VALUE }; tierAuto = false }
                        }
                    }
                }
                if (g.tier == null || g.tierAuto) {
                    val s = com.chronos.engine.Priorities.suggest(cfg, g, java.time.LocalDate.now())
                    Hint(tr("If you do not choose, the app makes it {}").replace("{}", tr(com.chronos.engine.Priorities.NAMES[s.tier()]).lowercase()) +
                        ": " + s.why().joinToString("، ") { translate(lang, it) })
                }
                Hint(tr("Weight") + " " + Math.round(100 * g.weight) + "% — " + tr("from its level and its place in Priorities."))
            }
        }
        "When" -> {
            Text(tr("Days it may happen"), style = MaterialTheme.typography.labelMedium)
            DayPicker(a.days.toSet()) { days -> upd { this.days.clear(); this.days.addAll(days) } }
            Text(tr("Allowed hours"), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 10.dp))
            a.windows.forEachIndexed { i, w ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        TimeRangeBar(w.from, w.to, color) { f, t -> upd { windows[i].from = f; windows[i].to = t } }
                    }
                    TextButton(onClick = { upd { windows.removeAt(i) } }) { Text("×") }
                }
                // The weekdays this window holds on (your request of 27 Oct 2026): each day may have hours of its own.
                if (a.days.size > 1) WindowDays(w.days, a.days, lang) { d -> upd { windows[i].days = d } }
            }
            TextButton(onClick = { upd { windows.add(Activity.Window("08:00", "12:00")) } }) { Text("Add a window") }
            Hint("Several windows are allowed. A morning and an afternoon slot is common. Under each window, pick the days " +
                 "it holds on to give a day hours of its own; \"Every day\" is the usual.")
            if (a.windowsByDay()) {
                a.days.forEach { d ->
                    val hours = a.windowsOn(d)
                    Text(weekday(lang, d) + ": " + (if (hours.isEmpty()) tr("not planned (no window)")
                        else hours.joinToString("، ") { it.from + "–" + it.to }),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (hours.isEmpty() && a.pins.none { it.day == d }) Palette.Brick else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(tr("Pinned to a fixed time on these days"), style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 10.dp))
            a.pins.forEachIndexed { i, p ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    Choice("Day", p.day, Slots.DOW.mapIndexed { k, _ -> k to weekday(LocalLang.current, k) },
                        modifier = Modifier.weight(1f)) { d -> upd { pins[i].day = d } }
                    Spacer(Modifier.width(8.dp))
                    TimeField("At", p.start, Modifier.weight(1f)) { v -> upd { pins[i].start = v } }
                    TextButton(onClick = { upd { pins.removeAt(i) } }) { Text("×") }
                }
            }
            TextButton(onClick = { upd { pins.add(Activity.Pin(0, "07:00")) } }) { Text("Pin a day") }
            Hint("A pin overrides the window. Use it for things that are fixed on class days but flexible otherwise.")
        }
        else -> {
            BlockLengthBar(a.minBlock, a.maxBlock, color) { lo, hi -> upd { minBlock = lo; maxBlock = hi } }
            if (!a.splitConfirmed) {
                Text(tr("The split between mental and physical below was suggested by the app."),
                    style = MaterialTheme.typography.labelSmall, color = Palette.Sand)
                TextButton(onClick = { upd { splitConfirmed = true } }) { Text("Keep these values") }
            }
            LevelSlider("Mental load", a.load, 0.0..1.0, 0.05, "costs nothing", "most draining", color,
                hint = "Per hour: how much it tires your mind.") { v -> upd { load = v; splitConfirmed = true } }
            LevelSlider("Physical load", a.physLoad, 0.0..1.0, 0.05, "costs nothing", "most draining", color,
                hint = "Per hour: how much it tires your body. A workout is high here and low above.") { v ->
                upd { physLoad = v; splitConfirmed = true } }
            LevelSlider("Mental recovery", a.recovery, 0.0..1.0, 0.05, "restores nothing", "fully restoring", color,
                hint = "Per hour: how much it rests your mind. Meals, walks and exercise belong here.") { v ->
                upd { recovery = v; splitConfirmed = true } }
            LevelSlider("Physical recovery", a.physRecovery, 0.0..1.0, 0.05, "restores nothing", "fully restoring", color,
                hint = "Per hour: how much it rests your body. Meals and naps belong here.") { v ->
                upd { physRecovery = v; splitConfirmed = true } }
            LevelSlider("Changeover before it", (a.changeoverMin ?: 0).toDouble(), 0.0..60.0, 5.0, "0 min", "60 min", color,
                format = { "${it.toInt()} " + minWord },
                hint = "Time to get into it: changing for the gym, walking to the kitchen.") { v ->
                upd { changeoverMin = v.toInt().takeIf { it > 0 } } }
            if (!upkeep) {
                Choice("Sessions per day", if (a.blocksMode == Enums.BlocksMode.AUTO) 0 else a.maxBlocks,
                    listOf(0 to "Let the model decide", 1 to "1", 2 to "2", 3 to "3", 4 to "4")) { n ->
                    upd { if (n == 0) blocksMode = Enums.BlocksMode.AUTO else { blocksMode = Enums.BlocksMode.FIXED; maxBlocks = n } }
                }
                Toggle("Needs a high-focus window", a.needsHighEnergy, "Counted separately in the capacity audit.") { v ->
                    upd { needsHighEnergy = v } }
            } else {
                Choice(if (leisure) "At most per day" else "Sessions per day", a.maxBlocks, (1..4).map { it to "$it" }) { n -> upd { maxBlocks = n } }
            }
            if (leisure) {
                LevelSlider("How stimulating", a.dopamine, 0.0..2.0, 0.05, "calm", "very", color,
                    hint = "Per hour, on the scale where your day's ceiling is about 1: a walk is low, short videos are high. " +
                        "A first guess; the log corrects it from how hard it was to stop and to start the next thing.") { v ->
                    upd { dopamine = v } }
                if (a.dopamineSeen > 0) Hint(tr("Learned from {} sessions").replace("{}", a.dopamineSeen.toString()))
            }
            Toggle("Happens out of the house", a.away, "Out-of-house items decide when the return trip may start.") { v ->
                upd { away = v } }
            val canCut = (upkeep && !leisure) || (!upkeep && t?.mode == Enums.GoalMode.OPEN)
            if (canCut) {
                Choice("When a deadline is at risk", a.sacrificeRank(), listOf(
                    Activity.SACRIFICE_NEVER to "Never cut it",
                    Activity.SACRIFICE_LAST to "Cut it as a last resort",
                    Activity.SACRIFICE_FIRST to "Cut it first")) { r -> upd { sacrifice = r } }
                if (upkeep && a.sacrificeRank() != Activity.SACRIFICE_NEVER)
                    IntField("Keep at least (min) when cut", a.keepMin,
                        "Empty or 0 drops it on that day. A workout that can shrink to a short run says 30.") { v ->
                        upd { keepMin = v } }
            }
        }
    }

    StepFooter(step, steps.lastIndex, e.isNew, !a.name.isNullOrBlank() && (upkeep || t != null), color,
        { step = it },
        onSave = {
            val saved = a.copy()
            // Goal work and its target are one thing to you: saved together, the target named after the activity.
            val target = if (upkeep) null else t?.copy()?.apply {
                name = saved.name?.trim()
                // Moved and filled in again: the note asking for it has done its work.
                if (note?.startsWith("Moved from") == true && filled()) note = null
            }
            if (target != null) saved.goalId = target.id
            val old = cfg.activity(saved.id)?.goalId
            vm.edit { c ->
                val i = c.activities.indexOfFirst { it.id == saved.id }
                if (i >= 0) c.activities[i] = saved else c.activities.add(saved)
                if (target != null) {
                    val j = c.goals.indexOfFirst { it.id == target.id }
                    if (j >= 0) c.goals[j] = target else c.goals.add(target)
                }
                // Moved to upkeep: its target goes with it.
                if (upkeep && old != null) c.goals.removeIf { it.id == old }
            }
            if (upkeep && old != null) vm.forgetGoal(old)
            close()
        },
        onCancel = close,
        onRemove = if (e.isNew) null else ({
            val target = a.goalId
            vm.edit { c -> c.activities.removeIf { it.id == a.id }; if (target != null) c.goals.removeIf { it.id == target } }
            target?.let { vm.forgetGoal(it) }
            close()
        }))
}

// ----------------------------------------------------------- commitment

@Composable
private fun CommitmentEditor(vm: ChronosViewModel, e: Editing.OfCommitment, issues: List<Readiness.Issue>, close: () -> Unit) {
    val c = e.commitment
    val minWord = tr("min")
    var tick by remember { mutableIntStateOf(0) }
    fun upd(f: Commitment.() -> Unit) { c.f(); tick++ }
    tick
    val color = Palette.Ochre
    val steps = (if (e.isNew) listOf("Kind") else emptyList()) + listOf("Basics", "Times")
    var step by remember { mutableIntStateOf(steps.indexOf(e.startAt).coerceAtLeast(0)) }
    StepHeader(if (e.isNew) tr("New commitment") else (c.name ?: ""), steps, step, color,
        issues.filter { it.subjectId() == c.id })
    val fixed = c.kind == Enums.CommitmentKind.FIXED

    when (steps[step]) {
        "Kind" -> {
            Row {
                KindTile("Class or fixed session", "▦", c.kind == Enums.CommitmentKind.FIXED, color, Modifier.weight(1f)) {
                    upd { kind = Enums.CommitmentKind.FIXED; away = true } }
                KindTile("Travel", "➝", c.kind == Enums.CommitmentKind.VARIABLE, color, Modifier.weight(1f)) {
                    upd {
                        kind = Enums.CommitmentKind.VARIABLE
                        if (medianMin == null) { medianMin = 30.0; sigma = 0.25; quantile = 0.8; load = 0.2; recovery = 0.0
                            physLoad = 0.2; physRecovery = 0.0 }
                    } }
            }
            Hint(if (c.kind == Enums.CommitmentKind.VARIABLE)
                "A trip that wraps around whatever happens out of the house that day. Its starting values are shown on the next step."
                else "Times that are not yours to choose: a class, a lab, work hours.")
        }
        "Basics" -> {
            TextField("Name", c.name ?: "") { v -> upd { name = v } }
            if (fixed) Toggle("Happens out of the house", c.away) { v -> upd { away = v } }
            else {
                LevelSlider("Typical one-way time", c.medianMin, 5.0..180.0, 5.0, "5 min", "3 h", color,
                    format = { "${it.toInt()} " + minWord }, hint = "The median, not the best case.") { v -> upd { medianMin = v } }
                LevelSlider("Variability", c.sigma, 0.05..0.6, 0.05, "reliable", "unpredictable", color) { v -> upd { sigma = v } }
                LevelSlider("Plan at quantile", c.quantile, 0.5..0.95, 0.05, "often late", "rarely late", color,
                    hint = "0.8 means accepting that you run late one day in five.") { v -> upd { quantile = v } }
                LevelSlider("Mental load", c.load, 0.0..1.0, 0.05, "costs nothing", "most draining", color) { v -> upd { load = v } }
                LevelSlider("Physical load", c.physLoad, 0.0..1.0, 0.05, "costs nothing", "most draining", color) { v -> upd { physLoad = v } }
                LevelSlider("Mental recovery", c.recovery, 0.0..1.0, 0.05, "restores nothing", "fully restoring", color) { v -> upd { recovery = v } }
                LevelSlider("Physical recovery", c.physRecovery, 0.0..1.0, 0.05, "restores nothing", "fully restoring", color) { v -> upd { physRecovery = v } }
            }
        }
        else -> {
            if (fixed) {
                // In week order, so the list reads like the timetable.
                c.sessions.sortWith(compareBy({ it.day }, { it.start ?: "" }))
                c.sessions.forEachIndexed { j, s ->
                    // A session not yet rated opens with its sliders; a rated one shows them in one line.
                    var loads by remember(c.id, j) { mutableStateOf(s.load == null) }
                    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(8.dp))
                        .border(BorderStroke(1.dp, classColor(vm.config, c.id).copy(alpha = 0.45f)), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Choice("Day", s.day, Slots.DOW.mapIndexed { k, _ -> k to weekday(LocalLang.current, k) },
                                modifier = Modifier.weight(1f)) { d -> upd { sessions[j].day = d } }
                            TextButton(onClick = { upd { sessions.removeAt(j) } }) { Text("×") }
                        }
                        TimeRangeBar(s.start, s.end, color) { f, t -> upd { sessions[j].start = f; sessions[j].end = t } }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { upd { sessions.add(sessions[j].copy().also { it.day = (it.day + 1) % 7 }) } }) {
                                Text("Same time, another day") }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { loads = !loads }) {
                                Text((if (loads) "▴ " else "▾ ") + tr("Load") + if (loads) "" else "  " +
                                    tr("mind") + " " + trimNumber(s.load ?: 0.0) + "/" + trimNumber(s.recovery ?: 0.0) + " · " +
                                    tr("body") + " " + trimNumber(s.physLoad ?: 0.0) + "/" + trimNumber(s.physRecovery ?: 0.0))
                            }
                        }
                        if (loads) {
                            LevelSlider("Mental load", s.load, 0.0..1.0, 0.05, "costs nothing", "most draining", color) { v ->
                                upd { sessions[j].load = v } }
                            LevelSlider("Physical load", s.physLoad, 0.0..1.0, 0.05, "costs nothing", "most draining", color) { v ->
                                upd { sessions[j].physLoad = v } }
                            LevelSlider("Mental recovery", s.recovery, 0.0..1.0, 0.05, "restores nothing", "fully restoring", color) { v ->
                                upd { sessions[j].recovery = v } }
                            LevelSlider("Physical recovery", s.physRecovery, 0.0..1.0, 0.05, "restores nothing", "fully restoring", color) { v ->
                                upd { sessions[j].physRecovery = v } }
                        }
                    }
                }
                TextButton(onClick = { upd { sessions.add(Commitment.Session(sessions.lastOrNull()?.day ?: 0,
                    sessions.lastOrNull()?.start ?: "09:00", sessions.lastOrNull()?.end ?: "11:00")) } }) { Text("Add a session") }
                Hint("Each session has its own load and recovery: a four-hour lab drains more than a lecture.")
            } else {
                LegBlock("Getting there", c.outbound, color) { tick++ }
                LegBlock("Coming back", c.inbound, color) { tick++ }
                Hint("The trip wraps around everything marked out of the house that day. It is never dropped — if it " +
                     "will not fit, the day plan reports the conflict instead.")
            }
        }
    }

    StepFooter(step, steps.lastIndex, e.isNew, !c.name.isNullOrBlank() && c.kind != null, color, { step = it },
        onSave = {
            val saved = c.copy()
            vm.edit { cfg ->
                val i = cfg.commitments.indexOfFirst { it.id == saved.id }
                if (i >= 0) cfg.commitments[i] = saved else cfg.commitments.add(saved)
            }
            close()
        },
        onCancel = close,
        onRemove = if (e.isNew) null else ({ vm.edit { cfg -> cfg.commitments.removeIf { it.id == c.id } }; close() }))
}

@Composable
private fun LegBlock(label: String, leg: Commitment.Leg, color: Color, changed: () -> Unit) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Choice(label, leg.mode, listOf(
            Enums.LegMode.PINNED to "Pinned to the session",
            Enums.LegMode.FLEXIBLE to "Flexible inside a window",
            Enums.LegMode.NONE to "Does not happen")) { m -> leg.mode = m; changed() }
        if (leg.mode == Enums.LegMode.FLEXIBLE)
            TimeRangeBar(leg.from ?: "13:00", leg.to ?: "19:00", color) { f, t -> leg.from = f; leg.to = t; changed() }
    }
}
