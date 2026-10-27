package com.chronos.ui.navigator

import com.chronos.ui.*
import com.chronos.ui.common.*
import com.chronos.ui.setup.*
import com.chronos.ui.horizon.*
import com.chronos.ui.day.*
import com.chronos.ui.log.*

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.chronos.navigator.llm.Crew
import com.chronos.navigator.llm.client.CrewRun
import com.chronos.navigator.model.Node
import com.chronos.navigator.model.Priority
import com.chronos.navigator.store.NavProposals
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/*
 * The Navigator in control (your decisions of 13 Oct 2026): you tell it what
 * is going on, a crew of models — each with its own job — works out a package
 * of changes to the goal graph and to Chronos, the app measures it, and you
 * approve the parts you want.
 */

internal fun roleLabel(r: String) = when (r) {
    "COORDINATOR" -> "Coordinator"
    "ARCHITECT" -> "Architect"
    "ANALYST" -> "Log analyst"
    "ESTIMATOR" -> "Estimator"
    "TUNER" -> "Week tuner"
    "CRITIC" -> "Critic"
    else -> r
}

internal fun roleJob(r: Crew.Role) = when (r) {
    Crew.Role.COORDINATOR -> "Decides what reaches you, answers the critic, may ask once for a revision."
    Crew.Role.ARCHITECT -> "Turns what you tell it into tasks, dependencies and planner changes."
    Crew.Role.ANALYST -> "Reads the summary of your log and says what it shows."
    Crew.Role.ESTIMATOR -> "Checks the hours of new tasks against your measured speed."
    Crew.Role.TUNER -> "Sets the week's levers: pressure, sleep, rest days, activities."
    Crew.Role.CRITIC -> "Looks for what is wrong or risky in a proposal and its measured effect."
}

private fun stepMark(s: String) = when (s) { "DONE" -> "✓"; "FAILED" -> "✗"; "SKIPPED" -> "–"; "RUNNING" -> "…"; else -> "○" }

private fun stepColor(s: String) = when (s) { "DONE" -> Palette.Moss; "FAILED" -> Palette.Brick; "RUNNING" -> Palette.Indigo; else -> Palette.Muted }

// ---------------------------------------------------------------- talk

@Composable
internal fun CrewPanel(nav: NavigatorViewModel, compact: Boolean = false) {
    Panel("Tell the Navigator", Palette.Indigo) {
        if (!compact || nav.chat.isEmpty())
            Hint("Say what is going on — a task your advisor gave you, a change of plans, something you want. A crew of " +
                 "models works out what to change in your goals and in Chronos; the app measures it, and you approve " +
                 "the parts you want. Nothing changes before that.")
        // Where your messages go: the crew's models, or your own chat app (copy and return).
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            Text(tr("Ask through") + ":", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 6.dp))
            Pill(tr("The crew"), !nav.relayMode) { nav.setRelay(false) }
            Spacer(Modifier.width(6.dp))
            Pill(tr("My chat app"), nav.relayMode) { nav.setRelay(true) }
        }
        if (nav.relayMode && !compact)
            Hint("The app makes one complete question; you send it to Claude, ChatGPT or Gemini, copy the whole reply, " +
                 "and bring it back here. It is checked and measured like any answer. No limits, no busy providers.")
        if (!nav.crewReady && !nav.relayMode) {
            Text(tr("The crew has no models yet: set it up in the LLM panel (Settings → The crew)."),
                style = MaterialTheme.typography.bodySmall, color = Palette.Brick)
            TextButton(onClick = { nav.page = NavPage.SETTINGS }) { Text(tr("Open Settings") + " ›") }
            return@Panel
        }
        // On the home the last few turns; the whole conversation on its own page.
        val turns = nav.chat.takeLast(if (compact) 3 else 60)
        if (nav.chat.size > turns.size)
            TextButton(onClick = { nav.page = NavPage.CHAT }) {
                Text(tr("Full conversation") + " (" + nav.chat.size + ") ›")
            }
        var draft by remember { mutableStateOf("") }
        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
        turns.forEach { t ->
            if (t.proposalId != null && t.who != "you") PackageCard(nav, t.proposalId, t.text)
            else Bubble(t.text, mine = t.who == "you")
            // A message that did not go stays, with why and what you can do with it.
            t.failed?.let { why ->
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                    Text(tr("Not sent") + ": " + why, style = MaterialTheme.typography.labelSmall, color = Palette.Brick,
                        modifier = Modifier.fillMaxWidth(0.85f))
                    Row {
                        TextButton(onClick = { nav.retry(t) }, enabled = !nav.busy) { Text("Send again") }
                        TextButton(onClick = { draft = t.text; nav.takeBack(t) }, enabled = !nav.busy) { Text("Edit") }
                        TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(t.text)) }) { Text("Copy") }
                    }
                }
            }
        }
        nav.jobs.filter { it.status == "RUNNING" }.forEach { JobProgress(it) }
        nav.jobs.filter { it.relay && (it.status == "PREPARING" || it.status == "WAITING_ANSWER") }
            .sortedBy { it.createdAt }.forEach { RelayCard(nav, it) }
        // What the message is: a situation to act on, or a question about the graph itself (your request of 22 Oct 2026).
        var aboutGraph by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            Pill(tr("Something happened"), !aboutGraph) { aboutGraph = false }
            Spacer(Modifier.width(6.dp))
            Pill(tr("A question about the graph"), aboutGraph) { aboutGraph = true }
        }
        if (aboutGraph) Hint("Ask why two goals are linked, where a chance comes from, or what would raise it. The model sees the " +
            "whole graph with today's odds; if you ask it to correct a link, an estimate or a chance, the correction comes as a package to approve.")
        fun send() {
            val t = draft.trim()
            when {
                aboutGraph -> nav.askGraph(t)
                nav.relayMode -> nav.tellRelay(t)
                else -> nav.tell(t)
            }
            draft = ""
        }
        OutlinedTextField(value = draft, onValueChange = { draft = it }, modifier = Modifier.fillMaxWidth(), minLines = 2,
            placeholder = { Text(tr(if (aboutGraph) "e.g. Why is the chance of my master's application so low?"
                else "e.g. My advisor wants a literature review of 20 papers by 15 November."),
                style = MaterialTheme.typography.bodySmall) },
            // Sent from inside the box, so the keyboard need not be closed first.
            trailingIcon = { SendIcon(draft.isNotBlank() && !nav.busy) { send() } })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { send() }, enabled = draft.isNotBlank() && !nav.busy) { Text("Send") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { if (nav.relayMode) nav.reviewRelay() else nav.reviewNow() },
                enabled = !nav.busy && nav.jobs.none { (it.status == "RUNNING" || it.status == "PREPARING") && it.kind == "WEEKLY" }) {
                Text("Review the week now")
            }
        }
        if (nav.computing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
        if (!nav.relayMode)
            Hint(tr("Free requests left today") + ": " + nav.freeLeft + " / " + nav.freeCap + "  ·  " +
                 tr("a message takes about 3 to 6, a weekly review 4 to 6."))
        if (nav.chat.isNotEmpty()) TextButton(onClick = { nav.clearChat() }) { Text("Clear the conversation") }
    }
}

/**
 * A question made for your own chat app: send it (or copy it), then bring the chat's whole reply back.
 * The answer is checked and measured like any; a reply that cannot be read can be pasted again.
 */
@Composable
private fun RelayCard(nav: NavigatorViewModel, job: CrewRun.Job) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var pasted by remember(job.id) { mutableStateOf("") }
    // A shared reply that could not be read comes back here, to fix or replace.
    LaunchedEffect(nav.sharedFailed) { nav.sharedFailed?.let { pasted = it; nav.sharedFailed = null } }
    val chooserTitle = tr("Send the question to")
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp))
        .background(Palette.Teal.copy(alpha = 0.08f)).padding(10.dp)) {
        Text(tr(when (job.kind) { "WEEKLY" -> "Weekly review"; "HABITS" -> "From your log"; else -> "Your question" }) + "  ·  " +
            tr(if (job.status == "PREPARING") "being prepared" else "ready for your chat app"),
            style = MaterialTheme.typography.labelMedium, color = Palette.Teal)
        if (job.status == "PREPARING") { LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp)); return@Column }
        Hint("1. Send it to your chat app.  2. When it answers, share its reply (long-press it → Share) to \"Chronos navigator\" — " +
             "the package is made by itself. Or copy the whole reply and paste it below.")
        Row {
            TextButton(onClick = {
                val q = nav.relayPrompt(job.id) ?: return@TextButton
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, q)
                }
                context.startActivity(android.content.Intent.createChooser(send, chooserTitle))
            }) { Text("Send to a chat app") }
            TextButton(onClick = { nav.relayPrompt(job.id)?.let { clipboard.setText(androidx.compose.ui.text.AnnotatedString(it)) } }) {
                Text("Copy the question")
            }
        }
        OutlinedTextField(value = pasted, onValueChange = { pasted = it }, modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp),
            placeholder = { Text(tr("Paste the chat's whole reply here"), style = MaterialTheme.typography.bodySmall) },
            textStyle = MaterialTheme.typography.bodySmall)
        Row {
            TextButton(onClick = { pasted = clipboard.getText()?.text ?: "" }) { Text("Paste") }
            Button(onClick = { nav.answerRelay(job.id, pasted) }, enabled = pasted.isNotBlank() && !nav.busy) { Text("Use this answer") }
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = { nav.cancelRelay(job.id) }) { Text("Cancel") }
        }
    }
}

/** The send arrow inside a message box. */
@Composable
internal fun SendIcon(enabled: Boolean, send: () -> Unit) {
    IconButton(onClick = send, enabled = enabled) {
        Text("➤", style = MaterialTheme.typography.titleLarge,
            color = if (enabled) Palette.Indigo else MaterialTheme.colorScheme.outline)
    }
}

/** A package the crew filed, as a card in the conversation: its reply, how many changes, where it stands. */
@Composable
private fun PackageCard(nav: NavigatorViewModel, id: String, reply: String) {
    // A package revised in a conversation lives on as its newest version.
    var p = nav.proposals.firstOrNull { it.id() == id }
    while (p != null && p.status() == "REVISED") {
        val next = nav.proposals.firstOrNull { it.parentId() == p!!.id() } ?: break
        p = next
    }
    val n = p?.crew()?.getAsJsonArray("groups")?.size() ?: 0
    val weekly = p?.crew()?.get("kind")?.asString == "WEEKLY"
    val color = when (p?.status()) { "PENDING" -> Palette.Plum; "APPROVED" -> Palette.Moss; null -> Palette.Muted; else -> Palette.Brick }
    Column(Modifier.fillMaxWidth(0.92f).padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp))
        .background(color.copy(alpha = 0.08f)).clickable(enabled = p != null) { nav.openProposal = p!!.id() }
        .padding(10.dp)) {
        Text(tr(if (weekly) "Weekly review" else "Package") + "  ·  " +
            (if (p == null) tr("no longer in the inbox") else tr(statusLabel(p.status())) +
                (if (n > 0) "  ·  " + n + " " + tr(if (n == 1) "change" else "changes") else "")),
            style = MaterialTheme.typography.labelMedium, color = color)
        Text(reply, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        if (p != null) Text(tr(if (p.status() == "PENDING") "Open to review and approve" else "Open") + " ›",
            style = MaterialTheme.typography.labelMedium, color = color, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun JobProgress(job: CrewRun.Job) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(8.dp))
        .background(Palette.Indigo.copy(alpha = 0.06f)).padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(6.dp))
            Text(tr(if (job.kind == "WEEKLY") "The crew is reviewing the week" else "The crew is working on it") +
                (job.stage?.let { " — " + tr(if (it == "measuring") "the app is measuring" else roleLabel(it.uppercase())) } ?: ""),
                style = MaterialTheme.typography.labelMedium)
        }
        job.steps.forEach { s ->
            Text(stepMark(s.status) + "  " + tr(roleLabel(s.role)) + (s.model?.let { "  ·  $it" } ?: ""),
                style = MaterialTheme.typography.labelSmall, color = stepColor(s.status))
        }
    }
}

// ---------------------------------------------------------------- setup

@Composable
internal fun CrewSetup(nav: NavigatorViewModel) {
    Text(tr("The crew"), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
    Hint("Each role is played by its own model. OpenRouter's free models cost nothing; the coordinator may be a " +
         "stronger provider you set up above (Gemini, Claude). If a role's model does not answer, the next free one takes over.")
    Button(onClick = { nav.autoAssignCrew() }, enabled = !nav.busy && nav.profiles.any { it.preset == "openrouter" }) {
        Text("Choose models automatically")
    }
    if (nav.profiles.none { it.preset == "openrouter" })
        Hint("First add OpenRouter as a provider (Add a provider → OpenRouter) with your key.")
    val profiles = nav.profiles
    Crew.Role.entries.forEach { role ->
        val pick = nav.crewPicks[role.name]
        val prof = profiles.firstOrNull { it.id == pick?.profileId }
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(tr(roleLabel(role.name)), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            Hint(roleJob(role))
            val options = buildList {
                profiles.filter { it.preset != "openrouter" && it.model.isNotBlank() }.forEach { p ->
                    add((p.id + "|" + p.model) to (p.label + " · " + p.model))
                    // Other models of the same provider, once "Fetch models" listed them: a role may use one
                    // without changing the provider's own model.
                    if (nav.fetchedFor == p.id) nav.fetchedModels.filter { it != p.model }
                        .forEach { m -> add((p.id + "|" + m) to (p.label + " · " + m)) }
                }
                val router = profiles.firstOrNull { it.preset == "openrouter" }
                if (router != null) nav.freeModels.forEach { add((router.id + "|" + it.id) to it.id) }
                if (pick != null && none { it.first == pick.profileId + "|" + pick.model })
                    add((pick.profileId + "|" + pick.model) to ((prof?.label ?: "?") + " · " + pick.model))
            }
            Choice("", pick?.let { it.profileId + "|" + it.model }, options) { v ->
                val (id, model) = v!!.split("|", limit = 2)
                nav.setCrewPick(role, id, model)
            }
        }
    }
    TextButton(onClick = { nav.refreshFreeModels() }, enabled = !nav.busy) { Text("Fetch the free models again") }
    IntField("Free OpenRouter requests a day", nav.freeCap) { v -> if (v != null && v >= 0) nav.setFreeCap(v) }
    Hint("50 while you have bought less than 10 dollars of OpenRouter credit in all; 1000 once you have " +
         "(the free models still cost nothing). OpenRouter also allows 20 a minute; the app keeps to it.")
}

// --------------------------------------------------------------- package

private fun JsonObject.arr(k: String): JsonArray = if (has(k) && get(k).isJsonArray) getAsJsonArray(k) else JsonArray()
private fun JsonObject.obj(k: String): JsonObject? = if (has(k) && get(k).isJsonObject) getAsJsonObject(k) else null
private fun JsonObject.s(k: String): String? = get(k)?.takeIf { it.isJsonPrimitive }?.asString
private fun JsonObject.d(k: String): Double? = get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble

private fun pct(v: Double?) = v?.let { "${Math.round(it * 100)}%" } ?: "—"

/** The crew's package: its changes to tick, what the app refused, the odds, the week, and who said what. */
@Composable
internal fun CrewProposal(nav: NavigatorViewModel, p: NavProposals.View, ticked: MutableState<Set<Int>>) {
    val crew = p.crew() ?: return
    // What you said and what the Navigator answers, as in the conversation.
    Panel(null, Palette.Plum) {
        crew.s("message")?.let { Bubble(it, mine = true) }
        crew.s("reply")?.let { Bubble(it, mine = false) }
    }

    val groups = crew.arr("groups")
    Panel("The changes", Palette.Teal) {
        if (p.status() == "PENDING")
            Hint("Each change is approved or left out as a whole.")
        groups.forEachIndexed { i, el ->
            val gr = el.asJsonObject
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(top = 6.dp)) {
                if (p.status() == "PENDING")
                    Checkbox(checked = i in ticked.value, onCheckedChange = { on ->
                        ticked.value = if (on) ticked.value + i else ticked.value - i
                    })
                Column(Modifier.weight(1f)) {
                    Text(gr.s("title") ?: "", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                    gr.s("why")?.let { Hint(it) }
                    gr.arr("ops").forEach { ix -> p.ops().getOrNull(ix.asInt)?.let { OpLine(it) } }
                }
            }
        }
        val refused = crew.arr("rejected")
        if (refused.size() > 0) {
            Text(tr("Refused by the app's checks"), style = MaterialTheme.typography.labelMedium,
                color = Palette.Brick, modifier = Modifier.padding(top = 8.dp))
            refused.forEach { r -> Hint("✗ " + (r.asJsonObject.s("change") ?: "") + ": " + (r.asJsonObject.s("reason") ?: "")) }
        }
        val qs = crew.arr("questions")
        if (qs.size() > 0) {
            Text(tr("What it needs to know from you"), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
            qs.forEach { Hint("? " + it.asString) }
            Hint("Answer in the conversation below or in Tell the Navigator.")
        }
        EffectLine(crew)
    }
}

/** The measured effect in one line: goals pushed below their minimum, goal hours, time above the mental line. */
@Composable
private fun EffectLine(crew: JsonObject) {
    val breaches = crew.arr("odds").count { it.asJsonObject.get("breaks_floor")?.asBoolean == true }
    val now = crew.obj("week_now"); val with = crew.obj("week_with_it")
    val parts = mutableListOf<String>()
    if (crew.arr("locks").size() > 0) parts += tr("Chronos would stop planning")
    parts += if (breaches > 0) "$breaches " + tr(if (breaches == 1) "goal below its minimum" else "goals below their minimum")
             else tr("no goal pushed below its minimum")
    if (now != null && with != null) {
        val h0 = now.d("goal_hours_total"); val h1 = with.d("goal_hours_total")
        if (h0 != null && h1 != null && Math.abs(h1 - h0) >= 0.25) parts += tr("goal hours this week") + " " + trimNumber(h0) + " → " + trimNumber(h1)
        val m0 = now.d("minutes_above_mental_line"); val m1 = with.d("minutes_above_mental_line")
        if (m0 != null && m1 != null && Math.abs(m1 - m0) >= 15) parts += tr("above the mental line") + " " + m0.toInt() + " → " + m1.toInt() + " " + tr("min")
    }
    val bad = breaches > 0 || crew.arr("locks").size() > 0
    Text(parts.joinToString("  ·  "), style = MaterialTheme.typography.labelMedium,
        color = if (bad) Palette.Brick else Palette.Moss, modifier = Modifier.padding(top = 8.dp))
}

/** The package's details, folded until tapped: the odds, the simulated week, what each role said. */
@Composable
internal fun CrewDetails(p: NavProposals.View) {
    val crew = p.crew() ?: return
    // the odds
    val odds = crew.arr("odds")
    val breaches = odds.count { it.asJsonObject.get("breaks_floor")?.asBoolean == true }
    Folded("Your goals' odds", if (odds.size() == 0) tr("No deadline's odds moved.")
           else "${odds.size()} " + tr("goals moved") + if (breaches > 0) "  ·  $breaches " + tr("below their minimum") else "",
           Palette.Moss, p.id() + "odds") {
        if (odds.size() == 0) Hint("No deadline's odds moved.")
        odds.forEach { el ->
            val o = el.asJsonObject
            val before = o.obj("before"); val after = o.obj("after")!!
            val breach = o.get("breaks_floor")?.asBoolean == true
            Column(Modifier.padding(vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text((o.s("title") ?: o.s("goal") ?: ""), style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f), fontWeight = if (breach) FontWeight.Bold else FontWeight.Normal)
                    PriorityTag(o.s("priority") ?: "optional")
                }
                if (before != null) BandRow(tr("now"), before, o.d("floor"), Palette.Muted)
                else Hint(tr("A new goal"))
                BandRow(tr("with it"), after, o.d("floor"), if (breach) Palette.Brick else Palette.Moss)
                if (breach) Text(tr("Below its floor of") + " " + pct(o.d("floor")) + " " + tr("at the low end of the band"),
                    style = MaterialTheme.typography.labelSmall, color = Palette.Brick)
            }
        }
        Hint("The middle figure is the chance of meeting the deadline; the range is the 80% band — how far that chance " +
             "could be off while the log has not pinned your speed, adherence and goal hours down. Critical and important " +
             "goals must keep the low end above their floor, or the package must say what makes up for it.")
    }

    // the week
    val now = crew.obj("week_now"); val with = crew.obj("week_with_it")
    if (now != null && with != null) Folded("The coming week in Chronos",
        trimNumber(now.d("goal_hours_total") ?: 0.0) + " → " + trimNumber(with.d("goal_hours_total") ?: 0.0) + " " + tr("goal hours"),
        Palette.Ochre, p.id() + "week") {
        val locks = crew.arr("locks")
        if (locks.size() > 0) {
            Text(tr("Chronos would stop planning until this is fixed in Setup") + ":", style = MaterialTheme.typography.labelMedium,
                color = Palette.Brick)
            locks.forEach { Text("· " + it.asString, style = MaterialTheme.typography.bodySmall, color = Palette.Brick) }
        }
        WeekLine(tr("Goal hours"), now.d("goal_hours_total"), with.d("goal_hours_total"), "h", upIsGood = true)
        WeekLine(tr("Hours that could not be placed"), now.d("unplaced_hours"), with.d("unplaced_hours"), "h", upIsGood = false)
        WeekLine(tr("Minutes above the mental line"), now.d("minutes_above_mental_line"), with.d("minutes_above_mental_line"), "min", upIsGood = false)
        WeekLine(tr("Minutes above the physical line"), now.d("minutes_above_physical_line"), with.d("minutes_above_physical_line"), "min", upIsGood = false)
        val a = now.obj("goal_hours"); val b = with.obj("goal_hours")
        if (a != null && b != null) {
            (a.keySet() + b.keySet()).forEach { k ->
                val x = a.d(k) ?: 0.0; val y = b.d(k) ?: 0.0
                if (Math.abs(x - y) >= 0.25) Hint("$k: ${trimNumber(x)} → ${trimNumber(y)} h")
            }
        }
        Hint("Chronos planned the next seven days with and without the package, and arranged each day; tiredness is " +
             "what its model of you expects.")
    }

    // who said what
    val roles = crew.arr("roles")
    if (roles.size() > 0) Folded("What each role said", roles.size().toString() + " " + tr("roles"), Palette.Steel, p.id() + "roles") {
        run {
            roles.forEach { el ->
                val r = el.asJsonObject
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text(stepMark(r.s("status") ?: "") + "  " + tr(roleLabel(r.s("role") ?: "")) + (r.s("model")?.let { "  ·  $it" } ?: ""),
                        style = MaterialTheme.typography.labelMedium, color = stepColor(r.s("status") ?: ""))
                    r.s("said")?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    r.s("error")?.let { Hint(it) }
                }
            }
            val responses = crew.arr("responses")
            if (responses.size() > 0) {
                Text(tr("The coordinator's answers to the critic"), style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 6.dp))
                responses.forEach { el ->
                    val r = el.asJsonObject
                    val ok = r.get("accepted")?.asBoolean == true
                    Text((if (ok) "✓ " else "✗ ") + (r.s("objection") ?: ""), style = MaterialTheme.typography.bodySmall,
                        color = if (ok) Palette.Moss else Palette.Sand)
                    r.s("why")?.let { Hint(it) }
                }
            }
        }
    }
}

@Composable
internal fun CrewDecide(nav: NavigatorViewModel, p: NavProposals.View, ticked: MutableState<Set<Int>>) {
    if (p.status() != "PENDING") return
    val n = p.crew()?.arr("groups")?.size() ?: 0
    Panel {
        Row {
            Button(onClick = { nav.approveCrew(p.id(), ticked.value); nav.openProposal = null },
                enabled = !nav.busy && ticked.value.isNotEmpty()) {
                Text(tr("Approve") + " " + ticked.value.size + "/" + n)
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { nav.decide(p.id(), false); nav.openProposal = null }, enabled = !nav.busy) { Text("Reject") }
        }
        Hint("Approving writes the ticked changes into your goal graph and into Chronos, and rebuilds the plan. " +
             "Archived activities keep their log and can be brought back in Setup.")
    }
}

@Composable
private fun BandRow(label: String, b: JsonObject, floor: Double?, color: Color) {
    val p = b.d("p") ?: 0.0; val lo = b.d("low") ?: p; val hi = b.d("high") ?: p
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(52.dp))
        Box(Modifier.weight(1f).height(12.dp)) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val w = size.width; val h = size.height
                drawRect(color.copy(alpha = 0.10f))
                drawRect(color.copy(alpha = 0.35f), topLeft = androidx.compose.ui.geometry.Offset((lo * w).toFloat(), 0f),
                    size = androidx.compose.ui.geometry.Size(((hi - lo) * w).toFloat().coerceAtLeast(2f), h))
                drawRect(color, topLeft = androidx.compose.ui.geometry.Offset((p * w).toFloat() - 1.5f, 0f),
                    size = androidx.compose.ui.geometry.Size(3f, h))
                floor?.let { f ->
                    drawRect(Palette.Brick, topLeft = androidx.compose.ui.geometry.Offset((f * w).toFloat() - 1f, 0f),
                        size = androidx.compose.ui.geometry.Size(2f, h))
                }
            }
        }
        Text(" ${pct(p)} (${pct(lo)}–${pct(hi)})", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(104.dp))
    }
}

@Composable
private fun WeekLine(label: String, a: Double?, b: Double?, unit: String, upIsGood: Boolean) {
    if (a == null || b == null) return
    val d = b - a
    val color = if (Math.abs(d) < 0.05) Palette.Muted else if ((d > 0) == upIsGood) Palette.Moss else Palette.Brick
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text("${trimNumber(a)} → ${trimNumber(b)} $unit", style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@Composable
private fun PriorityTag(name: String) {
    val color = when { name.startsWith("critical") -> Palette.Brick; name.startsWith("important") -> Palette.Ochre; else -> Palette.Muted }
    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.13f)).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(tr(name.substringBefore(" ").replaceFirstChar { it.uppercase() }), style = MaterialTheme.typography.labelSmall, color = color)
    }
}

// --------------------------------------------------------------- priority

/** How much a goal's deadline matters, on its card. */
@Composable
internal fun PriorityRow(nav: NavigatorViewModel, n: Node) {
    if (n.deadline == null) return
    val now = Priority.of(n)
    Column(Modifier.padding(top = 4.dp)) {
        Text(tr("How much it matters") + if (Priority.chosen(n)) "" else " (" + tr("default") + ")",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRowSimple {
            listOf(Priority.CRITICAL to "Critical", Priority.IMPORTANT to "Important", Priority.OPTIONAL to "Optional").forEach { (v, label) ->
                Pill(tr(label), now == v) { if (!nav.busy) nav.setPriority(n.id, v) }
            }
        }
        val floor = Priority.floor(nav.graph ?: return, now)
        if (floor > 0) Hint(tr("A change may not push its odds below") + " " + pct(floor) + " " + tr("(low end of the 80% band)"))
    }
}
