package com.chronos.ui.log

import com.chronos.ui.*
import com.chronos.ui.common.*
import com.chronos.ui.setup.*
import com.chronos.ui.horizon.*
import com.chronos.ui.day.*
import com.chronos.ui.navigator.*

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.chronos.data.ChronosRepository
import com.chronos.engine.Energy
import com.chronos.engine.Slots
import com.chronos.model.Activity
import com.chronos.model.Block
import com.chronos.model.Commitment
import com.chronos.model.Config
import com.chronos.model.Enums
import com.chronos.model.Goal
import com.chronos.model.LogEntry
import java.time.LocalDate
import java.util.UUID

/*
 * The day, reported back.
 *
 * Two lists instead of a free-form form. The first is every waking hour with
 * how sharp you were and why; the second is every block the plan gave the day,
 * each with its own box for what really happened — including, when a block
 * was skipped, what was done in its place. Anything done that no block covered
 * goes in a third list. Nothing here is applied on the spot: when the day
 * closes (see AutoPlanner) the log is read once, the model is refitted, and
 * the plan is rebuilt from the next day. Days already closed can still be
 * filled in; those entries wait for the Apply button or the next close.
 *
 * Every entry has a stable id — the hour, or the block's activity and slot —
 * so changing an answer replaces it instead of adding a second one.
 */

/** What the whole day was like around you. Outside causes: they keep the whole day out of the body-clock fit. */
private val DAY_CIRCUMSTANCES = listOf("Poor sleep", "Unwell", "Stressed", "Exam or deadline day", "Travel day",
    "Social day", "Family matter", "Holiday or day off")

/** How the day felt. Also outside the body clock, so they keep the day out of the fit too. */
private val DAY_FEELINGS = listOf("Anxious", "Low mood", "Upset or angry", "Lonely", "Bad news", "Good news",
    "Happy or excited")

/** Every day-wide tag. Kept apart from the hour tags: a day tag is found in any rated hour's tags. */
private val DAY_CAUSES = DAY_CIRCUMSTANCES + DAY_FEELINGS

/** Outside causes for one hour. The rating is kept, but not used to fit your curve. */
private val HOUR_OUTSIDE = listOf("Caffeine", "Skipped a meal", "Noisy or interrupted", "Hungry",
    "Too hot or cold", "On the phone or social media", "Pain or discomfort")

/** How one hour felt. Outside the body clock as well: kept, but not used to fit the curve. */
private val HOUR_FEELINGS = listOf("Worried about something", "Frustrated", "Bored", "Restless",
    "Upsetting conversation", "Good conversation", "Excited about something")

/** Causes inside the body. These are what the curve already models, so the rating is kept in the fit. */
private val HOUR_INSIDE = listOf("Sleepy", "Mentally tired", "Physically tired", "After a meal", "After exercise")

/** What a night can be like. */
private val SLEEP_TAGS = listOf("Hard to fall asleep", "Woke up often", "Woke too early", "Bad dreams",
    "Vivid dreams", "Noise or light", "Too hot or cold", "Caffeine late in the day", "Screen before bed",
    "Racing thoughts", "Pain or discomfort", "Slept in", "Napped during the day")

/** Things that went wrong in a session. The first three are outside causes. */
private val SESSION_TAGS = listOf("Noisy or interrupted", "Unwell", "Stressed", "Late start")

private const val SKIPPED = "Skipped"
private const val NOTHING_INSTEAD = "Nothing instead"

private fun energyId(day: LocalDate, hour: Int) = "energy|$day|$hour"
private fun sessionId(day: LocalDate, actId: String, slot: Int) = "session|$day|$actId|$slot"
private fun insteadPrefix(day: LocalDate, actId: String, slot: Int) = "instead|$day|$actId|$slot|"
private fun extraPrefix(day: LocalDate) = "extra|$day|"
private fun tripId(day: LocalDate, comId: String, slot: Int) = "trip|$day|$comId|$slot"
private fun duringPrefix(day: LocalDate, comId: String, slot: Int) = "during|$day|$comId|$slot|"

@Composable
fun LogScreen(vm: ChronosViewModel) {
    val rev = vm.revision
    // The day on screen. Starts on the day you are in, and can go back a month.
    var day by remember(vm.logDay) { mutableStateOf(vm.logDay) }
    val earliest = vm.logDay.minusDays(ChronosRepository.PAST_DAYS_KEPT.toLong())
    val isPast = day.isBefore(vm.logDay)

    ScreenHeader(
        "Log",
        "Tell the model how the day really went: your energy hour by hour, and what happened to " +
        "each block of the plan. Nothing needs applying for today — when the day closes, the model " +
        "learns from this, and tomorrow's plan is rebuilt and sent to your calendar."
    )

    QuickLogPanel(vm)
    if (!isPast) WellbeingPanel(vm)
    LogDayPicker(vm, day, earliest) { day = it }
    if (isPast) PastDayPanel(vm, day)
    DayHistoryPanel(vm, day)
    LateEntriesPanel(vm)
    if (!isPast) ClosingPanel(vm)

    val bundle = vm.bundle
    LaunchedEffect(day, bundle, rev) { vm.requestDay(day) }
    val result = if (bundle == null) null else vm.dayResult(day)
    val noPlan = vm.noPlanKept[day] == true

    SleepPanel(vm, day)
    EnergyPanel(vm, day, result?.blocks())
    ActivityPanel(vm, day, bundle != null, noPlan, result?.blocks())
    DayMatchPanel(vm, day, result?.blocks())
    ExtraPanel(vm, day)
    if (!isPast) NightlyPanel(vm)
    RemindersPanel(vm)
    ExportPanel(vm)
}

/* ------------------------------------------------------- how you are today */

/**
 * The morning and evening check-in, this week's load, and what they add up
 * to. A clear case turns the day into a rest day by itself (when allowed); a
 * borderline one offers it; either way the reasons are shown, so the app's
 * judgement can be checked against your own.
 */
@Composable
private fun WellbeingPanel(vm: ChronosViewModel) {
    val today = vm.logDay
    val morning = vm.logs.firstOrNull { it.id == "morning|$today" }
    val evening = vm.logs.firstOrNull { it.id == "evening|$today" }
    val verdict = vm.restVerdict
    val lang = LocalLang.current

    val accent = when {
        vm.restToday -> Palette.Moss
        verdict?.level() == com.chronos.engine.RestAdvisor.Level.REST -> Palette.Brick
        verdict?.level() == com.chronos.engine.RestAdvisor.Level.CONSIDER -> Palette.Sand
        else -> Palette.Steel
    }
    Panel("How are you today?", accent) {
        if (vm.restToday) {
            Text("Today is a rest day.", style = MaterialTheme.typography.titleMedium, color = Palette.Moss)
            Hint("Goal work is paused; upkeep and your rest activities are planned. Tomorrow morning's " +
                 "check-in decides whether to come back, and the return is gradual — deadlines are " +
                 "caught up over the next days, not all at once.")
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = { vm.chooseRestToday(false) }, enabled = !vm.busy) {
                Text("Back to the normal plan")
            }
            Spacer(Modifier.height(10.dp))
            HorizontalDivider()
        }

        CheckinBox(vm, "morning", "Morning", morning,
            com.chronos.engine.RestAdvisor.SEVERE + com.chronos.engine.RestAdvisor.MILD, withHeavy = false)

        verdict?.let { v ->
            Spacer(Modifier.height(8.dp))
            val title = when (v.level()) {
                com.chronos.engine.RestAdvisor.Level.REST -> "The signs say rest"
                com.chronos.engine.RestAdvisor.Level.CONSIDER -> "Worth considering a rest day"
                else -> "Nothing says you need to stop"
            }
            Text(title, style = MaterialTheme.typography.titleMedium, color = accent)
            v.reasons().forEach { r ->
                Text("· " + translate(lang, r.why()), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!vm.restToday && v.level() != com.chronos.engine.RestAdvisor.Level.NORMAL) {
                Spacer(Modifier.height(6.dp))
                Button(onClick = { vm.chooseRestToday(true) }, enabled = !vm.busy) { Text("Make today a rest day") }
            }
        }
        if (!vm.restToday && verdict?.level() != com.chronos.engine.RestAdvisor.Level.CONSIDER &&
            verdict?.level() != com.chronos.engine.RestAdvisor.Level.REST) {
            TextButton(onClick = { vm.chooseRestToday(true) }, enabled = !vm.busy) {
                Text("I need a rest day anyway")
            }
        }

        Spacer(Modifier.height(10.dp))
        HorizontalDivider()
        WeeklyLoadRow(vm)

        Spacer(Modifier.height(10.dp))
        HorizontalDivider()
        CheckinBox(vm, "evening", "Evening", evening, com.chronos.engine.RestAdvisor.EVENING_SIGNS,
            withHeavy = true)
        DayTypeRow(vm, today)

        Spacer(Modifier.height(6.dp))
        Toggle("Switch to rest by itself when the signs are clear", vm.autoRest,
            "Off, it only suggests. Either way you can take the day back.") { vm.autoRest = it }
    }
}

/**
 * The one field the Navigator adds to the check-in: what kind of day this
 * was. The Navigator's suggestion is shown ticked until you choose; a tap
 * saves. An unusual day is kept but not learned from — by Chronos's nightly
 * learning as well as the Navigator's — and that can be decided for any day
 * the Log shows, not only today.
 */
@Composable
private fun DayTypeRow(vm: ChronosViewModel, day: LocalDate) {
    val chosen = vm.dayTypes[day]
    val suggested = vm.suggestedDayType(day)
    val current = chosen?.first ?: suggested
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text("What kind of day was it?", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            listOf("IDEAL" to "Ideal", "BUSY" to "Busy", "STRESS" to "Stressful").forEach { (k, label) ->
                Box(Modifier.weight(1f)) { Pill(tr(label), current == k) { vm.saveDayType(k, chosen?.second ?: false, day) } }
            }
        }
        if (chosen == null)
            Hint(tr("Suggested from your check-in and today's classes; tap to confirm or change."))
        Toggle("Unusual day — do not learn from it", chosen?.second ?: false,
            if (vm.isClosed(day)) "Already learned from: switching this takes back (or gives back) what this day taught, " +
                "and the nights after it are learned again." else null) { v -> vm.saveDayType(current, v, day) }
    }
}

/**
 * A past day's own status: its check-ins, what kind of day it was, and whether
 * the model should learn from it at all. Today's live in "How are you today?".
 */
@Composable
private fun PastDayPanel(vm: ChronosViewModel, day: LocalDate) {
    val morning = vm.logs.firstOrNull { it.id == "morning|$day" }
    val evening = vm.logs.firstOrNull { it.id == "evening|$day" }
    val unusual = vm.dayTypes[day]?.second == true
    Panel("How the day was", if (unusual) Palette.Sand else Palette.Steel) {
        if (unusual) Hint("Marked unusual: kept in the log, not learned from.")
        CheckinBox(vm, "morning", "Morning", morning,
            com.chronos.engine.RestAdvisor.SEVERE + com.chronos.engine.RestAdvisor.MILD, withHeavy = false, day = day)
        HorizontalDivider()
        CheckinBox(vm, "evening", "Evening", evening, com.chronos.engine.RestAdvisor.EVENING_SIGNS,
            withHeavy = true, day = day)
        DayTypeRow(vm, day)
    }
}

@Composable
private fun CheckinBox(vm: ChronosViewModel, kind: String, title: String, saved: LogEntry?,
                       signs: List<String>, withHeavy: Boolean, day: LocalDate? = null) {
    var energy by remember(saved?.ts) { mutableStateOf(saved?.energy) }
    var heavyMind by remember(saved?.ts) { mutableStateOf(saved?.mentalAfter ?: saved?.fatigueAfter) }
    var heavyBody by remember(saved?.ts) { mutableStateOf(saved?.physicalAfter ?: saved?.fatigueAfter) }
    var chosen by remember(saved?.ts) { mutableStateOf(saved?.tags.orEmpty()) }
    // Unanswered, each opens at its own time of day: the morning one until two
    // in the afternoon, the evening one from five. Either opens with a tap.
    val hour = java.time.LocalTime.now().hour
    var open by remember(kind, day) {
        mutableStateOf(day == null && saved == null && (if (kind == "morning") hour < 14 else hour >= 17))
    }

    Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (saved != null)
                Text("Energy ${saved.energy}/5" +
                     tiredText(saved, " · day felt ") +
                     (if (saved.tags.isNullOrEmpty()) "" else " · ${saved.tags.size} signs"),
                    style = MaterialTheme.typography.labelSmall, color = Palette.Moss)
            else Text("Not answered yet", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(if (open) "▲" else "▼", style = MaterialTheme.typography.labelSmall)
    }
    if (!open) return
    Text("How much energy do you have?", style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        (1..5).forEach { n -> Box(Modifier.weight(1f)) { Pill(n.toString(), energy == n) { energy = n } } }
    }
    Hint("1 empty  to  5 full")
    if (withHeavy) {
        Text("How heavy did today feel for your mind?", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            (1..5).forEach { n -> Box(Modifier.weight(1f)) { Pill(n.toString(), heavyMind == n) { heavyMind = n } } }
        }
        Text("And for your body?", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            (1..5).forEach { n -> Box(Modifier.weight(1f)) { Pill(n.toString(), heavyBody == n) { heavyBody = n } } }
        }
        Hint("1 light  to  5 crushing")
    }
    Text("Is any of this true?", style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
    TagPicker(signs, chosen) { chosen = it }
    Spacer(Modifier.height(6.dp))
    Button(onClick = {
        val e = energy ?: return@Button
        vm.saveCheckin(kind, e, if (withHeavy) heavyMind else null, if (withHeavy) heavyBody else null, chosen,
            day ?: vm.logDay)
        open = false
    }, enabled = energy != null && !vm.busy) { Text("Save") }
}

@Composable
private fun WeeklyLoadRow(vm: ChronosViewModel) {
    val w = vm.weekly ?: return
    Text("This week's load", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
    val ratio = w.ratio()
    if (ratio != null) {
        Meter((ratio / 1.6).toFloat(), when {
            ratio >= 1.5 -> Palette.Brick; ratio >= 1.3 -> Palette.Sand; else -> Palette.Moss })
        Hint("${"%.0f".format(ratio * 100)}% of a normal week (${"%.1f".format(w.weekLoadH())} against " +
             "${"%.1f".format(w.typicalWeekLoadH())} load-hours). Above 130% the week is heavy; above " +
             "150% it is the kind that ends in a crash.")
    } else {
        Hint("${"%.1f".format(w.weekLoadH())} load-hours in the last seven days: hours of work, classes and " +
             "travel, each weighted by how draining it is. Comparing it with a normal week needs two " +
             "weeks of logged days; you have ${w.daysLogged()}.")
    }
}

/* ------------------------------------------------------------- which day */

@Composable
private fun LogDayPicker(vm: ChronosViewModel, day: LocalDate, earliest: LocalDate, onPick: (LocalDate) -> Unit) {
    Panel {
        Text(dateText(day), style = MaterialTheme.typography.titleMedium)
        Hint(if (day == vm.logDay) "Today — the day you are reporting on."
             else "A day that has already closed. What you add or change here waits for the " +
                  "Apply button below, or for tonight's close.")
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { onPick(day.minusDays(1)) }, enabled = day.isAfter(earliest)) {
                Text("Previous day")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { onPick(day.plusDays(1)) }, enabled = day.isBefore(vm.logDay)) {
                Text("Next day")
            }
            if (day != vm.logDay) {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { onPick(vm.logDay) }) { Text("Today") }
            }
        }
    }
}

/** Entries for closed days wait here until they are applied, or until tonight. */
@Composable
private fun LateEntriesPanel(vm: ChronosViewModel) {
    if (vm.lateEntries <= 0) return
    Panel("Entries for past days", Palette.Sand) {
        Text("${vm.lateEntries} entries for days that have already closed are not in the model yet.",
            style = MaterialTheme.typography.bodySmall)
        Hint("Apply takes them in now: finished units go into your goals' progress and the ratings " +
             "into the model. The plan itself is not rebuilt; if the model moved, the Horizon tab " +
             "says what changed and offers the rebuild. Left alone, they are taken in tonight.")
        Spacer(Modifier.height(8.dp))
        Button(onClick = { vm.applyLateEntries() }, enabled = !vm.busy) { Text("Apply") }
    }
}

/* ---------------------------------------------------------- the day closing */

@Composable
private fun ClosingPanel(vm: ChronosViewModel) {
    Panel {
        TimeField("The day closes at", vm.dayEnd) { vm.changeDayEnd(it) }
        Hint("Pick a time after you are usually asleep. Until then you can keep changing today's " +
             "answers; after it, today is read once and the plan is rebuilt from the next day.")
    }
}

/* ------------------------------------------------------- energy, hour by hour */

/** One waking hour: its clock hour and the slot a rating in it is filed under. */
private data class WakingHour(val hour: Int, val at: String)

/**
 * The hours with any waking time in them, from getting up to going to bed.
 * A rating is filed at the middle of the hour's waking part, so a half hour
 * before bed is not filed at a moment the model believes you were asleep.
 */
private fun wakingHours(sleepStart: String, sleepEnd: String): List<WakingHour> {
    val s0 = Slots.parse(sleepStart)
    val s1 = Slots.parse(sleepEnd)
    val perHour = 60 / Slots.SLOT_MIN
    val firstHour = s1 / perHour
    val out = mutableListOf<WakingHour>()
    for (i in 0 until 24) {
        val h = (firstHour + i) % 24
        val awake = (0 until perHour).map { h * perHour + it }.filter { !Energy.isAsleep(it, s0, s1) }
        if (awake.isNotEmpty()) out.add(WakingHour(h, Slots.format(awake[awake.size / 2])))
    }
    return out
}

/* ------------------------------------------------------------------ sleep */

private fun sleepId(day: LocalDate) = "sleep|$day"

/** Minutes from going to bed to getting up, across midnight. */
private fun minutesInBed(bed: String, up: String): Int {
    fun m(t: String) = t.split(":").let { it[0].toInt() * 60 + it[1].toInt() }
    return ((m(up) - m(bed)) % 1440 + 1440) % 1440
}

private fun hm(minutes: Int) = "%d:%02d".format(minutes / 60, minutes % 60)

/**
 * The night that ended on this day's morning: when you went to bed and got
 * up, how long falling asleep took, how often you woke, how restful it was.
 * Filed under the morning's date. It is a record and a sign for the rest
 * check — the body-clock fit does not read it.
 */
@Composable
private fun SleepPanel(vm: ChronosViewModel, day: LocalDate) {
    val saved = vm.logs.firstOrNull { it.id == sleepId(day) }
    // What is being typed lives in the view model until it is saved, so it
    // survives switching tabs; it starts from what was saved before.
    val draft = vm.sleepDrafts[day] ?: LogEntry().apply {
        id = sleepId(day); kind = Enums.LogKind.SLEEP; date = day
        at = saved?.at; endAt = saved?.endAt; latencyMin = saved?.latencyMin; wakeups = saved?.wakeups
        restored = saved?.restored; tags = saved?.tags.orEmpty(); note = saved?.note
    }
    fun change(edit: LogEntry.() -> Unit) {
        val next = LogEntry().apply {
            id = draft.id; kind = draft.kind; date = draft.date
            at = draft.at; endAt = draft.endAt; latencyMin = draft.latencyMin; wakeups = draft.wakeups
            restored = draft.restored; tags = draft.tags; note = draft.note
        }
        next.edit()
        vm.sleepDrafts[day] = next
    }
    val bed = draft.at; val up = draft.endAt; val latency = draft.latencyMin
    val quality = draft.restored; val tags = draft.tags.orEmpty(); val note = draft.note ?: ""
    val unsaved = vm.sleepDrafts.containsKey(day)
    // Comes back open when you return to it halfway through.
    var open by remember(day) { mutableStateOf(unsaved) }
    val p = vm.config.params

    Panel(if (day == vm.logDay) "Last night's sleep" else "The night before this day", Palette.Indigo) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (saved?.at != null && saved.endAt != null) {
                    val inBed = minutesInBed(saved.at!!, saved.endAt!!)
                    val asleep = (inBed - (saved.latencyMin ?: 0)).coerceAtLeast(0)
                    Ltr { Text("${saved.at}–${saved.endAt}", style = MaterialTheme.typography.bodyMedium) }
                    Text(tr("In bed") + " " + hm(inBed) + "  ·  " + tr("asleep about") + " " + hm(asleep) +
                         (saved.restored?.let { "  ·  " + tr("restful") + " $it/5" } ?: "") +
                         (saved.wakeups?.takeIf { it > 0 }?.let { "  ·  " + tr("woke") + " $it×" } ?: ""),
                        style = MaterialTheme.typography.labelSmall, color = Palette.Moss)
                } else Text(if (unsaved) "Filled in, not saved yet" else "Not logged yet",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (unsaved) Palette.Sand else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(if (open) "▲" else "▼", style = MaterialTheme.typography.labelSmall)
        }
        if (!open) return@Panel

        Row {
            TimeField("Went to bed", bed, Modifier.weight(1f)) { v -> change { at = v } }
            Spacer(Modifier.width(8.dp))
            TimeField("Got up", up, Modifier.weight(1f)) { v -> change { endAt = v } }
        }
        // 12 and 00 sit on two rings of the dial: a bedtime by day with a short or endless night is a slip.
        if (bed != null && up != null) {
            val meant = com.chronos.engine.Habits.bedMinutes(bed, up)
            val fixed = Slots.formatMinutes(meant)
            if (fixed != bed) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Did you mean {}?").replace("{}", fixed), style = MaterialTheme.typography.labelSmall,
                    color = Palette.Sand, modifier = Modifier.weight(1f))
                TextButton(onClick = { change { at = fixed } }) { Text(tr("Use {}").replace("{}", fixed)) }
            }
        }
        if (p.sleepStart != null && p.sleepEnd != null)
            TextButton(onClick = { change { at = p.sleepStart; endAt = p.sleepEnd } }) {
                Text(tr("Same as usual") + " (" + p.sleepStart + "–" + p.sleepEnd + ")")
            }
        Row {
            IntField("Minutes to fall asleep", latency, modifier = Modifier.weight(1f)) { v -> change { latencyMin = v } }
            Spacer(Modifier.width(8.dp))
            IntField("Times you woke", draft.wakeups, modifier = Modifier.weight(1f)) { v -> change { wakeups = v } }
        }
        Scale("How restful was it?", quality ?: 0, "barely slept", "deeply rested") { v -> change { restored = v } }
        Text("Anything about the night?", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        TagPicker(SLEEP_TAGS, tags) { v -> change { this.tags = v } }
        TextField("Note", note) { v -> change { this.note = v } }
        val b = bed; val u = up
        if (b != null && u != null) {
            val inBed = minutesInBed(b, u)
            Hint(tr("In bed") + " " + hm(inBed) + "  ·  " + tr("asleep about") + " " +
                 hm((inBed - (latency ?: 0)).coerceAtLeast(0)))
        }
        Hint("A night of under six hours or rated 1–2 counts as a bad night in the rest check.")
        val missing = listOfNotNull(
            if (b == null) "when you went to bed" else null,
            if (u == null) "when you got up" else null)
        if (missing.isNotEmpty())
            Text(tr("To save, set") + ": " + missing.map { tr(it) }.joinToString(", "),
                style = MaterialTheme.typography.labelSmall, color = Palette.Brick)
        else if (unsaved)
            Text("Not saved yet", style = MaterialTheme.typography.labelSmall, color = Palette.Sand)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                vm.putLogs(listOf(LogEntry().apply {
                    id = sleepId(day); kind = Enums.LogKind.SLEEP; date = day
                    at = draft.at; endAt = draft.endAt; latencyMin = draft.latencyMin; wakeups = draft.wakeups
                    restored = draft.restored; this.tags = draft.tags.orEmpty(); this.note = draft.note?.ifBlank { null }
                    ts = System.currentTimeMillis()
                }))
                vm.sleepDrafts.remove(day)
                open = false
            }, enabled = missing.isEmpty() && !vm.busy) { Text("Save the night") }
            if (unsaved) {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { vm.sleepDrafts.remove(day) }) { Text("Discard changes") }
            }
        }
    }
}

@Composable
private fun EnergyPanel(vm: ChronosViewModel, day: LocalDate, blocks: List<Block>?) {
    val p = vm.config.params
    if (p.sleepStart == null || p.sleepEnd == null) {
        Panel("Energy through the day") {
            Hint("Set your sleep hours in Setup first: the list is built from the hours you are awake.")
        }
        return
    }
    val hours = remember(p.sleepStart, p.sleepEnd) { wakingHours(p.sleepStart, p.sleepEnd) }
    val mine = vm.logs.filter { it.kind == Enums.LogKind.CHECKIN && it.date == day && it.id.startsWith("energy|") }
    val byHour = mine.associateBy { it.id.substringAfterLast('|').toIntOrNull() ?: -1 }

    // Day-wide causes live in each rated hour's tags. Before any hour is rated
    // they are held here, and written with the first rating.
    var dayCauses by remember(day) {
        mutableStateOf(DAY_CAUSES.filter { t -> mine.any { it.tags?.contains(t) == true } })
    }
    val pendingCauses = remember(day) { mutableStateMapOf<Int, List<String>>() }
    var openHour by remember(day) { mutableStateOf<Int?>(null) }

    fun hourCauses(h: Int): List<String> =
        byHour[h]?.tags?.filter { it !in DAY_CAUSES } ?: pendingCauses[h] ?: emptyList()

    fun entry(w: WakingHour, rating: Int, causes: List<String>, allDay: List<String>) = LogEntry().apply {
        id = energyId(day, w.hour)
        kind = Enums.LogKind.CHECKIN
        date = day
        at = w.at
        ts = System.currentTimeMillis()
        energy = rating
        tags = (causes + allDay).distinct()
    }

    val lang = LocalLang.current
    Panel("Energy through the day") {
        vm.observeConfig()
        Hint("Tap how sharp you were in each hour, from foggy to razor sharp. Skip the hours you " +
             "cannot remember — a few honest answers beat a full row of guesses.")
        Spacer(Modifier.height(8.dp))

        val setDay: (List<String>) -> Unit = { chosen ->
            dayCauses = chosen
            vm.putLogs(hours.mapNotNull { w ->
                byHour[w.hour]?.energy?.let { r -> entry(w, r, hourCauses(w.hour), chosen) }
            })
        }
        Text("All day: what was going on", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        TagPicker(DAY_CIRCUMSTANCES, dayCauses.filter { it in DAY_CIRCUMSTANCES }) { chosen ->
            setDay(chosen + dayCauses.filter { it in DAY_FEELINGS })
        }
        Text("All day: how you felt", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        TagPicker(DAY_FEELINGS, dayCauses.filter { it in DAY_FEELINGS }) { chosen ->
            setDay(dayCauses.filter { it in DAY_CIRCUMSTANCES } + chosen)
        }
        Hint("Outside causes that coloured the whole day. Its ratings are kept, but left out of " +
             "the body-clock fit — they describe that day, not your natural rhythm.")
        Spacer(Modifier.height(10.dp))
        HorizontalDivider()

        hours.forEach { w ->
            val rating = byHour[w.hour]?.energy
            val causes = hourCauses(w.hour)
            val planned = blocks.orEmpty()
                .filter { !it.gap && it.start >= 0 && overlapsHour(it, w.hour) }
                .joinToString(" · ") { it.name ?: "" }
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Ltr {
                        Text("%02d–%02d".format(w.hour, (w.hour + 1) % 24),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.width(56.dp))
                    }
                    (1..5).forEach { n ->
                        Box(Modifier.weight(1f)) {
                            Pill(n.toString(), rating == n) {
                                if (rating == n) vm.dropLogs(listOf(energyId(day, w.hour)))
                                else vm.putLogs(listOf(entry(w, n, causes, dayCauses)))
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (planned.isBlank()) "Nothing planned" else planned,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f))
                    // A short label only: a long list of reasons in the button squeezed the
                    // planned activity beside it into one letter per line. The list goes below.
                    TextButton(onClick = { openHour = if (openHour == w.hour) null else w.hour },
                        modifier = Modifier.widthIn(max = 140.dp)) {
                        Text(if (causes.isEmpty()) tr("Why?") else tr("Reasons") + " (" + causes.size + ")",
                            style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                }
                if (causes.isNotEmpty())
                    Text(causes.joinToString(if (lang.rtl) "، " else ", ") { translate(lang, it) },
                        style = MaterialTheme.typography.labelSmall, color = Palette.Plum,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp))
                if (openHour == w.hour) {
                    val update: (List<String>) -> Unit = { chosen ->
                        if (rating != null) vm.putLogs(listOf(entry(w, rating, chosen, dayCauses)))
                        else pendingCauses[w.hour] = chosen
                    }
                    Text("Around you — rating kept out of the fit",
                        style = MaterialTheme.typography.labelSmall, color = Palette.Brick)
                    TagPicker(HOUR_OUTSIDE, causes.filter { it in HOUR_OUTSIDE }) { chosen ->
                        update(chosen + causes.filter { it !in HOUR_OUTSIDE }) }
                    Text("How you felt — rating kept out of the fit",
                        style = MaterialTheme.typography.labelSmall, color = Palette.Plum)
                    TagPicker(HOUR_FEELINGS, causes.filter { it in HOUR_FEELINGS }) { chosen ->
                        update(chosen + causes.filter { it !in HOUR_FEELINGS }) }
                    Text("Inside the body — the curve already accounts for these",
                        style = MaterialTheme.typography.labelSmall, color = Palette.Moss)
                    TagPicker(HOUR_INSIDE, causes.filter { it in HOUR_INSIDE }) { chosen ->
                        update(chosen + causes.filter { it !in HOUR_INSIDE }) }
                }
            }
            HorizontalDivider()
        }
        Spacer(Modifier.height(6.dp))
        Hint("${mine.size} of ${hours.size} hours rated. These ratings are what fit your body clock: " +
             "your baseline, the height and timing of your daily peak, and how much sleep pressure " +
             "and accumulated work fatigue pull you down. The work fatigue comes from the blocks you " +
             "report below, so the two lists teach the model together.")
    }
}

private fun overlapsHour(b: Block, hour: Int): Boolean {
    val perHour = 60 / Slots.SLOT_MIN
    val a = hour * perHour
    val z = a + perHour
    // Blocks can run past midnight; compare on the unwrapped span and its wrap.
    return (b.start < z && b.start + b.dur > a) ||
        (b.start + b.dur > Slots.PER_DAY && b.start + b.dur - Slots.PER_DAY > a)
}

/* ------------------------------------------------------ the plan, block by block */

/** The activity a stored block came from. Stored blocks carry the name and goal, not the object. */
private fun activityOf(cfg: Config, b: Block): Activity? {
    if (b.fixed || b.gap) return null
    return cfg.activities.firstOrNull { it.name == b.name && (b.goalId == null || it.goalId == b.goalId) }
}

/** The trip a travel leg belongs to, if its duration is something the model learns. */
private fun tripOf(cfg: Config, b: Block): Commitment? {
    if (b.fixed || b.gap || !b.away) return null
    val name = b.name ?: return null
    val base = when {
        name.endsWith(" (out)") -> name.removeSuffix(" (out)")
        name.endsWith(" (back)") -> name.removeSuffix(" (back)")
        else -> return null
    }
    return cfg.commitments.firstOrNull { it.name == base && it.kind == Enums.CommitmentKind.VARIABLE }
}

@Composable
private fun ActivityPanel(vm: ChronosViewModel, day: LocalDate, hasPlan: Boolean, noPlan: Boolean,
                          blocks: List<Block>?) {
    Panel("What the day held") {
        vm.observeConfig()
        if (!hasPlan) {
            Hint("There is no plan yet. Build one on the Horizon tab and its blocks appear here.")
            return@Panel
        }
        if (noPlan) {
            Hint("The plan for this day was not kept — it is from before plans were stored. " +
                 "Use the list below to add what you did.")
            return@Panel
        }
        if (blocks == null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(Modifier.size(24.dp))
            }
            return@Panel
        }
        Hint("One box per block of the day's plan. Open it, say how much of it you did, when you " +
             "started and how it went, and save. Blocks you leave unreported teach the model nothing, " +
             "which is better than a guess.")
        vm.dayResult(day)?.unplaced()?.takeIf { it.isNotEmpty() }?.let { missing ->
            Spacer(Modifier.height(8.dp))
            Text("Not placed in this day's plan", style = MaterialTheme.typography.labelSmall,
                color = Palette.Brick)
            missing.forEach { MissingRow(it.name ?: "", it.reason ?: "") }
        }
        Spacer(Modifier.height(8.dp))
        val placed = blocks.filter { !it.gap && it.start >= 0 }.sortedBy { it.start }
        if (placed.isEmpty()) Hint("Nothing was planned for this day.")
        else PlanBesideDone(vm, day, placed)
        // Each saved entry under its block, even after the day was arranged again (28 Oct 2026).
        val places = placed.mapNotNull { b ->
            val act = activityOf(vm.config, b)
            val trip = if (act == null) tripOf(vm.config, b) else null
            val cls = if (act == null && trip == null) classOf(vm.config, b, day) else null
            when {
                act != null -> com.chronos.engine.LogHomes.Place("act|" + act.id, b.start)
                trip != null -> com.chronos.engine.LogHomes.Place("trip|" + trip.id, b.start)
                cls != null -> com.chronos.engine.LogHomes.Place("class|" + cls.id, b.start)
                else -> null
            }
        }
        val homes = remember(day, placed, vm.logs) { com.chronos.engine.LogHomes.of(day, places, vm.logs) }
        placed.forEach { b ->
            val act = activityOf(vm.config, b)
            val trip = if (act == null) tripOf(vm.config, b) else null
            val cls = if (act == null && trip == null) classOf(vm.config, b, day) else null
            when {
                act != null -> SessionBox(vm, day, b, act, homes["act|${act.id}|${b.start}"] ?: b.start)
                trip != null -> TripBox(vm, day, b, trip, homes["trip|${trip.id}|${b.start}"] ?: b.start)
                cls != null -> ClassBox(vm, day, b, cls, homes["class|${cls.id}|${b.start}"] ?: b.start)
                else -> FixedRow(b)
            }
        }
        val unseen = remember(day, placed, vm.logs) { com.chronos.engine.LogHomes.unseen(day, places, vm.logs) }
        if (unseen.isNotEmpty()) UnseenEntries(vm, unseen)
    }
}

/**
 * Saved entries of this day that no block shows any more — the activity is not in the day's plan
 * now. Kept and listed, never hidden, so they are not filled in a second time (28 Oct 2026).
 */
@Composable
private fun UnseenEntries(vm: ChronosViewModel, unseen: List<LogEntry>) {
    Spacer(Modifier.height(10.dp))
    Text(tr("Saved for this day, under no block of its plan now"), style = MaterialTheme.typography.labelMedium, color = Palette.Sand)
    Hint("The day was arranged again after you saved these, and their block is no longer in it. They are kept and " +
         "count as they are. If one is the same as an entry above, delete one of the two.")
    unseen.sortedBy { it.at ?: "" }.forEach { l ->
        val name = l.actId?.let { vm.config.activity(it)?.name } ?: l.comId?.let { id -> vm.config.commitments.firstOrNull { it.id == id }?.name } ?: "?"
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text((l.at ?: "—") + "  " + name.trim() + "  ·  " +
                (if (l.at == null) tr("Skipped") else (l.actual?.let { "$it′" } ?: "") +
                    (l.units?.let { "  ·  " + trimNumber(it) + " " + tr("units") } ?: "")),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.removeLog(l.id) }) { Text("Delete") }
        }
    }
}

private fun span(b: Block) = "${Slots.format(b.start)}–${Slots.format(b.start + b.dur)}"

/**
 * The plan and what was done, side by side on one clock (your request of 23 Oct 2026): each box
 * with its own name, the plan on one side and the logged sessions, trips and classes on the other.
 */
@Composable
private fun PlanBesideDone(vm: ChronosViewModel, day: LocalDate, placed: List<Block>) {
    var show by remember(day) { mutableStateOf(true) }
    val cfg = vm.config
    fun colorOf(a: Activity?) = when {
        a == null -> Palette.Muted
        a.leisure -> Palette.Plum
        a.kind == Enums.ActivityKind.MAINTENANCE -> Palette.Moss
        else -> goalColor(cfg, a.goalId)
    }
    val planned = placed.map { b ->
        val a = activityOf(cfg, b)
        val color = when {
            a != null -> colorOf(a)
            classOf(cfg, b, day) != null -> classColor(cfg, classOf(cfg, b, day)!!.id)
            else -> Palette.Muted
        }
        TimeItem(b.start * Slots.SLOT_MIN, (b.start + b.dur) * Slots.SLOT_MIN, (b.name ?: "?").trim(), color, b.fixed || a != null)
    }
    fun minutes(t: String) = t.split(":").let { it[0].toInt() * 60 + it[1].toInt() }
    val done = vm.logs.filter { it.date == day && it.at != null && (it.actual ?: 0) > 0 &&
        (it.kind == Enums.LogKind.SESSION || it.kind == Enums.LogKind.TRAVEL || it.kind == Enums.LogKind.CLASS) }.map { l ->
        val from = minutes(l.at!!)
        when (l.kind) {
            Enums.LogKind.SESSION -> cfg.activity(l.actId).let { a -> TimeItem(from, from + l.actual!!, (a?.name ?: "?").trim(), colorOf(a), true) }
            Enums.LogKind.CLASS -> TimeItem(from, from + l.actual!!, cfg.commitments.firstOrNull { it.id == l.comId }?.name ?: "?",
                classColor(cfg, l.comId), true)
            else -> TimeItem(from, from + l.actual!!, cfg.commitments.firstOrNull { it.id == l.comId }?.name ?: "?", Palette.Muted, true)
        }
    }
    TextButton(onClick = { show = !show }) {
        Text((if (show) "▴ " else "▾ ") + tr("The plan beside what was done"))
    }
    if (!show) return
    val all = planned + done
    val from = ((all.minOfOrNull { it.from } ?: 420) / 60) * 60
    val to = (((all.maxOfOrNull { it.to } ?: 1320) + 59) / 60) * 60
    TimeColumns(listOf(tr("Planned"), tr("Done")), listOf(planned, done), from, to, hourHeight = 40.dp)
    if (done.isEmpty()) Hint("Nothing logged yet: fill in the boxes below and what you did appears beside the plan.")
    Spacer(Modifier.height(8.dp))
}

/** The class a fixed block is: a fixed commitment of that name with a session starting then. */
private fun classOf(cfg: Config, b: Block, day: LocalDate): Commitment? =
    cfg.commitments.firstOrNull { c -> c.kind == Enums.CommitmentKind.FIXED && c.name == b.name &&
        c.sessionAt(Slots.dow(day), b.start) != null }

/**
 * How a class left you, mind and body apart (your request of 23 Oct 2026). Each rating teaches
 * that session's own mental and physical load and recovery.
 */
@Composable
private fun ClassBox(vm: ChronosViewModel, day: LocalDate, b: Block, c: Commitment, slot: Int) {
    val id = "class|$day|${c.id}|$slot"
    val saved = vm.logs.firstOrNull { it.id == id }
    var open by remember(id) { mutableStateOf(false) }
    val key = saved?.ts
    var mind by remember(id, key) { mutableIntStateOf(saved?.mentalAfter ?: 0) }
    var body by remember(id, key) { mutableIntStateOf(saved?.physicalAfter ?: 0) }
    var tags by remember(id, key) { mutableStateOf(saved?.tags.orEmpty()) }
    val lang = LocalLang.current
    val status = if (saved == null) "Not reported yet" else translate(lang, tiredText(saved, "").removePrefix(" · ").ifBlank { "Saved" })
    FeedbackCard(span(b), b.name ?: "", status,
        if (saved == null) MaterialTheme.colorScheme.onSurfaceVariant else Palette.Moss, open, { open = !open }) {
        Scale("Mentally tired afterwards", mind, "1 fresh", "5 spent",
            "teaches this session's mental load and recovery") { mind = it }
        Scale("Physically tired afterwards", body, "1 fresh", "5 spent",
            "teaches this session's physical load and recovery") { body = it }
        Text("Did anything get in the way?", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        TagPicker(SESSION_TAGS, tags) { tags = it }
        Spacer(Modifier.height(8.dp))
        Row {
            Button(onClick = {
                vm.putLogs(listOf(LogEntry().apply {
                    this.id = id
                    kind = Enums.LogKind.CLASS
                    date = day
                    at = Slots.format(b.start)
                    ts = System.currentTimeMillis()
                    comId = c.id
                    planned = b.dur * Slots.SLOT_MIN
                    actual = b.dur * Slots.SLOT_MIN
                    mentalAfter = mind.takeIf { it > 0 }
                    physicalAfter = body.takeIf { it > 0 }
                    this.tags = tags
                }))
                open = false
            }, enabled = mind > 0 || body > 0) { Text("Save") }
            if (saved != null) {
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { vm.dropLogs(listOf(id)) }) { Text("Clear") }
            }
        }
    }
}

/** A block with nothing to report: a fixed trip, a commitment without sessions. Shown so the list reads as the day. */
@Composable
private fun FixedRow(b: Block) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 4.dp)) {
        Ltr {
            Text(span(b), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(96.dp))
        }
        Text(b.name ?: "", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
    }
}

/** The frame every box shares: a header that opens and closes it, and a status line. */
@Composable
private fun FeedbackCard(
    time: String, title: String, status: String, statusColor: Color, open: Boolean, onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val border = if (open) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .border(BorderStroke(1.dp, border), RoundedCornerShape(6.dp))
    ) {
        Column(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Ltr {
                    Text(time, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(96.dp))
                }
                Text(title, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f))
                Text(if (open) "▲" else "▼", style = MaterialTheme.typography.labelSmall)
            }
            Text(status, style = MaterialTheme.typography.labelSmall, color = statusColor,
                modifier = Modifier.padding(top = 3.dp))
        }
        if (open) Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) { content() }
    }
}

/**
 * The output the plan expected from a block, in the goal's units: its hours,
 * times the activity's learned quality, over the goal's hours per unit — the
 * same conversion the horizon uses to turn remaining units into hours.
 */
private fun expectedUnits(cfg: Config, act: Activity, plannedMin: Int): Double? {
    val g = act.goalId?.let { cfg.goal(it) } ?: return null
    if (g.mode != Enums.GoalMode.CLOSED) return null
    val hpu = g.hoursPerUnit ?: return null
    if (hpu <= 0) return null
    return plannedMin / 60.0 * act.q / hpu
}

private fun round2(v: Double) = Math.round(v * 100) / 100.0

/* ---------------------------------------------------------- one session's answers */

/**
 * What was done in one session, while it is being filled in. The same form
 * serves a planned block, something done instead of a skipped one, and
 * something no block covered.
 */
private class SessionDraft(saved: LogEntry?, defStart: String, defMinutes: Int, target: Double?) {
    var startAt by mutableStateOf(saved?.at ?: defStart)
    var minutes by mutableStateOf<Int?>(saved?.actual?.takeIf { it > 0 } ?: defMinutes)
    var attain by mutableStateOf<Double?>(saved?.attain ?: 100.0)
    var units by mutableStateOf(saved?.units ?: target?.let { round2(it) })
    var focus by mutableIntStateOf(saved?.focus ?: 3)
    /** How tired afterwards, mind and body; 0 until you pick one, and then nothing is saved for it. */
    var mind by mutableIntStateOf(saved?.mentalAfter ?: saved?.fatigueAfter ?: 0)
    var body by mutableIntStateOf(saved?.physicalAfter ?: 0)
    var restored by mutableIntStateOf(saved?.restored ?: 3)
    /** Leisure's after-effects (25 Oct 2026); 0 until picked, and then nothing is saved for it. */
    var stopHard by mutableIntStateOf(saved?.stopHard ?: 0)
    var nextStart by mutableIntStateOf(saved?.nextStart ?: 0)
    var craving by mutableIntStateOf(saved?.craving ?: 0)
    var tags by mutableStateOf(saved?.tags?.filter { it != SKIPPED && it != NOTHING_INSTEAD } ?: emptyList())
    /** Whether the start or length was typed in, or came from a save; if not, it may be re-split. */
    var timeSet = saved != null

    /**
     * @param planned what the plan asked of it, in minutes. For something that
     *                was not planned this is its own length, so its quality is
     *                read from what it produced for the time it took.
     */
    fun entry(id: String, day: LocalDate, act: Activity, planned: Int, target: Double?) = LogEntry().apply {
        this.id = id
        kind = Enums.LogKind.SESSION
        date = day
        ts = System.currentTimeMillis()
        actId = act.id
        this.planned = planned
        at = startAt
        actual = (minutes ?: planned).coerceAtLeast(1)
        this.tags = this@SessionDraft.tags
        if (act.kind == Enums.ActivityKind.GOAL) {
            this.attain = this@SessionDraft.attain ?: 100.0
            this.focus = this@SessionDraft.focus
            mentalAfter = mind.takeIf { it > 0 }
            this.units = if (target != null) this@SessionDraft.units else null
        } else {
            this.restored = this@SessionDraft.restored
            if (act.leisure) {
                this.stopHard = this@SessionDraft.stopHard.takeIf { it > 0 }
                this.nextStart = this@SessionDraft.nextStart.takeIf { it > 0 }
                this.craving = this@SessionDraft.craving.takeIf { it > 0 }
            }
        }
        physicalAfter = body.takeIf { it > 0 }
    }
}

@Composable
private fun SessionInputs(d: SessionDraft, act: Activity, goal: Goal?, target: Double?, plannedHint: String?) {
    Row {
        TimeField("Started at", d.startAt, Modifier.weight(1f)) { d.startAt = it; d.timeSet = true }
        Spacer(Modifier.width(10.dp))
        IntField("Minutes spent", d.minutes, modifier = Modifier.weight(1f)) { d.minutes = it; d.timeSet = true }
    }
    if (plannedHint != null) Hint(plannedHint)
    if (act.kind == Enums.ActivityKind.GOAL) {
        NumberField("Target reached (%)", d.attain,
            "How much of what this block was meant to produce got done.") { v ->
            d.attain = v
            if (v != null && target != null) d.units = round2(v / 100.0 * target)
        }
        if (target != null && goal != null) {
            NumberField("Finished (${goal.unit ?: "units"})", d.units,
                "The plan expected ${"%.2f".format(target)}. Added to the goal's progress " +
                "when the day closes.") { v ->
                d.units = v
                if (v != null && target > 0) d.attain = round2(v / target * 100.0)
            }
        }
        Scale("Focus during the session", d.focus, "1 scattered", "5 absorbed",
            "teaches your capacity curve") { d.focus = it }
        Scale("Mentally tired afterwards", d.mind, "1 fresh", "5 spent",
            "teaches this activity's mental load") { d.mind = it }
    } else {
        Scale("How restored afterwards", d.restored, "1 no change", "5 fully reset",
            "teaches this activity's recovery rate") { d.restored = it }
        // How stimulating it was, read from what it left behind (your request of 25 Oct 2026):
        // nobody can say how much dopamine an hour of a game released, but everyone knows these.
        if (act.leisure) {
            Scale("How hard was it to stop?", d.stopHard, "1 easy", "5 could not stop",
                "teaches how stimulating it is") { d.stopHard = it }
            Scale("Getting into the next thing afterwards", d.nextStart, "1 easy", "5 very hard",
                "teaches how stimulating it is") { d.nextStart = it }
            Scale("Did you want more of it afterwards?", d.craving, "1 not at all", "5 a lot",
                "teaches how stimulating it is") { d.craving = it }
        }
    }
    Scale("Physically tired afterwards", d.body, "1 fresh", "5 spent",
        "teaches this activity's physical load") { d.body = it }
    Text("Did anything get in the way?", style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    TagPicker(SESSION_TAGS, d.tags) { d.tags = it }
}

private fun goalOf(cfg: Config, act: Activity): Goal? = act.goalId?.let { cfg.goal(it) }

/* ---------------------------------------------------------------- a planned block */

@Composable
private fun SessionBox(vm: ChronosViewModel, day: LocalDate, b: Block, act: Activity, slot: Int) {
    // [slot]: the block its entries are saved under, which is this one's start unless it moved since.
    val id = sessionId(day, act.id, slot)
    val saved = vm.logs.firstOrNull { it.id == id }
    val prefix = insteadPrefix(day, act.id, slot)
    val savedInstead = vm.logs.filter { it.id.startsWith(prefix) }
    var open by remember(id) { mutableStateOf(false) }

    val goal = goalOf(vm.config, act)
    val plannedMin = b.dur * Slots.SLOT_MIN
    val target = expectedUnits(vm.config, act, plannedMin)
    val skipped = saved != null && saved.at == null

    // Seeded from what was saved, or from the plan; re-seeded when a save lands.
    val key = saved?.ts
    var did by remember(id, key) { mutableStateOf(!skipped) }
    val draft = remember(id, key) { SessionDraft(saved?.takeIf { !skipped }, Slots.format(b.start), plannedMin, target) }

    // What was done instead of a skipped block: several things can share its time.
    var instead by remember(id, key) {
        mutableStateOf(savedInstead.mapNotNull { it.actId })
    }
    var nothing by remember(id, key) { mutableStateOf(saved?.tags?.contains(NOTHING_INSTEAD) == true) }
    val insteadDrafts = remember(id, key) { mutableStateMapOf<String, SessionDraft>() }

    val lang = LocalLang.current
    val status = if (saved == null) "Not reported yet" else when {
        skipped && savedInstead.isNotEmpty() -> translate(lang, "Skipped · instead:") + " " +
            savedInstead.joinToString(", ") { l ->
                "${vm.config.activity(l.actId)?.name ?: "?"} ${l.actual}′"
            }
        skipped && saved.tags?.contains(NOTHING_INSTEAD) == true -> "Skipped · nothing else done"
        skipped -> "Skipped"
        act.kind == Enums.ActivityKind.GOAL -> "Done ${saved.actual} min · ${saved.attain?.toInt() ?: 0}% of target · " +
            "focus ${saved.focus}/5 · drained ${saved.fatigueAfter}/5"
        else -> "Done ${saved.actual} min · restored ${saved.restored}/5"
    }
    val statusColor = when {
        saved == null -> MaterialTheme.colorScheme.onSurfaceVariant
        skipped -> Palette.Sand
        else -> Palette.Moss
    }

    FeedbackCard(span(b), b.name ?: "", status, statusColor, open, { open = !open }) {
        FlowRowSimple {
            Pill("I did it", did) { did = true }
            Pill("I skipped it", !did) { did = false }
        }
        if (did) {
            SessionInputs(draft, act, goal, target,
                "Planned: $plannedMin min from ${Slots.format(b.start)}. Count only the time you " +
                "were actually on it.")
            Hint("Interruptions, illness and stress keep this session's fatigue out of the fit; " +
                 "its output still counts.")
        } else {
            Hint("A skipped block is not a bad block: it does not lower this activity's quality. " +
                 "The work it was meant to do stays in the goal and is planned again.")
            Spacer(Modifier.height(6.dp))
            Text("What did you do instead?", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            val others = vm.loggableOn(day, instead).filter { it.id != act.id }
            // Until a time is typed in, the block is shared evenly and in order
            // among whatever is chosen, and re-shared when the choice changes.
            fun choose(next: List<String>) {
                instead = next
                val n = next.size.coerceAtLeast(1)
                next.forEachIndexed { i, altId ->
                    val d = insteadDrafts[altId] ?: return@forEachIndexed
                    if (d.timeSet) return@forEachIndexed
                    d.startAt = Slots.format(b.start + i * (plannedMin / n) / Slots.SLOT_MIN)
                    d.minutes = plannedMin / n
                }
            }
            FlowRowSimple {
                Pill("Nothing", nothing && instead.isEmpty()) { nothing = true; choose(emptyList()) }
                others.forEach { o ->
                    Pill(o.name ?: "", o.id in instead) {
                        nothing = false
                        choose(if (o.id in instead) instead - o.id else instead + o.id)
                    }
                }
            }
            if (instead.isNotEmpty())
                Hint("Pick every one you did — several can share this time. Each gets its own " +
                     "answers below, and each teaches the model like a planned session.")
            // Default times split the block evenly among what was chosen, in order.
            val share = plannedMin / instead.size.coerceAtLeast(1)
            instead.forEachIndexed { i, altId ->
                val alt = vm.config.activity(altId) ?: return@forEachIndexed
                val altSaved = savedInstead.firstOrNull { it.actId == altId }
                val d = insteadDrafts.getOrPut(altId) {
                    SessionDraft(altSaved, Slots.format(b.start + i * share / Slots.SLOT_MIN), share,
                        expectedUnits(vm.config, alt, share))
                }
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp)
                        .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), RoundedCornerShape(6.dp))
                        .padding(10.dp)
                ) {
                    Text(alt.name ?: "", style = MaterialTheme.typography.bodyMedium)
                    SessionInputs(d, alt, goalOf(vm.config, alt),
                        expectedUnits(vm.config, alt, d.minutes ?: share), null)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row {
            Button(onClick = {
                if (did) {
                    vm.putLogs(listOf(draft.entry(id, day, act, plannedMin, target)))
                    vm.dropLogs(savedInstead.map { it.id })
                } else {
                    val main = LogEntry().apply {
                        this.id = id
                        kind = Enums.LogKind.SESSION
                        date = day
                        ts = System.currentTimeMillis()
                        actId = act.id
                        planned = plannedMin
                        // No start time: the model reads nothing from a session that did not happen.
                        at = null
                        actual = 0
                        tags = if (nothing && instead.isEmpty()) listOf(SKIPPED, NOTHING_INSTEAD) else listOf(SKIPPED)
                    }
                    val alts = instead.mapNotNull { altId ->
                        val alt = vm.config.activity(altId) ?: return@mapNotNull null
                        val d = insteadDrafts[altId] ?: return@mapNotNull null
                        val minutes = d.minutes ?: 0
                        d.entry(prefix + altId, day, alt, minutes.coerceAtLeast(1),
                            expectedUnits(vm.config, alt, minutes))
                    }
                    vm.putLogs(listOf(main) + alts)
                    vm.dropLogs(savedInstead.filter { it.actId !in instead }.map { it.id })
                }
                open = false
            }) { Text("Save") }
            if (saved != null) {
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { vm.dropLogs(listOf(id) + savedInstead.map { it.id }) }) {
                    Text("Clear")
                }
            }
        }
    }
}

/**
 * A trip, reported like any other block: when you left, how long it took,
 * how it left you, and what you did on the way. The time teaches the planner
 * how long the trip really takes, the rating teaches it what the trip costs,
 * and anything done on the way — reading, English in your ears — counts
 * toward its goal like a session at the desk.
 */
@Composable
private fun TripBox(vm: ChronosViewModel, day: LocalDate, b: Block, trip: Commitment, slot: Int) {
    val id = tripId(day, trip.id, slot)
    val saved = vm.logs.firstOrNull { it.id == id }
    val prefix = duringPrefix(day, trip.id, slot)
    val savedDuring = vm.logs.filter { it.id.startsWith(prefix) }
    var open by remember(id) { mutableStateOf(false) }
    val plannedMin = b.dur * Slots.SLOT_MIN

    val key = saved?.ts
    var startAt by remember(id, key) { mutableStateOf(saved?.at ?: Slots.format(b.start)) }
    var minutes by remember(id, key) { mutableStateOf<Int?>(saved?.actual ?: plannedMin) }
    var mind by remember(id, key) { mutableIntStateOf(saved?.mentalAfter ?: saved?.fatigueAfter ?: 0) }
    var body by remember(id, key) { mutableIntStateOf(saved?.physicalAfter ?: 0) }
    var tags by remember(id, key) { mutableStateOf(saved?.tags.orEmpty()) }
    var during by remember(id, key) { mutableStateOf(savedDuring.mapNotNull { it.actId }) }
    val drafts = remember(id, key) { mutableStateMapOf<String, SessionDraft>() }

    val lang = LocalLang.current
    val status = if (saved == null) "Not reported yet" else buildString {
        append(translate(lang, "Took ${saved.actual} min"))
        append(translate(lang, tiredText(saved, " · ")))
        if (savedDuring.isNotEmpty()) append(" · ").append(translate(lang, "on the way:")).append(" ")
            .append(savedDuring.joinToString(", ") { vm.config.activity(it.actId)?.name?.trim() ?: "?" })
    }
    val color = if (saved == null) MaterialTheme.colorScheme.onSurfaceVariant else Palette.Moss

    FeedbackCard(span(b), b.name ?: "", status, color, open, { open = !open }) {
        Row {
            TimeField("Left at", startAt, Modifier.weight(1f)) { startAt = it }
            Spacer(Modifier.width(10.dp))
            IntField("Door to door (min)", minutes, modifier = Modifier.weight(1f)) { minutes = it }
        }
        Hint("Planned $plannedMin min. A few of these and the planner stops guessing.")
        Scale("Mentally tired on arrival", mind, "1 fresh", "5 spent",
            "teaches this trip's mental load") { mind = it }
        Scale("Physically tired on arrival", body, "1 fresh", "5 spent",
            "teaches this trip's physical load") { body = it }

        Spacer(Modifier.height(6.dp))
        Text("What did you do on the way?", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRowSimple {
            vm.loggableOn(day, during).filter { !it.nap }.forEach { a ->
                Pill(a.name ?: "", a.id in during) {
                    during = if (a.id in during) during - a.id else during + a.id
                }
            }
        }
        Hint("Reading, English in your ears, planning the day — whatever you did while travelling " +
             "counts toward its goal and teaches the model, like a session at the desk.")
        during.forEach { actId ->
            val act = vm.config.activity(actId) ?: return@forEach
            val d = drafts.getOrPut(actId) {
                SessionDraft(savedDuring.firstOrNull { it.actId == actId }, startAt, minutes ?: plannedMin,
                    expectedUnits(vm.config, act, minutes ?: plannedMin))
            }
            Column(
                Modifier.fillMaxWidth().padding(vertical = 6.dp)
                    .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), RoundedCornerShape(6.dp))
                    .padding(10.dp)
            ) {
                Text(act.name ?: "", style = MaterialTheme.typography.bodyMedium)
                SessionInputs(d, act, goalOf(vm.config, act),
                    expectedUnits(vm.config, act, d.minutes ?: plannedMin), null)
            }
        }

        Text("Did anything get in the way?", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        TagPicker(SESSION_TAGS, tags) { tags = it }

        Spacer(Modifier.height(8.dp))
        Row {
            Button(onClick = {
                val m = minutes
                if (m != null && m > 0) {
                    val trip0 = LogEntry().apply {
                        this.id = id
                        kind = Enums.LogKind.TRAVEL
                        date = day
                        at = startAt
                        ts = System.currentTimeMillis()
                        comId = trip.id
                        actual = m
                        mentalAfter = mind.takeIf { it > 0 }
                        physicalAfter = body.takeIf { it > 0 }
                        this.tags = tags
                    }
                    val onTheWay = during.mapNotNull { actId ->
                        val act = vm.config.activity(actId) ?: return@mapNotNull null
                        val d = drafts[actId] ?: return@mapNotNull null
                        val mins = (d.minutes ?: m).coerceAtLeast(1)
                        d.entry(prefix + actId, day, act, mins, expectedUnits(vm.config, act, mins))
                    }
                    vm.putLogs(listOf(trip0) + onTheWay)
                    vm.dropLogs(savedDuring.filter { it.actId !in during }.map { it.id })
                }
                open = false
            }) { Text("Save") }
            if (saved != null) {
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { vm.dropLogs(listOf(id) + savedDuring.map { it.id }) }) { Text("Clear") }
            }
        }
    }
}

/* ------------------------------------------------------ not in the plan at all */

/**
 * Things done that no block covered: an extra hour of reading in the evening,
 * or a whole day whose plan was not kept. They count exactly like a planned
 * session — progress, quality, fatigue — only with nothing to compare against.
 */
@Composable
private fun ExtraPanel(vm: ChronosViewModel, day: LocalDate) {
    val prefix = extraPrefix(day)
    val saved = vm.logs.filter { it.id.startsWith(prefix) }.sortedBy { it.at ?: "" }
    var adding by remember(day) { mutableStateOf<String?>(null) }

    Panel("Not in the plan") {
        vm.observeConfig()
        Hint("Anything you did that no block above covers. It teaches the model and counts toward " +
             "your goals just like a planned session.")
        Spacer(Modifier.height(6.dp))
        saved.forEach { l -> ExtraBox(vm, day, l.id, l, startOpen = false) { } }
        adding?.let { newId -> ExtraBox(vm, day, newId, null, startOpen = true) { adding = null } }
        if (adding == null && vm.config.activities.isNotEmpty())
            OutlinedButton(onClick = { adding = prefix + UUID.randomUUID() }) { Text("Add something you did") }
    }
}

@Composable
private fun ExtraBox(vm: ChronosViewModel, day: LocalDate, id: String, saved: LogEntry?, startOpen: Boolean,
                     onDone: () -> Unit) {
    var open by remember(id) { mutableStateOf(startOpen) }
    var actId by remember(id, saved?.ts) {
        mutableStateOf(saved?.actId ?: vm.loggableOn(day).firstOrNull { !it.nap }?.id)
    }
    val act = actId?.let { vm.config.activity(it) } ?: return
    val draft = remember(id, saved?.ts, actId) {
        SessionDraft(saved?.takeIf { it.actId == actId }, saved?.at ?: "18:00", saved?.actual ?: 60,
            expectedUnits(vm.config, act, saved?.actual ?: 60))
    }
    val time = saved?.at?.let { "$it · ${saved.actual}′" } ?: "—"
    val status = if (saved == null) "Not saved yet" else when (act.kind) {
        Enums.ActivityKind.GOAL -> "${saved.attain?.toInt() ?: 0}% of target · focus ${saved.focus}/5" +
            tiredText(saved, " · ")
        else -> "restored ${saved.restored}/5" + tiredText(saved, " · ")
    }
    FeedbackCard(time, act.name ?: "", status,
        if (saved == null) MaterialTheme.colorScheme.onSurfaceVariant else Palette.Moss, open, { open = !open }) {
        Choice("What did you do", actId, vm.loggableOn(day, listOfNotNull(saved?.actId)).map { it.id to (it.name ?: it.id) }) { actId = it }
        val minutes = draft.minutes ?: 0
        val target = expectedUnits(vm.config, act, minutes)
        SessionInputs(draft, act, goalOf(vm.config, act), target, null)
        Spacer(Modifier.height(8.dp))
        Row {
            Button(onClick = {
                vm.putLogs(listOf(draft.entry(id, day, act, minutes.coerceAtLeast(1), target)))
                open = false
                onDone()
            }) { Text("Save") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                if (saved != null) vm.dropLogs(listOf(id))
                onDone()
            }) { Text(if (saved != null) "Clear" else "Cancel") }
        }
    }
}

/* ------------------------------------------------------ taking it all away */

/**
 * Everything, in one file the user keeps: every log entry, the model's
 * coefficients now, and each step by which they changed. The system asks
 * where to put it, so it can go to the phone, a card, or a cloud drive.
 */
@Composable
private fun ExportPanel(vm: ChronosViewModel) {
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) vm.exportLog(uri) }
    Panel("Save everything to a file") {
        Hint("Every goal and activity in full — settings, learned values, what the log says about " +
             "each and how it changed — plus every log entry since the start and every change the model " +
             "made. One JSON file, saved wherever you choose.")
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { launcher.launch("chronos-log-${LocalDate.now()}.json") },
            enabled = !vm.busy) { Text("Choose where to save") }
    }
}

/* ------------------------------------------------------ what the night changed */

@Composable
private fun NightlyPanel(vm: ChronosViewModel) {
    val report = vm.nightly ?: return
    Panel("What changed last night") {
        val days = report.days.split(",").filter { it.isNotBlank() }
            .mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
        days.forEach { Text("Closed: ${dateText(it)}", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Spacer(Modifier.height(6.dp))
        if (report.lines.isEmpty()) Hint("Nothing was logged, so nothing moved.")
        report.lines.forEach { (subject, detail) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(subject, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(120.dp))
                Text(detail, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f))
            }
        }
        if (report.note.isNotBlank()) Hint(report.note)
        Hint("Estimates are pulled toward your original numbers and only drift as evidence " +
             "accumulates, so one strange day cannot rewrite the model. Anything fitted is " +
             "still editable in Setup.")
    }
}

/* ---------------------------------------------------------------- history */

/**
 * The entries of the day on screen, in the order the day went — right under
 * the day picker, so going to a day shows what was already said about it.
 * Folded to one line until opened, so it does not push the lists down.
 */
@Composable
private fun DayHistoryPanel(vm: ChronosViewModel, day: LocalDate) {
    val mine = vm.logs.filter { it.date == day }.sortedWith(compareBy({ it.at ?: "99:99" }, { it.ts }))
    var open by remember(day) { mutableStateOf(false) }
    val lang = LocalLang.current
    Panel {
        Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Entries for this day", style = MaterialTheme.typography.titleMedium)
                Text(if (mine.isEmpty()) "Nothing logged for this day yet."
                     else (if (mine.any { it.kind == Enums.LogKind.SLEEP }) "sleep · " else "") +
                          "${mine.count { it.kind == Enums.LogKind.SESSION }} sessions · " +
                          "${mine.count { it.kind == Enums.LogKind.CHECKIN }} check-ins · " +
                          "${mine.count { it.kind == Enums.LogKind.TRAVEL }} trips",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (mine.isNotEmpty()) Text(if (open) "▲" else "▼", style = MaterialTheme.typography.labelSmall)
        }
        if (open) {
            Spacer(Modifier.height(6.dp))
            mine.forEach { l ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Ltr {
                        Text(l.at ?: "—", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(52.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(describe(l, vm), style = MaterialTheme.typography.bodySmall)
                        val tags = l.tags.orEmpty().filter { it != SKIPPED }
                        if (tags.isNotEmpty())
                            Text(tags.joinToString(if (lang.rtl) "، " else ", ") { translate(lang, it) },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { vm.removeLog(l.id) }) { Text("×") }
                }
                HorizontalDivider()
            }
        }
    }
}

private fun describe(l: LogEntry, vm: ChronosViewModel): String = when (l.kind) {
    Enums.LogKind.CHECKIN -> "Energy ${l.energy}/5"
    Enums.LogKind.SLEEP -> "Sleep ${l.at}–${l.endAt}" + (l.restored?.let { ", restful $it/5" } ?: "")
    Enums.LogKind.CLASS -> (vm.config.commitments.firstOrNull { it.id == l.comId }?.name ?: "Class") + tiredText(l, " — ")
    Enums.LogKind.TRAVEL -> {
        val name = vm.config.commitments.firstOrNull { it.id == l.comId }?.name ?: "Travel"
        if (l.fatigueAfter != null || l.mentalAfter != null || l.physicalAfter != null)
            "$name — ${l.actual} min" + tiredText(l, ", ")
        else "$name — ${l.actual} min"
    }
    else -> {
        val name = vm.config.activities.firstOrNull { it.id == l.actId }?.name ?: "Session"
        when {
            l.at == null -> "$name — skipped"
            l.attain != null ->
                "$name — ${l.attain?.toInt()}% of target, focus ${l.focus}/5" + tiredText(l, ", ")
            else -> "$name — restored ${l.restored}/5" + tiredText(l, ", ")
        }
    }
}

/**
 * How tired an entry says you were, for a summary line: "mind 3/5 · body 2/5",
 * or the one "drained" number entries had before fatigue had two kinds.
 */
internal fun tiredText(l: LogEntry, lead: String): String {
    val parts = listOfNotNull(l.mentalAfter?.let { "mind $it/5" }, l.physicalAfter?.let { "body $it/5" })
    return when {
        parts.isNotEmpty() -> lead + parts.joinToString(" · ")
        l.fatigueAfter != null -> lead + "drained ${l.fatigueAfter}/5"
        else -> ""
    }
}
