package com.chronos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.relocation.bringIntoViewRequester
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chronos.engine.Feasibility
import com.chronos.engine.HorizonPlanner
import com.chronos.engine.Jalali
import com.chronos.engine.Sacrifice
import com.chronos.model.Enums
import com.chronos.validate.Readiness

@Composable
fun HorizonScreen(vm: ChronosViewModel) {
    val rev = vm.revision
    val bundle = vm.bundle
    val plan = bundle?.plan()

    ScreenHeader(
        "Horizon",
        "The whole month is allocated before any single day is arranged. Each goal's remaining " +
        "work is spread over the days that can hold it, serving whichever goal has the least " +
        "room to spare first."
    )

    var asking by remember { mutableStateOf(false) }
    if (vm.restToday) Verdict(Tone.Good, "Today is a rest day",
        "Goal work is paused today. The days after it come back gradually, as far as the deadlines allow.")
    Panel {
        Text(dateText(vm.today) + (vm.config.planFrom?.takeIf { it.toLocalDate() == vm.today }
            ?.let { "  ·  " + tr("from") + " " + "%02d:%02d".format(it.hour, it.minute) } ?: ""),
            style = MaterialTheme.typography.titleMedium)
        Hint("Planning from this date. Each day closes itself at ${vm.dayEnd}: what you logged " +
             "updates the model, and the plan is rebuilt from the next day and sent to your calendar.")
        Spacer(Modifier.height(10.dp))
        Button(onClick = { asking = true }, enabled = !vm.busy) { Text("Build the plan") }
    }
    if (asking) StartDateDialog(vm, onDismiss = { asking = false }) { start, time ->
        asking = false
        vm.rebuild(start, time)
    }

    if (bundle == null) {
        Verdict(Tone.Warn, "No plan yet", "Press Build the plan to solve the horizon.")
        return
    }

    if (bundle.stale() && vm.modelChanges.isNotEmpty()) {
        Panel("Your log changed the model", Palette.Sand) {
            Text("The entries you applied moved these estimates, so the plan was built on old " +
                 "numbers. Build the plan to use the new ones, or leave it to tonight's rebuild.",
                style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(6.dp))
            vm.modelChanges.forEach { c ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(c.subject(), style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.width(120.dp))
                    Text(c.detail(), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = { asking = true }, enabled = !vm.busy) { Text("Build the plan") }
        }
    } else if (bundle.stale()) {
        Verdict(
            Tone.Warn, "This plan no longer matches your settings",
            "Something you changed would move the schedule. It has been left alone on purpose — " +
            "press Build the plan when you want the new version, or leave it to tonight's rebuild."
        )
    }

    // ---- the four tiles (your choice of 24 Oct 2026); each takes you to its picture
    val scope = rememberCoroutineScope()
    val toMonth = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    val toGoals = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    val toProblems = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    run {
        val p0 = bundle.plan()
        val week = p0.days().take(7).indices
        val goalH = week.sumOf { i -> p0.allocation()[i].values.sum() }
        val freeH = week.sumOf { i -> (p0.days()[i].capacityH() - p0.allocation()[i].values.sum()).coerceAtLeast(0.0) }
        val dated = datedInPlay(vm, bundle.start())
        val shortN = vm.coverage.count { !it.finishes() }
        val problems = shortN + vm.upcoming.sumOf { it.missing().size }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HorizonTile(Modifier.weight(1f), tr("Deadlines"), localiseDigits(vm.lang, "${dated.size - shortN}/${dated.size}"),
                tr(if (shortN == 0) "all on track" else "on track"), if (shortN > 0) Palette.Brick else Palette.Moss) {
                scope.launch { toGoals.bringIntoView() } }
            HorizonTile(Modifier.weight(1f), tr("Next 7 days"), localiseDigits(vm.lang, trimNumber(Math.round(goalH * 2) / 2.0) + " h"),
                tr("of goal work planned"), Palette.Indigo) { scope.launch { toMonth.bringIntoView() } }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HorizonTile(Modifier.weight(1f), tr("Room left"), localiseDigits(vm.lang, trimNumber(Math.round(freeH * 2) / 2.0) + " h"),
                tr("usable but unplanned this week"), Palette.Teal) { scope.launch { toMonth.bringIntoView() } }
            HorizonTile(Modifier.weight(1f), tr("Problems"), localiseDigits(vm.lang, problems.toString()),
                tr(if (problems == 0) "nothing left out" else "to look at"), if (problems > 0) Palette.Sand else Palette.Muted) {
                scope.launch { toProblems.bringIntoView() } }
        }
        Spacer(Modifier.height(6.dp))
    }

    val cuts = remember(bundle, vm.revision) {
        Sacrifice.describe(vm.config, bundle.start(), bundle.cuts() ?: emptyList())
    }
    val violations = plan!!.violations()
    val overdue = plan.overdue()
    // A shortfall that rounds to 0.0h is arithmetic, not news.
    val shortfalls = plan.shortfalls().filter { it.missingH() >= 0.05 }

    when {
        overdue.isNotEmpty() -> Verdict(
            Tone.Bad, "Past their deadline",
            overdue.joinToString(", ") { "${it.goal()} (${"%.1f".format(it.missingH())}h outstanding)" } +
            ". The planner treats this as due immediately, which distorts everything after it. " +
            "Either move the deadline or write off the remainder."
        )
        violations.isNotEmpty() -> violations.forEach { v ->
            Verdict(
                Tone.Bad, "These deadlines cannot all be met",
                "By ${v.by()}, ${v.goals().joinToString(" and ")} need ${"%.1f".format(v.demandH())}h " +
                "but only ${"%.1f".format(v.supplyH())}h exists before then. You are " +
                "${"%.1f".format(v.deficitH())}h short — move a deadline, cut scope, or find time elsewhere."
            )
        }
        // A goal that the arranged days cannot cover is the strongest thing to
        // say, and it is measured rather than inferred — so it outranks both the
        // capacity estimate and the horizon's own pacing shortfall.
        vm.coverage.any { !it.finishes() } -> Unit        // the panel below says it properly
        // The capacity estimate below knows nothing of the cuts; the arranged
        // days, which do, have just said every goal finishes.
        cuts.isNotEmpty() && shortfalls.isEmpty() -> Verdict(
            Tone.Good, "Every deadline is reachable, by giving up some time",
            "The activities you allowed to be cut make the room. The list below says what, and when."
        )
        vm.feasibility?.clean() == false -> Unit
        shortfalls.isNotEmpty() -> Verdict(
            Tone.Warn, "Fits on paper, but the pacing rules leave work over",
            shortfalls.joinToString(", ") { "${it.goal()} (${"%.1f".format(it.missingH())}h)" } +
            ". Usually the daily ceiling or the weekday restriction is what binds."
        )
        else -> Verdict(
            Tone.Good, "Every deadline in the horizon is reachable",
            "Checked against the arranged days, not only against the hours that exist."
        )
    }

    Box(Modifier.bringIntoViewRequester(toMonth)) {
        Panel("The month", Palette.Steel) {
            vm.observeConfig()
            Hint("Each day coloured by how much of its usable time the plan fills. Tap a day for what it holds.")
            MonthHeat(vm, plan)
        }
    }
    Box(Modifier.bringIntoViewRequester(toGoals)) {
        Panel("Goals to their deadlines", Palette.Indigo) {
            vm.observeConfig()
            GoalsTimeline(vm, plan)
        }
    }

    Column(Modifier.bringIntoViewRequester(toProblems)) {
        UpcomingPanel(vm)
        CoverageCheck(vm)
        if (cuts.isEmpty()) DeadlineCheck(vm)
        CutsPanel(cuts)
    }

    Folded("Progress", tr("What is done, and each deadline; edit them here."), Palette.Moss, "progress") {
        vm.observeConfig()
        Hint("Change what you have finished, or move a deadline, and everything downstream reflows.")
        Spacer(Modifier.height(8.dp))
        val today = java.time.LocalDate.now()
        val finished = vm.config.goals.filter { vm.outOfPlay(it, today) != null }
        vm.config.goals.forEachIndexed { index, goal ->
            // Finished, expired and archived targets are summed up below instead (23 Oct 2026).
            if (vm.outOfPlay(goal, today) != null) return@forEachIndexed
            Column(Modifier.padding(vertical = 8.dp)) {
                Text(goal.name, style = MaterialTheme.typography.bodyMedium)
                if (goal.mode == Enums.GoalMode.CLOSED) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        NumberField("Done", goal.doneUnits, modifier = Modifier.weight(1f)) { v ->
                            vm.edit { it.goals[index].doneUnits = v ?: 0.0 }
                        }
                        Spacer(Modifier.width(10.dp))
                        DateField("Deadline", goal.deadline, modifier = Modifier.weight(1.4f)) { d ->
                            vm.edit { it.goals[index].deadline = d }
                        }
                    }
                    val total = goal.totalUnits ?: 0.0
                    val fraction = if (total > 0) ((goal.doneUnits ?: 0.0) / total).toFloat() else 0f
                    Spacer(Modifier.height(6.dp))
                    Meter(fraction, GoalColors.target(vm.config.goals, goal.id))
                    Hint("${(goal.doneUnits ?: 0.0).toInt()} of ${total.toInt()} ${goal.unit ?: ""}")
                } else {
                    Hint("Ongoing, paced at ${goal.weeklyFloorH ?: 0.0}h per week")
                }
            }
            HorizontalDivider()
        }
        if (finished.isNotEmpty()) {
            var showDone by remember { mutableStateOf(false) }
            TextButton(onClick = { showDone = !showDone }) {
                Text((if (showDone) "▴ " else "▾ ") + tr("Done and past") + " (" + finished.size + ")")
            }
            if (showDone) finished.forEach { g ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(GoalColors.target(vm.config.goals, g.id)))
                    Spacer(Modifier.width(8.dp))
                    Text(g.name ?: "?", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Text(listOfNotNull(
                        if (g.mode == Enums.GoalMode.CLOSED) trimNumber(g.doneUnits ?: 0.0) + "/" + trimNumber(g.totalUnits ?: 0.0) else null,
                        g.deadline?.let { dateText(it) },
                        tr(vm.outOfPlay(g, today) ?: "")).joinToString("  ·  "),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (showDone) Hint("Move a deadline later or raise an amount (in Setup) and it comes back above.")
        }
    }

}

/* ------------------------------------------------------- the deadline check */

/**
 * Whether every dated goal actually finishes, and what would let the ones that
 * do not. The suggestions are measured, not guessed: each was applied to a copy
 * of the configuration and the horizon re-planned, and the hours shown are the
 * hours it recovered. They are ordered by what they cost — rearranging the same
 * work first, working harder second, cutting the goal last.
 */
@Composable
private fun DeadlineCheck(vm: ChronosViewModel) {
    vm.observeConfig()
    val report = vm.feasibility ?: return
    if (report.clean()) {
        // The measured check above outranks this estimate, so its reassurance is
        // withheld while that one is reporting a goal that does not finish.
        if (vm.coverage.any { !it.finishes() }) return
        Panel("Deadline check", Palette.Moss) {
            Text("Every dated goal has somewhere to put all of its hours before its deadline.",
                style = MaterialTheme.typography.bodySmall)
            Hint("Checked against the days the goal is allowed, the hours its activities may use, " +
                 "and the room left once sleep, classes, travel and upkeep are taken out.")
        }
        return
    }

    Panel("These goals do not finish", Palette.Brick) {
        Text("Priority is finishing the goals, not fitting them in loosely — so this is reported " +
             "rather than quietly paced short. The suggestions below are ordered by what they " +
             "cost you: the first ones move the same work, they do not add any.",
            style = MaterialTheme.typography.bodySmall)

        report.deficits().forEach { d ->
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(10.dp))
            Text(d.goalName() ?: "Goal", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Stat("Short by", "%.1fh".format(d.missingH()),
                    "of ${"%.1f".format(d.neededH())}h needed", Modifier.weight(1f))
                Stat("Can take", "%.1fh".format(d.deliverableH()),
                    "by ${d.deadline()}", Modifier.weight(1f))
            }
            Meter((d.deliverableH() / d.neededH().coerceAtLeast(0.01)).toFloat(), Palette.Brick)
            Text(d.explanation(), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp))

            RoomByWeekday(vm, d.goalId())

            val mine = report.remedies().filter { it.goalId() == d.goalId() }
            if (mine.isEmpty()) {
                Hint("Nothing the planner tried recovered the hours on its own. The gap is large " +
                     "enough that it needs more than one change — try the deadline and the scope " +
                     "together.")
            } else {
                Spacer(Modifier.height(10.dp))
                Text("What would fix it", style = MaterialTheme.typography.titleMedium)
                mine.forEach { RemedyRow(it) }
            }
        }
    }
}

/** Where this goal actually has room, weekday by weekday. */
@Composable
private fun RoomByWeekday(vm: ChronosViewModel, goalId: String) {
    val room = remember(goalId, vm.revision) { Feasibility.roomByWeekday(vm.config, goalId) }
    Spacer(Modifier.height(10.dp))
    Text("Room per weekday", style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    val lang = LocalLang.current
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        com.chronos.engine.Slots.DOW.forEachIndexed { d, _ ->
            val name = weekday(lang, d)
            val hours = room[d] ?: 0.0
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(name, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (hours <= 0.05) "—" else "%.1f".format(hours),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (hours <= 0.05) Palette.Brick else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
    Hint("Hours inside this goal's allowed windows that are free of sleep, classes and travel, " +
         "counted only in stretches long enough for one session. A dash means the goal can never " +
         "run that day as configured.")
}

@Composable
private fun RemedyRow(r: Feasibility.Remedy, vm: ChronosViewModel? = null) {
    val (label, tint) = when (r.cost()) {
        Feasibility.Cost.REARRANGE -> "Same work" to Palette.Moss
        Feasibility.Cost.INTENSIFY -> "Costs fatigue" to Palette.Sand
        else -> "Changes the goal" to Palette.Brick
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(tint,
                androidx.compose.foundation.shape.RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
            Spacer(Modifier.weight(1f))
            Text(if (r.clearsIt()) "clears it" else "+%.1fh".format(r.recoveredH()),
                style = MaterialTheme.typography.labelSmall,
                color = if (r.clearsIt()) Palette.Moss else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(r.what(), style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 2.dp))
        // Straight to where it is changed (your request of 23 Oct 2026).
        if (vm != null && r.fix() != null)
            TextButton(onClick = { vm.goFix(r.fix()) }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                Text(tr(when {
                    r.fix() == "scheduler" -> "Change it in the Scheduler"
                    r.fix() == "upkeep" -> "Go to upkeep"
                    else -> "Change it in Setup"
                }) + " ›", style = MaterialTheme.typography.labelMedium)
            }
    }
}

/* ------------------------------------------------- what the days really cover */

/**
 * The honest answer to "will this goal finish": the hours its arranged days
 * actually place, against the hours it needs.
 *
 * The horizon's own guarantee is that the free hours exist. It cannot see that
 * an activity's allowed window, after a class and a meal and the trip home, has
 * no unbroken stretch left long enough for one session — so it can pass while
 * the timetable quietly drops work. This panel compares against the real days.
 */
@Composable
private fun CoverageCheck(vm: ChronosViewModel) {
    val short = vm.coverage.filter { !it.finishes() }
    if (short.isEmpty()) return

    Panel("These goals do not finish in the arranged days", Palette.Brick) {
        Text("Measured against the timetable, not only against the hours that exist. The horizon " +
             "can pace work onto a day whose allowed windows have no room left to arrange it.",
            style = MaterialTheme.typography.bodySmall)

        short.forEach { c ->
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(10.dp))
            Text(c.goalName() ?: "Goal", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Stat("Short by", "%.1fh".format(c.missingH()),
                    "of ${"%.1f".format(c.neededH())}h needed", Modifier.weight(1f))
                Stat("Actually placed", "%.1fh".format(c.placedH()),
                    "by ${c.deadline()}", Modifier.weight(1f))
            }
            Meter((c.placedH() / c.neededH().coerceAtLeast(0.01)).toFloat(), Palette.Brick)

            // Two different failures, and the fix differs: hours that were never
            // allocated mean the weeks are too full, hours allocated but not
            // placed mean a day could not arrange what it was given.
            val lostToDays = c.lostToArrangementH()
            if (lostToDays > 0.25) {
                Text("${"%.1f".format(lostToDays)}h of what the horizon allocated could not be " +
                     "arranged in the day itself — the allowed hours had no stretch long enough " +
                     "left in them.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp))
                c.worstDays().forEach { d ->
                    Text("· ${dateText(d.date())} lost ${"%.1f".format(d.hours())}h",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Text("The days before the deadline were never given the hours: once sleep, " +
                     "classes, travel, upkeep and the slack reserve are taken out, " +
                     "${"%.1f".format(c.allocatedH())}h is all there was to give.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp))
            }
            val fixes = remember(c.goalId(), vm.revision, c.missingH()) {
                Feasibility.remediesForGap(vm.config, vm.today, c.goalId(), c.missingH())
            }
            if (fixes.isEmpty()) {
                Hint("Nothing the planner tried recovered the hours on its own. The gap needs more " +
                     "than one change — try the deadline and the scope together.")
            } else {
                Spacer(Modifier.height(10.dp))
                Text("What would fix it", style = MaterialTheme.typography.titleMedium)
                fixes.forEach { RemedyRow(it, vm) }
                Hint("These are measured against the capacity model, so treat their hours as a " +
                     "direction rather than a promise — press Build the plan to see what the " +
                     "arranged days make of a change.")
            }
        }
    }
}

/* ------------------------------------------------------ where the plan starts */

/**
 * Asked every time a plan is built, because the right answer changes: most
 * days it is today, but a plan made on Thursday for a term that starts on
 * Saturday should not spend two days on work that cannot begin yet.
 */
@Composable
private fun StartDateDialog(vm: ChronosViewModel, onDismiss: () -> Unit, onBuild: (java.time.LocalDate, String?) -> Unit) {
    var start by remember { mutableStateOf(vm.today) }
    // The hour on the first day (your request of 22 Oct 2026): today it starts at the next quarter hour.
    fun nextQuarter(): String {
        val n = java.time.LocalTime.now()
        val m = ((n.hour * 60 + n.minute + 14) / 15 * 15).coerceAtMost(23 * 60 + 45)
        return "%02d:%02d".format(m / 60, m % 60)
    }
    var fromHour by remember { mutableStateOf(true) }
    var time by remember { mutableStateOf(nextQuarter()) }
    val tooEarly = start.isBefore(vm.logDay)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start the plan from") },
        text = {
            Column {
                DateField("First day of the plan", start) { start = it }
                if (tooEarly) Hint("That day has already passed. The plan will start today.")
                else if (start.isAfter(vm.logDay))
                    Hint("Nothing is scheduled before this date, and days before it are not closed out.")
                else Hint("The plan starts today.")
                Toggle("From an hour on that day", fromHour,
                    if (fromHour) "What was arranged before this hour today stays as it was; only the rest of the day is arranged again. Later days are planned whole." else "The whole first day is planned.") { fromHour = it }
                if (fromHour) TimeField("From", time) { time = it }
            }
        },
        confirmButton = {
            TextButton(onClick = { onBuild(if (tooEarly) vm.logDay else start, if (fromHour) time else null) }) { Text("Build") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/* ------------------------------------------------ what the coming days cannot hold */

/**
 * Everything the coming week's arranged days left out, and everything they
 * had to move or shrink to fit the rest — read from the days themselves, each
 * with its reason and what would fix it. Nothing is dropped without a line here.
 */
@Composable
private fun UpcomingPanel(vm: ChronosViewModel) {
    val days = vm.upcoming
    if (days.isEmpty()) return
    val anyMissing = days.any { it.missing().isNotEmpty() }
    Panel(if (anyMissing) "Not everything fits in the coming days" else "Worth knowing about the coming days",
          if (anyMissing) Palette.Brick else Palette.Sand) {
        Hint("Read from the arranged days themselves. Each line says why, and what would fix it.")
        days.forEach { d ->
            Text(dateText(d.date()), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 10.dp))
            d.missing().forEach { m -> MissingRow(m.name() ?: "", m.reason() ?: "") }
            d.notes().forEach { note ->
                Text(note, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 2.dp))
            }
        }
    }
}

/* ------------------------------------------------- what gave way for deadlines */

/**
 * What the plan took from the activities you allowed it to cut, day by day.
 * Only as much as the deadlines needed; the plan is re-solved every night, so
 * a cut that stops being necessary disappears on its own.
 */
@Composable
private fun CutsPanel(cuts: List<Sacrifice.Cut>) {
    if (cuts.isEmpty()) return
    Panel("Given up for the deadlines", Palette.Sand) {
        Hint("A dated goal did not fit, so the plan took time from activities you marked as " +
             "allowed to be cut — only as much as the deadlines needed, latest days first.")
        Spacer(Modifier.height(6.dp))
        cuts.groupBy { it.date() }.forEach { (date, onDay) ->
            Text(dateText(date), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp))
            onDay.forEach { c ->
                Row(Modifier.fillMaxWidth().padding(start = 8.dp, top = 2.dp)) {
                    Text(c.name() ?: "", style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f))
                    Text(if (c.toMin() <= 0) "left out" else "${c.fromMin()} → ${c.toMin()} min",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
