package com.chronos.ui.setup

import com.chronos.ui.*
import com.chronos.ui.common.*
import com.chronos.ui.horizon.*
import com.chronos.ui.day.*
import com.chronos.ui.log.*
import com.chronos.ui.navigator.*

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.chronos.engine.Slots
import com.chronos.model.Activity
import com.chronos.model.Commitment
import com.chronos.model.Enums
import com.chronos.model.Goal
import com.chronos.validate.Readiness
import java.util.UUID

/**
 * Setup is a wizard: one section at a time, in the order in which each answer
 * unlocks the next. Each step has its own accent colour, and the step bar shows
 * how many blocking answers are still missing in each.
 */
private enum class Step(val title: String, val wheres: Set<String>, private val tint: () -> Color, val blurb: String) {
    BodyClock("Body clock", setOf("bodyClock"), { Palette.Steel },
        "Four numbers define the capacity curve. Rough answers are fine — a week of check-ins " +
        "replaces them with fitted values."),
    Scheduler("Scheduler", setOf("solver"), { Palette.Plum },
        "Safe fatigue line, slack, pessimism and the two penalties. The table at the bottom tells " +
        "you which one to move when a plan feels wrong."),
    Board("Goals and activities", BOARD_WHERES, { Palette.Indigo },
        "Your goals with the work that serves them, then upkeep and commitments. Tap anything to open " +
        "it; a red mark means something in it is still missing."),
    Calendar("Calendar", setOf("calendar"), { Palette.Teal },
        "Optional. Pick a calendar and the next seven days are written into it, and rewritten " +
        "after every rebuild.");

    /** Its colour, read when drawn, so it follows the light or dark theme. */
    val accent: Color get() = tint()
}

@Composable
fun SetupScreen(vm: ChronosViewModel, goToPriorities: () -> Unit) {
    val cfg = vm.config
    val step = Step.entries[vm.setupStep.coerceIn(0, Step.entries.lastIndex)]
    val blocking = vm.issues.filter { it.severity() == Readiness.Severity.BLOCKING }
    val warnings = vm.issues.filter { it.severity() == Readiness.Severity.WARNING }
    val stepWheres = Step.entries.flatMap { it.wheres }.toSet()
    val missingPerStep = Step.entries.associateWith { st -> blocking.count { it.where() in st.wheres } }

    LanguagePanel(vm)

    ScreenHeader(
        "Setup",
        "Nothing is filled in for you, because a default body clock or a default deadline would " +
        "be a guess pretending to be data. The planner stays locked until everything below is answered."
    )

    ReadinessSummary(blocking, warnings, stepWheres, goToPriorities)
    StepBar(step, missingPerStep) { vm.setupStep = it.ordinal }

    Text(step.blurb, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))

    val here = blocking.filter { it.where() in step.wheres }
    if (here.isNotEmpty()) {
        Verdict(Tone.Bad, "${here.size} still missing here",
            here.take(8).joinToString("\n") { "· ${it.what()}" } +
            if (here.size > 8) "\n· and ${here.size - 8} more" else "")
    }
    warnings.filter { it.where() in step.wheres }.forEach { Verdict(Tone.Warn, "Worth a look", it.what()) }

    // Config is a mutable Java object that keeps the same lists across edits,
    // so each panel calls vm.observeConfig() inside its content lambda; that
    // read is what makes the panel redraw after an edit.
    when (step) {
        Step.BodyClock -> BodyClockPanel(vm, step.accent)
        Step.Scheduler -> SolverPanel(vm, step.accent)
        Step.Board -> SetupBoard(vm, (blocking + warnings).filter { it.where() in step.wheres })
        Step.Calendar -> CalendarPanel(vm, step.accent)
    }

    WizardFooter(step,
        onBack = { vm.setupStep = step.ordinal - 1 },
        onNext = { vm.setupStep = step.ordinal + 1 },
        onFinish = goToPriorities)
}

@Composable
private fun LanguagePanel(vm: ChronosViewModel) {
    Panel("Language", Palette.Steel) {
        Row(Modifier.fillMaxWidth()) {
            Lang.entries.forEach { l ->
                val selected = vm.lang == l
                OutlinedButton(
                    onClick = { vm.setLanguage(l) },
                    enabled = !selected,
                    modifier = Modifier.weight(1f).padding(end = 6.dp)
                ) { Text(l.label) }
            }
        }
        Hint("The interface only. Your own words — goal names, activity names — are never translated.")
        // Light or dark (29 Oct 2026).
        Text(tr("Appearance"), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 10.dp))
        FlowRowSimple {
            com.chronos.ui.common.ThemeMode.entries.forEach { m ->
                Pill(tr(m.label), vm.themeMode == m) { vm.setTheme(m) }
            }
        }
    }
}

/* -------------------------------------------------------- wizard chrome */

@Composable
private fun ReadinessSummary(blocking: List<Readiness.Issue>, warnings: List<Readiness.Issue>,
                             stepWheres: Set<String>, goToPriorities: () -> Unit) {
    if (blocking.isEmpty()) {
        Verdict(Tone.Good, "Ready to solve",
            "Everything the model needs is answered." +
            if (warnings.isEmpty()) "" else " There are still ${warnings.size} things worth a look.")
    } else {
        Verdict(Tone.Bad, "${blocking.size} things still missing",
            "The solver is locked until these are answered. The step bar below shows where.")
    }
    // Issues that belong to no step: priorities, and the capacity audit.
    val elsewhere = blocking.filter { it.where() !in stepWheres }
    if (elsewhere.isNotEmpty()) {
        Panel {
            elsewhere.forEach {
                Text("· ${it.what()}", style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 2.dp))
            }
            if (elsewhere.any { it.where() == "weights" }) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = goToPriorities) { Text("Set priorities") }
            }
        }
    }
    warnings.filter { it.where() !in stepWheres }.forEach { Verdict(Tone.Warn, "Worth a look", it.what()) }
}

/** Numbered, coloured chips — one per step, with the count of missing answers. */
@Composable
private fun StepBar(current: Step, missing: Map<Step, Int>, onPick: (Step) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Step.entries.forEachIndexed { i, st ->
            val selected = st == current
            val left = missing[st] ?: 0
            val bg = if (selected) st.accent else st.accent.copy(alpha = 0.10f)
            val fg = if (selected) Palette.OnFill else st.accent
            Row(
                Modifier.padding(end = 6.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(bg)
                    .border(BorderStroke(1.dp, st.accent.copy(alpha = if (selected) 1f else 0.45f)),
                        RoundedCornerShape(16.dp))
                    .clickable { onPick(st) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${i + 1}", style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold, color = fg)
                Spacer(Modifier.width(6.dp))
                Text(st.title, style = MaterialTheme.typography.labelSmall, color = fg)
                Spacer(Modifier.width(6.dp))
                Text(if (left == 0) "✓" else "$left",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (left == 0) fg else if (selected) Palette.OnFill else Palette.Brick,
                    fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun WizardFooter(step: Step, onBack: () -> Unit, onNext: () -> Unit, onFinish: () -> Unit) {
    val last = step.ordinal == Step.entries.lastIndex
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = onBack, enabled = step.ordinal > 0) { Text("Back") }
        Spacer(Modifier.weight(1f))
        Text("${step.ordinal + 1} / ${Step.entries.size}", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Button(
            onClick = if (last) onFinish else onNext,
            colors = ButtonDefaults.buttonColors(containerColor = step.accent)
        ) { Text(if (last) "Set priorities" else "Next: ${Step.entries[step.ordinal + 1].title}") }
    }
}

/* ------------------------------------------- windows (the nap uses them) */

@Composable
private fun WindowsEditor(vm: ChronosViewModel, windows: List<Activity.Window>,
                          of: (com.chronos.model.Config) -> MutableList<Activity.Window>) {
    // [of] finds the same list in the configuration being edited: an edit works on a fresh copy (29 Oct 2026).
    vm.observeConfig()
    Column(Modifier.padding(top = 10.dp)) {
        Text("Allowed hours", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        windows.forEachIndexed { i, w ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                TimeField("From", w.from, Modifier.weight(1f)) { v -> vm.edit { c -> of(c)[i].from = v } }
                Spacer(Modifier.width(8.dp))
                TimeField("To", w.to, Modifier.weight(1f)) { v -> vm.edit { c -> of(c)[i].to = v } }
                TextButton(onClick = { vm.edit { c -> of(c).removeAt(i) } }) { Text("×") }
            }
            if (Slots.span(w.from, w.to) == null)
                Text("This window ends before it starts, so it is ignored until you fix it. " +
                     "To run until midnight, end it at 00:00.",
                    style = MaterialTheme.typography.labelSmall, color = Palette.Brick)
        }
        TextButton(onClick = {
            vm.edit { c -> of(c).add(Activity.Window("08:00", "12:00")) }
        }) { Text("Add a window") }
        Hint("Several windows are allowed. A morning and an afternoon slot is common.")
    }
}

/* ------------------------------------------------------------ body clock */

@Composable
private fun BodyClockPanel(vm: ChronosViewModel, accent: Color) {
    val p = vm.config.params
    Panel("Your body clock", accent) {
        vm.observeConfig()
        Hint("Four numbers define the capacity curve. Answer them roughly now — once you have a " +
             "week of check-ins the model fits them from your own ratings and these become a " +
             "starting point rather than a guess you are stuck with.")
        Spacer(Modifier.height(8.dp))
        Row {
            TimeField("Sleep starts", p.sleepStart, Modifier.weight(1f)) { v ->
                vm.edit { it.params.sleepStart = v } }
            Spacer(Modifier.width(8.dp))
            TimeField("Sleep ends", p.sleepEnd, Modifier.weight(1f)) { v ->
                vm.edit { it.params.sleepEnd = v } }
        }
        TimeField("Your sharpest hour", p.phi?.let { Slots.formatMinutes(it) }) { v ->
            val parts = v.split(":")
            vm.edit { c -> c.params.phi = parts[0].toInt() * 60 + parts[1].toInt() }
        }
        Hint("When you feel sharpest, ignoring how long you have been awake.")

        NumberField("Baseline capacity", p.a0,
            "Your average capability on an ordinary rested day. Raising it lifts the whole curve.") { v ->
            vm.edit { it.params.a0 = v } }
        NumberField("How much time of day matters", p.aC,
            "High if mornings and evenings feel very different. Near zero if every hour is alike.") { v ->
            vm.edit { it.params.aC = v } }
        NumberField("How much being awake costs", p.aH,
            "High if you fade late in the day regardless of workload.") { v ->
            vm.edit { it.params.aH = v } }
        NumberField("How much work itself drains", p.aF,
            "High if two hard sessions back to back leave you useless.") { v ->
            vm.edit { it.params.aF = v } }
        NumberField("How much a tired body costs the mind", p.aP,
            "How far physical fatigue, once past the line below, lowers what you can do with your head.") { v ->
            vm.edit { it.params.aP = v } }
        NumberField("Physical fatigue line", p.pTheta,
            "Below it a tired body does not slow your thinking; above it, it does (0 to 1). Your log refines it.") { v ->
            vm.edit { it.params.pTheta = v } }
        Hint("The last two are easy to confuse. If you are tired by evening even on a day you did " +
             "nothing, the third is high. If only hard work does it, the fourth is.")
    }
    NapPanel(vm, accent)
}

/**
 * The afternoon nap: an activity in every respect the planner cares about —
 * days, a window or a fixed time, a length, how much it restores — but asked
 * about here, next to the night's sleep, because that is where it belongs.
 */
@Composable
private fun NapPanel(vm: ChronosViewModel, accent: Color) {
    val lang = LocalLang.current
    Panel("Afternoon nap", accent) {
        vm.observeConfig()
        val index = vm.config.activities.indexOfFirst { it.nap }
        val nap = vm.config.activities.getOrNull(index)
        Toggle("I take a nap", nap != null,
            "Planned like an activity: on the days you choose, inside its window or at a fixed time.") { on ->
            vm.edit { c ->
                if (on && c.activities.none { it.nap }) c.activities.add(Activity().apply {
                    id = UUID.randomUUID().toString()
                    name = translate(lang, "Nap")
                    kind = Enums.ActivityKind.MAINTENANCE
                    this.nap = true
                    days.addAll(0..6)
                    minBlock = 20; maxBlock = 40
                    load = 0.0; recovery = 0.6
                    windows.add(Activity.Window("13:00", "16:00"))
                })
                if (!on) c.activities.removeIf { it.nap }
            }
        }
        if (nap == null) return@Panel

        Text("Days", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        val pinned = nap.pins.isNotEmpty()
        val pinAt = nap.pins.firstOrNull()?.start ?: "14:00"
        DayPicker(nap.days.toSet()) { days ->
            vm.edit { c ->
                val a = c.activities[index]
                a.days.clear(); a.days.addAll(days)
                // A fixed time follows the days: one pin per day it happens.
                if (a.pins.isNotEmpty()) {
                    a.pins.clear()
                    days.sorted().forEach { d -> a.pins.add(Activity.Pin(d, pinAt)) }
                }
            }
        }
        Choice("How it is placed", pinned, listOf(
            false to "The planner picks the time in its window",
            true to "At a fixed time"
        )) { p ->
            vm.edit { c ->
                val a = c.activities[index]
                a.pins.clear()
                if (p) a.days.sorted().forEach { d -> a.pins.add(Activity.Pin(d, pinAt)) }
            }
        }
        if (pinned) {
            TimeField("At", pinAt) { t ->
                vm.edit { c ->
                    val a = c.activities[index]
                    a.pins.clear()
                    a.days.sorted().forEach { d -> a.pins.add(Activity.Pin(d, t)) }
                }
            }
        } else {
            WindowsEditor(vm, nap.windows) { c -> c.activities[index].windows }
        }
        Row {
            IntField("Shortest (min)", nap.minBlock, modifier = Modifier.weight(1f)) { v ->
                vm.edit { it.activities[index].minBlock = v } }
            Spacer(Modifier.width(8.dp))
            IntField("Longest (min)", nap.maxBlock, modifier = Modifier.weight(1f)) { v ->
                vm.edit { it.activities[index].maxBlock = v } }
        }
        Hint("The plan books the shortest length, so the nap never crowds out the day; sleep " +
             "longer when you can.")
        NumberField("Physical recovery per hour", nap.physRecovery,
            "How much a nap rests your body.") { v ->
            vm.edit { it.activities[index].physRecovery = v } }
        NumberField("Recovery per hour", nap.recovery,
            "How much of the day's fatigue it takes away. A good nap is among the most restoring " +
            "things you do; 0.6 is a fair start, and your Log refines it.") { v ->
            vm.edit { it.activities[index].recovery = v } }
    }
}

/* ---------------------------------------------------------------- solver */

@Composable
private fun SolverPanel(vm: ChronosViewModel, accent: Color) {
    val p = vm.config.params
    Panel("How the scheduler behaves", accent) {
        vm.observeConfig()
        NumberField("Safe fatigue line", p.fSafe,
            "Below this, fatigue is free. Above it, every extra unit is penalised.") { v ->
            vm.edit { it.params.fSafe = v } }
        NumberField("Recovery while resting", p.idleRecovery,
            "How fast doing nothing restores you. Too low and the planner shoves work into the evening.") { v ->
            vm.edit { it.params.idleRecovery = v } }
        NumberField("Safe physical fatigue line", p.fSafeP,
            "Like the line above, for the body: below it physical fatigue is free.") { v ->
            vm.edit { it.params.fSafeP = v } }
        NumberField("Physical recovery while resting", p.idleRecoveryP,
            "How fast doing nothing rests your body.") { v ->
            vm.edit { it.params.idleRecoveryP = v } }
        NumberField("How much a tired mind makes effort cost", p.kMP,
            "0.3 means that with your mind fully tired, physical effort tires you 30% more.") { v ->
            vm.edit { it.params.kMP = v } }
        NumberField("Slack kept free", p.slackRatio,
            "Share of open time never scheduled. 0.10 means one hour in ten stays empty on purpose.") { v ->
            vm.edit { it.params.slackRatio = v } }
        NumberField("Pessimism", p.lcbBeta,
            "How far below your estimate the planner assumes you will perform. 0 trusts you fully.") { v ->
            vm.edit { it.params.lcbBeta = v } }
        NumberField("Cost of starting a block", p.switchPenalty,
            "Raise it for fewer, longer sessions.") { v ->
            vm.edit { it.params.switchPenalty = v } }
        NumberField("Cost of overworking", p.fatiguePenalty,
            "How hard the planner avoids pushing past the safe line.") { v ->
            vm.edit { it.params.fatiguePenalty = v } }
        // Not clamped while typing: coerceIn turned the first digit of "14" into
        // 7, which the field then wrote back, so a two-digit horizon starting
        // below 7 could never be entered. An out-of-range answer is reported by
        // Readiness like any other, rather than silently corrected.
        IntField("Planning horizon (days)", p.horizonDays,
            "Between 7 and 90 days. Longer costs nothing to solve; it only means the plan " +
            "reaches further than your estimates can be trusted.") { v ->
            vm.edit { it.params.horizonDays = v } }

        Spacer(Modifier.height(14.dp))
        Text("If the plans feel wrong, start here", style = MaterialTheme.typography.titleMedium)
        listOf(
            "Days feel too packed" to "Raise cost of overworking, or lower the safe fatigue line",
            "Days feel too empty" to "Lower pessimism, or lower slack kept free",
            "Too many short scattered sessions" to "Raise cost of starting a block, or raise each activity's shortest block",
            "Everything crowds into the morning" to "Lower how much time of day matters, or move your sharpest hour later",
            "Evenings look unusable" to "Lower how much being awake costs",
            "Mornings sit empty while work piles up at night" to "Raise recovery while resting"
        ).forEach { (symptom, fix) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(symptom, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Text(fix, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1.2f))
            }
        }
    }
}

/* -------------------------------------------------------------- calendar */

@Composable
private fun CalendarPanel(vm: ChronosViewModel, accent: Color) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var granted by remember {
        mutableStateOf(androidx.core.content.ContextCompat.checkSelfPermission(
            context, Manifest.permission.WRITE_CALENDAR) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED)
    }
    LaunchedEffect(granted) { if (granted && vm.calendars.isEmpty()) vm.refreshCalendars() }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        granted = result.values.all { it }
        if (granted) vm.refreshCalendars()
    }

    Panel("Google Calendar", accent) {
        Hint("Seven rolling days are written and rewritten as the plan changes. A month of guessed " +
             "blocks would only clutter a calendar you also live in.")
        Spacer(Modifier.height(10.dp))

        if (!granted && vm.calendars.isEmpty()) {
            Button(onClick = {
                launcher.launch(arrayOf(Manifest.permission.READ_CALENDAR,
                                        Manifest.permission.WRITE_CALENDAR))
            }) { Text("Allow calendar access") }
            Hint("Asked only now, not on first launch. Nothing is read or written until you pick a calendar.")
            return@Panel
        }

        vm.calendars.filter { it.writable() }.forEach { c ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                RadioButton(selected = vm.calendarId == c.id, onClick = { vm.chooseCalendar(c.id) })
                Column(Modifier.padding(start = 6.dp)) {
                    Text(c.displayName() ?: "Calendar", style = MaterialTheme.typography.bodySmall)
                    Text(
                        if (c.syncsToGoogle()) "${c.accountName()} — reaches google.com"
                        else "${c.accountName()} — stays on this phone",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Hint("Only a Google account calendar reaches google.com; a local one shows on the phone " +
             "alone. The app cannot create a new calendar inside a Google account — only Google's " +
             "own sync adapter may do that — so pick an existing one to write into.")

        Spacer(Modifier.height(10.dp))
        Row {
            OutlinedButton(onClick = { vm.refreshCalendars() }) { Text("Refresh") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { vm.createLocalCalendar() }) { Text("Phone-only calendar") }
        }

        Spacer(Modifier.height(12.dp))
        Toggle("Include meals, travel and upkeep", vm.includeUpkeepInCalendar,
            "Off sends only goal work, which keeps a shared calendar readable.") {
            vm.includeUpkeepInCalendar = it
        }
        Toggle("Put sleep in the calendar", vm.sleepInCalendar,
            "The night's sleep, and the nap if you take one, as blocks — so the calendar shows " +
            "the whole day.") {
            vm.sleepInCalendar = it
        }
        IntField("Reminder, minutes before", vm.reminderMinutes,
            "The calendar app fires these itself, so the phone needs no alarm permission. " +
            "Leave it empty for no reminder.") { v -> vm.reminderMinutes = v }

        Spacer(Modifier.height(12.dp))
        Row {
            Button(onClick = { vm.pushToCalendar() }, enabled = vm.calendarId != null && !vm.busy) {
                Text("Send seven days")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { vm.clearCalendar() }, enabled = vm.calendarId != null) {
                Text("Remove mine")
            }
        }
        Hint("Sending twice never duplicates: this app's own events for those days are replaced, " +
             "and anything you put in that calendar yourself is left untouched.")
    }
}
