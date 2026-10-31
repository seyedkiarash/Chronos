package com.chronos.ui.navigator

import com.chronos.ui.*
import com.chronos.ui.common.*
import com.chronos.ui.setup.*
import com.chronos.ui.horizon.*
import com.chronos.ui.day.*
import com.chronos.ui.log.*

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.chronos.navigator.llm.Op
import com.chronos.navigator.llm.client.LlmClient
import com.chronos.navigator.llm.client.Profile
import com.chronos.navigator.llm.client.ProviderKind
import java.time.Instant
import java.time.ZoneId

/* The LLM's side of the Navigator tab: the proposals inbox and the settings. */

@Composable
internal fun ProposalsPanel(nav: NavigatorViewModel) {
    val pending = nav.proposals.filter { it.status() == "PENDING" }
    val past = nav.proposals.filter { it.status() != "PENDING" }
    if (pending.isEmpty() && past.isEmpty()) return
    var showPast by remember { mutableStateOf(false) }
    Panel("Proposals", Palette.Plum) {
        Hint("What the LLM proposed. Nothing here has changed anything yet: approve to save the values " +
             "with the LLM as their source, or reject.")
        if (pending.isEmpty()) Hint("Nothing is waiting.")
        ProposalGroups(nav, pending)
        if (past.isNotEmpty()) {
            TextButton(onClick = { showPast = !showPast }) {
                Text(if (showPast) "Hide past proposals" else "Past proposals")
            }
            if (showPast) ProposalGroups(nav, past.take(30))
        }
    }
}

/** Options from one answer sit under one heading: what woke the LLM, when, and its summary. */
@Composable
private fun ProposalGroups(nav: NavigatorViewModel, items: List<com.chronos.navigator.store.NavProposals.View>) {
    items.groupBy { it.callId() ?: it.id() }.values.forEach { group ->
        val first = group.first()
        val at = Instant.ofEpochMilli(first.createdAt()).atZone(ZoneId.systemDefault()).toLocalDate()
        Text(tr(triggerLabel(first.trigger(), first.task())) + "  ·  " + dateText(at),
            style = MaterialTheme.typography.labelMedium, color = Palette.Plum, modifier = Modifier.padding(top = 10.dp))
        first.summary()?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (group.size > 1) Hint("Options: approve the ones you want; each was forecast by the app to measure its effect.")
        group.forEach { ProposalCard(nav, it) }
    }
}

/** Why the LLM was asked, in words. */
internal fun triggerLabel(trigger: String?, task: String?): String = when (trigger) {
    "prob_drop" -> "A deadline became unlikely"
    "prob_jump" -> "A deadline became much likelier"
    "expand_due" -> "Time to break a node down"
    "external_window" -> "A gate's window changed"
    "manual_change" -> "You changed your goals"
    "monthly_review" -> "Monthly review"
    "refine" -> "A value refined by the model"
    "discussion" -> "Revised after your conversation"
    "you" -> "You told the Navigator"
    "weekly_review" -> "Weekly review of the levers"
    "your_log" -> "Read from your log"
    else -> when (task) {
        "EXPAND_NODE" -> "Breakdown you asked for"
        "REPLAN_SUGGEST" -> "Ways to recover you asked for"
        else -> "Estimate you asked for"
    }
}

@Composable
private fun ProposalCard(nav: NavigatorViewModel, p: com.chronos.navigator.store.NavProposals.View) {
    val uri = LocalUriHandler.current
    val g = nav.graph
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(listOfNotNull(p.title(), p.subject()?.takeIf { p.title() == null }).joinToString("  ·  ").ifBlank { p.task() ?: "" } +
             "  ·  " + tr(statusLabel(p.status())),
            style = MaterialTheme.typography.labelMedium,
            color = when (p.status()) { "PENDING" -> Palette.Plum; "APPROVED" -> Palette.Moss; else -> Palette.Brick })
        if (p.title() != null && p.callId() == null) p.summary()?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        p.rationale()?.let { Hint(it) }
        p.reason()?.let { Text(tr("Rejected by the checks") + ": " + it, style = MaterialTheme.typography.labelSmall, color = Palette.Brick) }
        p.effects().forEach { e ->
            val d = Math.round((e.after() - e.before()) * 100)
            Text("P(" + (g?.node(e.nodeId())?.title ?: e.nodeId()) + "): " + Math.round(e.before() * 100) + "% → " +
                 Math.round(e.after() * 100) + "%  (" + (if (d >= 0) "+" else "") + d + ")",
                style = MaterialTheme.typography.labelSmall,
                color = if (d > 0) Palette.Moss else if (d < 0) Palette.Brick else Palette.Muted)
        }
        if (p.effects().isNotEmpty()) Hint("Measured by the app's own forecast, with and without this, on the same simulated futures.")
        if (p.expansion()) ExpansionLines(p) else p.ops().forEach { OpLine(it) }
        if (p.sources().isNotEmpty()) {
            Text(tr("Sources"), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
            p.sources().forEach { s ->
                Text(s.url(), style = MaterialTheme.typography.labelSmall, color = Palette.Steel,
                    modifier = Modifier.clickable { if (webLink(s.url())) runCatching { uri.openUri(s.url()) } })
            }
        }
        if (p.checks().isNotEmpty()) Hint(tr("Checks passed") + ": " + p.checks().joinToString(", "))
        if (p.status() == "PENDING") {
            var copyFrom by remember(p.id()) {
                mutableStateOf(nav.nodeActivities[p.subject()]?.firstOrNull()?.let { a ->
                    nav.activities.firstOrNull { it.trim().lowercase() == a }
                })
            }
            if (p.expansion()) {
                Choice("Set up the new tasks in Chronos like", copyFrom, nav.activities.map { it to it },
                    hint = "Each task becomes its own goal and activity, with this activity's windows, block lengths " +
                           "and load. It gets hours only from a week you accept.") { copyFrom = it }
            }
            Row {
                Button(onClick = { nav.decide(p.id(), true, copyFrom) },
                    enabled = !nav.busy && (!p.expansion() || copyFrom != null)) {
                    Text(if (p.noteOnly()) "Noted" else "Approve")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { nav.decide(p.id(), false) }, enabled = !nav.busy) { Text("Reject") }
            }
        }
        HorizontalDivider(Modifier.padding(top = 6.dp))
    }
}

internal fun statusLabel(s: String) = when (s) {
    "PENDING" -> "waiting for you"; "APPROVED" -> "approved"; "REJECTED" -> "rejected by you"
    "INVALID" -> "failed the checks"; "REVISED" -> "replaced by a revision"; "OUTDATED" -> "outdated"; else -> s
}

/** A breakdown: the new tasks in order, with their hours, dates and what each waits for. */
@Composable
internal fun ExpansionLines(p: com.chronos.navigator.store.NavProposals.View) {
    p.ops().filter { it.kind() == Op.NODE_ADD && it.node() != null }.forEach { op ->
        val n = op.node()
        val effort = n.getAsJsonObject("effort")
        val window = n.getAsJsonObject("window")
        Column(Modifier.padding(top = 4.dp)) {
            Text("• " + n.get("title").asString, style = MaterialTheme.typography.bodySmall)
            Hint(listOfNotNull(
                effort?.let { trimNumber(it.get("total_h_p50").asDouble) + "–" + trimNumber(it.get("total_h_p90").asDouble) + " h" },
                window?.get("earliest")?.asString?.let { tr("from") + " " + dateText(java.time.LocalDate.parse(it)) },
                window?.get("deadline")?.asString?.let { tr("due") + " " + dateText(java.time.LocalDate.parse(it)) },
                n.get("granularity")?.asString?.let { tr(it) },
                n.get("expand_at")?.asString?.let { tr("To be broken down on") + " " + dateText(java.time.LocalDate.parse(it)) },
                n.getAsJsonObject("chronos")?.get("activity")?.asString?.let { tr("activity") + " \"" + it + "\"" }
            ).joinToString("  ·  "))
            n.get("deliverable")?.asString?.let { Hint(tr("Done when") + ": " + it) }
            n.getAsJsonArray("depends_on")?.takeIf { it.size() > 0 }?.let { d ->
                Hint(tr("After") + ": " + d.joinToString(", ") { it.asString })
            }
        }
    }
    p.ops().firstOrNull { it.kind() == Op.NODE_EDIT }?.let { Hint(it.note()) }
}

/** One operation as "what → before → after", in words for the shapes the LLM may propose. */
@Composable
internal fun OpLine(op: Op) {
    val v = op.values()
    val key = op.key()
    val node = key.removePrefix("node.").substringBeforeLast('.')
    fun day(k: String) = v[k]?.let { java.time.LocalDate.ofEpochDay(it.toLong()) }
    val what = when {
        op.isChronos() || key.endsWith(".priority") || op.kind() == Op.NODE_ADD || op.kind() == Op.NODE_EDIT -> op.note() ?: key
        op.kind() == Op.DISCUSS -> tr("To raise") + " (" + key.removePrefix("discuss.") + "): " + (op.note() ?: "")
        key.endsWith(".span") -> tr("Regime") + " " + key.removePrefix("regime.").removeSuffix(".span") + ": " +
            java.time.LocalDate.ofEpochDay(v["from"]!!.toLong()) + " → " + java.time.LocalDate.ofEpochDay(v["to"]!!.toLong())
        key.endsWith(".mix") -> tr("Mix of days") + " " + key.removePrefix("regime.").removeSuffix(".mix") + ": " +
            listOf("IDEAL", "BUSY", "STRESS").joinToString(" / ") { "${Math.round(100 * (v[it] ?: 0.0))}%" } +
            (v["strength"]?.let { "  ·  " + tr("worth") + " " + trimNumber(it) + " " + tr("days") } ?: "")
        key.endsWith(".effort") -> tr("Effort") + " " + node + ": " +
            trimNumber(v["p50"] ?: 0.0) + "–" + trimNumber(v["p90"] ?: 0.0) + " " + tr("hours (median to 90th percentile)")
        key.endsWith(".success") -> {
            val a = v["a"] ?: 0.0; val b = v["b"] ?: 0.0
            tr("Chance of success") + " " + node + ": " +
                Math.round(100 * a / (a + b)) + "%  ·  " + tr("worth") + " " + trimNumber(a + b) + " " + tr("cases")
        }
        key.endsWith(".floor") -> tr("Weekly floor") + " " + node + ": " + trimNumber(v["value"] ?: 0.0) + " h"
        key.endsWith(".min_weekly") -> tr("At least") + " " + trimNumber(v["value"] ?: 0.0) + " " + tr("h a week on") + " " +
            node + ", " + day("from")?.let { dateText(it) } + " – " + day("until")?.let { dateText(it) }
        key.endsWith(".pause") -> tr("Pause") + " " + node + ": " + day("from")?.let { dateText(it) } + " – " +
            day("until")?.let { dateText(it) }
        key.endsWith(".deadline") -> tr("New deadline for") + " " + node + ": " + day("value")?.let { dateText(it) }
        else -> key + ": " + v.entries.joinToString { "${it.key}=${trimNumber(it.value)}" }
    }
    Text("• $what", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
    if (op.before().isNotEmpty() && !key.endsWith(".deadline") && !op.isChronos() && !key.endsWith(".priority"))
        Hint(tr("Replaces") + ": " + op.before().entries.joinToString { "${it.key}=${trimNumber(it.value)}" })
    if (key.endsWith(".deadline")) op.before()["value"]?.let {
        Hint(tr("Replaces") + ": " + dateText(java.time.LocalDate.ofEpochDay(it.toLong())))
    }
    if (op.discussOnly() && op.kind() != Op.DISCUSS)
        Text(tr("Your advisor owns this node: raise it with them; it will not be changed here."),
            style = MaterialTheme.typography.labelSmall, color = Palette.Sand)
}

// ------------------------------------------------------------- the triggers

@Composable
internal fun TriggersPanel(nav: NavigatorViewModel) {
    nav.llmRevision
    var open by remember { mutableStateOf(false) }
    val queued = nav.calls.count { it.status == "QUEUED" }
    Panel("Automatic checks", Palette.Ochre) {
        Hint("Every night after the forecast the Navigator checks six triggers. When one fires, the LLM is asked " +
             "on its own — within your budget and daily limit — and its answer waits in the proposals inbox.")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Ask the LLM automatically", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Switch(checked = nav.llm.autoTriggers, onCheckedChange = { nav.setAutoTriggers(it) })
        }
        if (queued > 0) Hint(tr("Waiting to be sent") + ": " + queued)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { nav.checkTriggers() }, enabled = !nav.busy) { Text("Check now") }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { open = !open }) { Text(if (open) "Hide" else "What they are") }
        }
        if (!open) return@Panel
        listOf(
            "prob_drop" to "A deadline's chance falls below 60%: ways to recover it. It does not fire again for that deadline until the chance has been back above 70%.",
            "prob_jump" to "A deadline's chance rises by more than 15 points within 7 days: is there room to pull other work forward?",
            "expand_due" to "A coarse node reaches its breakdown date: it is broken into smaller tasks.",
            "external_window" to "A gate's window opens or its deadline changes: its odds are looked at again, with a web search when the provider can.",
            "manual_change" to "You changed goals, deadlines, commitments or answers: the graph is checked for what the change broke.",
            "monthly_review" to "The first Wednesday of each month: a review of the whole picture, and the questions worth asking you."
        ).forEach { (kind, what) ->
            val last = nav.triggers.filter { it.kind == kind && it.firedAt > 0 }.maxByOrNull { it.firedAt }
            Column(Modifier.padding(vertical = 4.dp)) {
                Text(tr(triggerLabel(kind, null)), style = MaterialTheme.typography.labelMedium)
                Hint(what)
                Hint(if (last == null) tr("Not fired yet") else tr("Last fired") + " " +
                    dateText(Instant.ofEpochMilli(last.firedAt).atZone(ZoneId.systemDefault()).toLocalDate()) +
                    (if (last.node != null && last.node != "*") "  ·  " + last.node else ""))
            }
        }
    }
}

// ----------------------------------------------------------- the questions

@Composable
internal fun QuestionsPanel(nav: NavigatorViewModel) {
    val saved = nav.questions
    Panel("Answers that would help most", Palette.Steel) {
        Hint("The numbers you gave that the forecast is most sensitive to: each is tried at the low and the high " +
             "end of what is believed now, and the deadlines' weighted odds compared. A better answer to the top " +
             "ones sharpens the forecast most.")
        if (saved == null || saved.items.isEmpty()) {
            Hint(if (saved == null) "Not ranked yet. It is ranked at each monthly review, or now (it may take a minute):"
                 else "Nothing you gave moves the forecast much.")
        } else {
            Hint(tr("Ranked") + " " + dateText(java.time.LocalDate.parse(saved.asOf)))
            saved.items.forEach { q -> QuestionRow(nav, q) }
        }
        if (saved != null && saved.items.isNotEmpty()) {
            OutlinedButton(onClick = { nav.refineAll(saved.items.map { it.key() to it.text() }) }, enabled = !nav.busy) {
                Text("Let the model estimate all of these")
            }
            Hint("Each becomes a proposal you can open, question and approve. Those that do not fit in today's " +
                 "calls wait in the queue for the next ones.")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { nav.rankQuestions() }, enabled = !nav.busy) {
                Text(if (saved == null) "Rank them now" else "Rank again")
            }
            if (nav.computing) CircularProgressIndicator(Modifier.padding(start = 8.dp).size(18.dp))
        }
    }
}

@Composable
private fun QuestionRow(nav: NavigatorViewModel, q: com.chronos.navigator.forecast.InfoValue.Ranked) {
    var open by remember(q.key()) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        val g = nav.graph
        val n = g?.node(q.subject())
        val r = g?.regime(q.subject())
        val text = when (q.kind()) {
            "effort" -> tr("How many hours will this take?") + " " + (n?.title ?: q.subject()) + "  ·  " + tr("now") + " " +
                trimNumber(n?.effortP50 ?: 0.0) + "–" + trimNumber(n?.effortP90 ?: 0.0) + " h"
            "success" -> tr("How likely is this?") + " " + (n?.title ?: q.subject()) +
                (if (n?.successA != null && n.successB != null) "  ·  " + tr("now about") + " " +
                    Math.round(100 * n.successA / (n.successA + n.successB)) + "%" else "")
            else -> tr("What will days be like in this stretch?") + " " + q.subject() +
                (r?.let { "  ·  " + dateText(it.from) + " – " + dateText(it.to) } ?: "")
        }
        Text(text, style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Meter(q.impact().coerceAtMost(1.0).toFloat(), Palette.Steel)
        }
        Hint(tr("Could move the weighted odds of your deadlines by") + " " + trimNumber(Math.round(q.impact() * 100) / 100.0))
        Row {
            TextButton(onClick = { open = !open }) { Text(if (open) "Close" else "Answer this") }
            TextButton(onClick = { nav.refine(q.key(), q.text()) }, enabled = !nav.busy) { Text("Let the model estimate it") }
        }
        if (open) when (q.kind()) {
            "effort" -> {
                var p50 by remember { mutableStateOf<Double?>(null) }
                var p90 by remember { mutableStateOf<Double?>(null) }
                NumberField("Median (hours)", p50) { p50 = it }
                NumberField("90th percentile (hours)", p90) { p90 = it }
                val ok = p50 != null && p90 != null && p50!! > 0 && p90!! >= p50!!
                Button(onClick = {
                    nav.answerOver(q.key(), com.chronos.navigator.model.Kinds.Dist.LOGNORMAL, mapOf("p50" to p50!!, "p90" to p90!!))
                    open = false
                }, enabled = ok) { Text("Save") }
            }
            "success" -> {
                var pct by remember { mutableStateOf<Double?>(null) }
                var k by remember { mutableStateOf<Double?>(null) }
                NumberField("Probability (%)", pct) { pct = it }
                NumberField("Sureness (cases)", k) { k = it }
                val ok = pct != null && k != null && pct!! > 0 && pct!! < 100 && k!! > 0
                Button(onClick = {
                    val p = pct!! / 100
                    nav.answerOver(q.key(), com.chronos.navigator.model.Kinds.Dist.BETA, mapOf("a" to p * k!!, "b" to (1 - p) * k!!))
                    open = false
                }, enabled = ok) { Text("Save") }
            }
            "mix" -> {
                var ideal by remember { mutableStateOf<Double?>(null) }
                var busy by remember { mutableStateOf<Double?>(null) }
                var stress by remember { mutableStateOf<Double?>(null) }
                var k by remember { mutableStateOf<Double?>(null) }
                NumberField("Ideal days (%)", ideal) { ideal = it }
                NumberField("Busy days (%)", busy) { busy = it }
                NumberField("Stress days (%)", stress) { stress = it }
                NumberField("Sureness (days)", k) { k = it }
                val sum = (ideal ?: 0.0) + (busy ?: 0.0) + (stress ?: 0.0)
                val ok = ideal != null && busy != null && stress != null && k != null && k!! > 0 && kotlin.math.abs(sum - 100) < 0.5
                Button(onClick = {
                    nav.answerOver(q.key(), com.chronos.navigator.model.Kinds.Dist.DIRICHLET,
                        mapOf("IDEAL" to ideal!! / 100, "BUSY" to busy!! / 100, "STRESS" to stress!! / 100, "strength" to k!!))
                    open = false
                }, enabled = ok) { Text("Save") }
            }
        }
        HorizontalDivider(Modifier.padding(top = 4.dp))
    }
}

@Composable
internal fun LlmPanel(nav: NavigatorViewModel) {
    nav.llmRevision
    var open by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Profile?>(null) }
    var adding by remember { mutableStateOf(false) }
    val active = nav.activeProfile
    Panel("LLM", Palette.Teal) {
        Hint("Used when you ask, when a trigger fires (see Automatic checks), and by the crew. It never sees your log " +
             "itself — the crew gets the app's summary of it — and what it says is a proposal you approve or reject.")
        Hint(tr("Spent this month") + ": $" + "%.2f".format(nav.spentThisMonth) + " / $" +
             "%.2f".format(nav.llm.monthlyCapUsd))
        Text(if (active == null) tr("No provider set up yet")
             else tr("In use") + ": " + active.label + " · " + active.model +
                  (if (active.id in nav.keyed) "" else "  ·  " + tr("no key")),
            style = MaterialTheme.typography.bodySmall,
            color = if (active != null && active.id in nav.keyed) Palette.Moss else Palette.Brick)
        TextButton(onClick = { open = !open }) { Text(if (open) "Hide" else "Settings") }
        if (!open) return@Panel

        Text(tr("Providers"), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
        nav.profiles.forEach { p ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = p.id == active?.id, onClick = { nav.useProfile(p.id) })
                Column(Modifier.weight(1f)) {
                    Text(p.label, style = MaterialTheme.typography.bodySmall)
                    Text(p.model.ifBlank { tr("no model chosen") } + "  ·  " +
                         (if (p.free) tr("free") else "$" + trimNumber(p.priceIn) + " / $" + trimNumber(p.priceOut)) +
                         "  ·  " + (if (p.id !in nav.keyed) tr("no key")
                            else (nav.keyTails[p.id]?.size ?: 1).let { n -> if (n > 1) tr("{} keys").replace("{}", n.toString()) else tr("key saved") }),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { editing = p; adding = false }) { Text("Edit") }
            }
        }
        if (editing == null && !adding)
            OutlinedButton(onClick = { adding = true }) { Text("Add a provider") }
        if (adding) {
            Text(tr("Which provider?"), style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 6.dp))
            LlmClient.PRESETS.forEach { pr ->
                Row(Modifier.fillMaxWidth().clickable {
                    val (pin, pout) = (pr.priceIn ?: -1.0) to (pr.priceOut ?: -1.0)
                    editing = Profile(java.util.UUID.randomUUID().toString(), pr.key, pr.label, pr.kind,
                        pr.baseUrl, pr.model, pin, pout)
                    adding = false
                }.padding(vertical = 6.dp)) {
                    Column {
                        Text(pr.label + if (pr.free) "  ·  " + tr("free tier") else "",
                            style = MaterialTheme.typography.bodySmall)
                        Hint(pr.note)
                    }
                }
            }
            TextButton(onClick = { adding = false }) { Text("Cancel") }
        }
        editing?.let { p -> ProfileEditor(nav, p) { editing = null } }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        CrewSetup(nav)

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        NumberField("Monthly budget (dollars)", nav.llm.monthlyCapUsd,
            hint = "When a question would pass it, it is not sent.") { v -> if (v != null && v >= 0) nav.setMonthlyCap(v) }
        IntField("Calls per day at most", nav.llm.dailyCalls) { v -> if (v != null && v >= 0) nav.setDailyCalls(v) }

        if (nav.calls.isNotEmpty()) {
            Text(tr("Recent calls"), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
            nav.calls.take(6).forEach { c ->
                val at = Instant.ofEpochMilli(c.createdAt).atZone(ZoneId.systemDefault()).toLocalDate()
                Hint(dateText(at) + "  ·  " + (c.model ?: "") + "  ·  " +
                     (c.task?.split("|")?.drop(1)?.joinToString(" ") ?: "") + "  ·  " +
                     (c.status ?: "") + "  ·  $" + "%.3f".format(c.costUsd) +
                     (c.error?.let { "  ·  $it" } ?: ""))
            }
        }
    }
}

/** One provider's settings: name, address, model, prices and key. A new one is saved only from here. */
@Composable
private fun ProfileEditor(nav: NavigatorViewModel, start: Profile, close: () -> Unit) {
    val preset = LlmClient.preset(start.preset)
    var label by remember(start.id) { mutableStateOf(start.label) }
    var baseUrl by remember(start.id) { mutableStateOf(start.baseUrl) }
    var model by remember(start.id) { mutableStateOf(start.model) }
    var priceIn by remember(start.id) { mutableStateOf(start.priceIn.takeIf { it >= 0 }) }
    var priceOut by remember(start.id) { mutableStateOf(start.priceOut.takeIf { it >= 0 }) }
    var key by remember(start.id) { mutableStateOf("") }
    val claude = start.kind == ProviderKind.ANTHROPIC
    val draft = start.copy(label = label.trim(), baseUrl = baseUrl.trim(), model = model.trim(),
        priceIn = priceIn ?: -1.0, priceOut = priceOut ?: -1.0)

    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(preset.label, style = MaterialTheme.typography.titleSmall, color = Palette.Teal)
        Hint(preset.note)
        if (preset.free)
            Hint("A free tier may use what you send to improve the provider's models. Each question sends " +
                 "only the snapshot it needs — dates, titles, the numbers you stated — never your log.")
        TextField("Name", label) { label = it }
        if (start.preset == "custom") TextField("Address (base URL)", baseUrl,
            hint = "The part before /chat/completions, e.g. https://api.example.com/v1") { baseUrl = it }

        OutlinedTextField(
            value = key, onValueChange = { key = it.trim() },
            label = { Text("API key", style = MaterialTheme.typography.labelSmall) },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        Hint((if (preset.keyPage.isNotBlank()) tr("Made at") + " " + preset.keyPage + ". " else "") +
             tr(if (start.id in nav.keyed) "A key is saved; leave this empty to keep it."
                else "Stored encrypted with a key kept in the phone's Keystore."))
        // Several keys for one provider (your request of 27 Oct 2026): requests sent together go out on
        // different keys, and when one reaches its limit the next carries on.
        if (start.id in nav.keyed) {
            val tails = nav.keyTails[start.id].orEmpty()
            Text(tr("Keys") + " (" + tails.size + ")", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
            tails.forEachIndexed { i, t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text((i + 1).toString() + ".  " + t, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    if (tails.size > 1) TextButton(onClick = { nav.removeKey(start.id, i) }) { Text("×") }
                }
            }
            var more by remember(start.id) { mutableStateOf("") }
            OutlinedTextField(
                value = more, onValueChange = { more = it.trim() },
                label = { Text(tr("Another key"), style = MaterialTheme.typography.labelSmall) },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(onClick = { nav.addKey(start.id, more); more = "" }, enabled = more.isNotBlank() && !nav.busy) { Text("Add the key") }
            Hint("Keys from other accounts of the same provider: each has its own limits, so together they answer more, " +
                 "and faster — the crew's roles and queued questions go out on different keys at once.")
        }

        if (claude) {
            Choice("Model", model, LlmClient.PRICES.keys.map { m ->
                val (i, o) = LlmClient.PRICES.getValue(m)
                m to "$m  ($" + trimNumber(i) + " / $" + trimNumber(o) + " " + tr("per million tokens") + ")"
            }) { m -> model = m; LlmClient.PRICES[m]?.let { priceIn = it.first; priceOut = it.second } }
        } else {
            TextField("Model", model, hint = "Type a model name, or fetch the list your key can use.") { model = it }
            OutlinedButton(onClick = { nav.fetchModels(draft, key) },
                enabled = !nav.busy && baseUrl.isNotBlank()) { Text("Fetch models") }
            if (nav.fetchedModels.isNotEmpty())
                Choice("Pick a model", model, nav.fetchedModels.map { it to it }) { model = it }
            NumberField("Input price per million tokens ($)", priceIn,
                hint = "0 on a free tier. If your key is billed, use the provider's price for this model.") { priceIn = it }
            NumberField("Output price per million tokens ($)", priceOut) { priceOut = it }
        }

        val ok = label.isNotBlank() && model.isNotBlank() && baseUrl.startsWith("https://") &&
            priceIn != null && priceIn!! >= 0 && priceOut != null && priceOut!! >= 0 &&
            (key.isNotBlank() || start.id in nav.keyed)
        Row(Modifier.padding(top = 6.dp)) {
            Button(onClick = { nav.saveProfile(draft, key); close() }, enabled = ok && !nav.busy) { Text("Save") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = close) { Text("Cancel") }
            Spacer(Modifier.weight(1f))
            if (nav.profiles.any { it.id == start.id })
                TextButton(onClick = { nav.removeProfile(start.id); close() }) { Text("Remove") }
        }
        HorizontalDivider(Modifier.padding(top = 6.dp))
    }
}
