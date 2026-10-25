package com.chronos.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chronos.navigator.model.Edge
import com.chronos.navigator.model.Graph
import com.chronos.navigator.model.Issue
import com.chronos.navigator.model.Issue.Fix
import com.chronos.navigator.model.Issue.Severity
import com.chronos.navigator.model.Kinds
import com.chronos.navigator.model.Kinds.Dist
import com.chronos.navigator.model.Kinds.EdgeKind
import com.chronos.navigator.model.Kinds.NodeType
import com.chronos.navigator.model.Node
import com.chronos.navigator.model.Overrides
import com.chronos.navigator.model.ParamRegistry
import com.chronos.navigator.model.Requirements
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The Navigator: the goal graph above the daily plan. This stage shows the
 * imported graph, what it still lacks, and the forms that fill those gaps.
 */
@Composable
fun NavigatorScreen(nav: NavigatorViewModel) {
    LaunchedEffect(Unit) { nav.refresh() }
    nav.openProposal?.let { if (!nav.loading) { ProposalPage(nav, it); return } }
    if (nav.loading) return

    // A dashboard and its pages (your choice of 16 Oct 2026): see NavigatorHome.
    val page = nav.page
    if (page == NavPage.HOME) { NavigatorHome(nav); return }
    PageTop(nav, page)
    val g = nav.graph
    when (page) {
        NavPage.CHAT -> CrewPanel(nav, compact = false)
        NavPage.INBOX -> { InboxPanel(nav); if (nav.proposals.isEmpty()) Hint("Nothing has been proposed yet.") }
        NavPage.WEEK -> if (g != null) WeekPanel(nav, g)
        NavPage.GOALS -> if (g != null) { ForecastPanel(nav, g); GraphWizard(g, nav) }   // the map is on the home (25 Oct 2026)
        NavPage.NEEDS -> {
            if (g != null) MissingPanel(nav, g)
            SyncPanel(nav)
            QuestionsPanel(nav)
        }
        NavPage.LEARNING -> LearnedPanel(nav)
        NavPage.SETTINGS -> {
            ImportPanel(nav)
            if (nav.refused.isNotEmpty()) RefusedPanel(nav.refused)
            LlmPanel(nav)
            TriggersPanel(nav)
            ParamsPanel(nav)
        }
        NavPage.HOME -> { }
    }
}

// ---------------------------------------------------------------------- import

@Composable
private fun ImportPanel(nav: NavigatorViewModel) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) nav.importFrom(uri)
    }
    Panel("Goal graph") {
        val info = nav.info
        val g = nav.graph
        if (info == null || g == null) {
            Hint("No goal graph yet. Import the goal-graph JSON file (for example goal-graph-v0.2.json). " +
                 "It is checked first: a file with a loop, a missing node or a broken consent rule is refused whole.")
        } else {
            Text(g.title ?: info.fileName ?: "")
            Hint(tr("Version") + " " + (info.schemaVersion ?: "?") + "  ·  " +
                 tr("Imported") + " " + dateText(Instant.ofEpochMilli(info.importedAt)
                     .atZone(ZoneId.systemDefault()).toLocalDate()) +
                 (info.fileName?.let { "  ·  $it" } ?: ""))
            Hint("${g.nodes.size} nodes, ${g.edges.size} edges, ${g.regimes.size} regimes")
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = { launcher.launch(arrayOf("application/json", "text/plain", "*/*")) },
            enabled = !nav.busy) {
            Text(if (nav.info == null) "Import goal graph" else "Import a new version")
        }
        if (nav.info != null)
            Hint("A new version replaces the nodes and the calendar. What the log taught and the answers " +
                 "you gave here are kept, unless the new file states them itself.")
    }
}

@Composable
private fun RefusedPanel(refused: List<Issue>) {
    Verdict(Tone.Bad, "The file was not imported", "Nothing was changed. Fix these in the file and import it again.")
    Panel {
        refused.forEach { MissingRow(it.subject(), it.message()) }
    }
}

// --------------------------------------------------------------- missing data

@Composable
private fun MissingPanel(nav: NavigatorViewModel, g: Graph) {
    val blocking = nav.issues.filter { it.severity() == Severity.BLOCKING }
    val warnings = nav.issues.filter { it.severity() == Severity.WARNING }
    if (blocking.isEmpty())
        Verdict(Tone.Good, "Nothing is missing", "Every number the layers need has a source.")
    else
        Verdict(Tone.Bad, "${blocking.size} things are missing",
            "The Navigator will not compute on a number nobody gave. Answer these below; " +
            "each answer is saved as yours and can be changed later.")
    if (nav.issues.isEmpty()) return
    Panel("Missing data") {
        (blocking + warnings).forEach { IssueRow(nav, g, it) }
    }
}

@Composable
private fun IssueRow(nav: NavigatorViewModel, g: Graph, issue: Issue) {
    var open by remember(issue.subject(), issue.fix()) { mutableStateOf(false) }
    var explain by remember(issue.subject(), issue.fix()) { mutableStateOf(false) }
    val warn = issue.severity() == Severity.WARNING
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(issue.message(), style = MaterialTheme.typography.bodySmall,
            color = if (warn) Palette.Sand else Palette.Brick,
            modifier = Modifier.clickable { open = !open })
        Row {
            TextButton(onClick = { explain = !explain }) { Text(if (explain) "Hide" else "What is this?") }
            if (issue.fix() != Fix.NONE && issue.fix() != Fix.REGIME_CONFIRM)
                TextButton(onClick = { open = !open }) { Text(if (open) "Close" else "Answer this") }
        }
        if (explain) IssueExplanation(g, issue)
        if (open && issue.fix() in LLM_FIXES) AskLlmRow(nav, issue)
        if (open) when (issue.fix()) {
            Fix.LINK_ACTIVITY -> LinkForm(nav, issue)
            Fix.NODE_EFFORT -> EffortForm(nav, g, issue.subject())
            Fix.NODE_PRIOR -> PriorForm(nav, issue.subject())
            Fix.PARAM -> ParamForm(nav, g, issue.subject())
            Fix.REGIME_GAP -> RegimeForm(nav, g, issue.subject(), null)
            Fix.REGIME_MIX -> RegimeForm(nav, g, null, issue.subject())
            else -> {}
        }
        HorizontalDivider(Modifier.padding(top = 6.dp))
    }
}

/** Issues the LLM can propose an answer to: estimates nobody can give precisely. */
private val LLM_FIXES = setOf(Fix.REGIME_GAP, Fix.REGIME_MIX, Fix.NODE_EFFORT, Fix.NODE_PRIOR)

@Composable
private fun AskLlmRow(nav: NavigatorViewModel, issue: Issue) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
        OutlinedButton(onClick = { nav.askLlm(issue) }, enabled = !nav.busy) { Text("Ask the LLM to propose") }
    }
    Hint("It reads only what this question needs — dates, milestones, the regimes you already have; never " +
         "your log — and answers with a range and how sure it is. Its answer waits in the proposals inbox " +
         "below until you approve it. Or answer it yourself here.")
}

/** The node a node-level issue is about; a link issue's subject is "NODE|activity". */
private fun issueNode(g: Graph, issue: Issue): Node? = when (issue.fix()) {
    Fix.NODE_EFFORT, Fix.NODE_PRIOR -> g.node(issue.subject())
    Fix.LINK_ACTIVITY -> g.node(issue.subject().substringBefore('|'))
    else -> null
}

/**
 * Why an answer is asked for: where the thing sits, what depends on it, and
 * which layer reads it. Open on demand, so the list itself stays short.
 */
@Composable
private fun IssueExplanation(g: Graph, issue: Issue) {
    val bg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)
        .background(bg, androidx.compose.foundation.shape.RoundedCornerShape(6.dp)).padding(10.dp)) {
        val n = issueNode(g, issue)
        if (n != null) {
            Text(n.title ?: n.id, style = MaterialTheme.typography.bodyMedium, color = typeColor(n.type))
            Hint(listOfNotNull(n.id, n.type?.let { tr(typeLabel(it)) }, n.phase?.let { tr("Phase") + " " + it },
                n.owner?.let { tr(ownerLabel(it)) }).joinToString("  ·  "))
            n.type?.let { Hint(typeMeaning(it)) }
            val before = (g.into(n.id, com.chronos.navigator.model.Kinds.EdgeKind.AND) +
                          g.into(n.id, com.chronos.navigator.model.Kinds.EdgeKind.OR_GROUP)).mapNotNull { g.node(it.from())?.title }
            if (before.isNotEmpty()) Hint(tr("Comes after") + ": " + before.joinToString("; "))
            val after = downstream(g, n.id)
            if (after.isNotEmpty()) Hint(tr("What depends on it") + ": " +
                after.take(6).joinToString("; ") { it.title ?: it.id } + if (after.size > 6) " …" else "")
            n.raw?.get("note")?.takeIf { it.isJsonPrimitive }?.let { Hint(it.asString) }
            Spacer(Modifier.height(4.dp))
        }
        when (issue.fix()) {
            Fix.NODE_EFFORT -> {
                Text("Why it is needed", style = MaterialTheme.typography.labelMedium)
                Hint("The forecast (stage 3) plays out the whole graph from today to its last date, thousands of " +
                     "times. A node that takes hours but has no estimate cannot be played out — and neither can " +
                     "anything that comes after it.")
                if (n?.expandAt != null || n?.granularity == "coarse")
                    Hint(tr("This is a coarse node: it will be broken into smaller tasks") +
                         (n.expandAt?.let { " " + tr("on") + " " + dateText(it) } ?: "") + ". " +
                         tr("A rough range is enough now; make the 90th percentile generous."))
            }
            Fix.NODE_PRIOR -> {
                Text("Why it is needed", style = MaterialTheme.typography.labelMedium)
                Hint("Nothing in the graph decides this node, so the forecast decides it by chance: in each " +
                     "simulated future it is reached with the probability you give. The sureness says how much " +
                     "that probability itself may be off.")
            }
            Fix.LINK_ACTIVITY -> {
                Text("Why it is needed", style = MaterialTheme.typography.labelMedium)
                Hint("Sessions you log under this Chronos activity are how the Navigator sees progress on the " +
                     "node, and the weekly allocation sends the node's hours to it. Without the link the node's " +
                     "time cannot reach your day plan.")
            }
            Fix.PARAM -> {
                ParamRegistry.spec(issue.subject())?.let { Hint(it.why()) }
                Hint("Read by the estimate layer (stage 2), every night, as the starting point that your log then corrects.")
            }
            Fix.REGIME_GAP, Fix.REGIME_MIX, Fix.REGIME_CONFIRM -> {
                Text("What a regime is", style = MaterialTheme.typography.labelMedium)
                Hint("A stretch of the calendar with its own share of ideal, busy and stressful days (term, " +
                     "exams, holidays). The forecast draws each future day's type from it to know how many hours " +
                     "you will have.")
                Hint("Far ahead nobody knows this precisely. Give a rough mix with low sureness, or ask the LLM: " +
                     "it proposes stretches with a wide uncertainty, and the forecast treats them as rough.")
            }
            else -> {}
        }
    }
}

@Composable
private fun LinkForm(nav: NavigatorViewModel, issue: Issue) {
    if (nav.activities.isEmpty()) {
        Hint("There are no goal activities in Chronos yet. Create one in Setup.")
        return
    }
    var chosen by remember { mutableStateOf<String?>(null) }
    Choice("Count this Chronos activity instead", chosen, nav.activities.map { it to it },
        hint = "Or create an activity with exactly this name in Setup → Activities; it is found by name.") {
        chosen = it
    }
    Button(onClick = { chosen?.let { nav.link(issue.subject(), it) } }, enabled = chosen != null) { Text("Link") }
}

@Composable
private fun EffortForm(nav: NavigatorViewModel, g: Graph, nodeId: String) {
    val n = g.node(nodeId) ?: return
    if (n.type == NodeType.STREAM) {
        var floor by remember { mutableStateOf<Double?>(null) }
        NumberField("Weekly floor (hours)", floor) { floor = it }
        Button(onClick = { nav.answer(Overrides.nodeKey(n.id, "floor"), Dist.POINT, mapOf("value" to floor!!)) },
            enabled = floor != null && floor!! >= 0) { Text("Save") }
        return
    }
    var p50 by remember { mutableStateOf<Double?>(null) }
    var p90 by remember { mutableStateOf<Double?>(null) }
    Hint("Total hours of work. The median is your honest guess; the 90th percentile is the figure " +
         "you would be surprised to exceed.")
    NumberField("Median (hours)", p50) { p50 = it }
    NumberField("90th percentile (hours)", p90) { p90 = it }
    val ok = p50 != null && p90 != null && p50!! > 0 && p90!! >= p50!!
    if (p50 != null && p90 != null && !ok) Hint("The 90th percentile must be at least the median.")
    Button(onClick = {
        nav.answer(Overrides.nodeKey(n.id, "effort"), Dist.LOGNORMAL, mapOf("p50" to p50!!, "p90" to p90!!))
    }, enabled = ok) { Text("Save") }
}

@Composable
private fun PriorForm(nav: NavigatorViewModel, nodeId: String) {
    var pct by remember { mutableStateOf<Double?>(null) }
    var strength by remember { mutableStateOf<Double?>(null) }
    Hint("How likely is it, and how sure are you of that? Sureness is counted in cases: 10 means " +
         "\"as if I had seen ten similar cases\"; 2 means a loose guess.")
    NumberField("Probability (%)", pct) { pct = it }
    NumberField("Sureness (cases)", strength) { strength = it }
    val ok = pct != null && strength != null && pct!! > 0 && pct!! < 100 && strength!! > 0
    Button(onClick = {
        val p = pct!! / 100.0
        nav.answer(Overrides.nodeKey(nodeId, "success"), Dist.BETA,
            mapOf("a" to p * strength!!, "b" to (1 - p) * strength!!))
    }, enabled = ok) { Text("Save") }
}

@Composable
private fun ParamForm(nav: NavigatorViewModel, g: Graph, key: String) {
    val spec = ParamRegistry.spec(key) ?: return
    val have = g.params[key]
    val values = remember(key) {
        mutableStateMapOf<String, Double?>().apply { spec.fields().forEach { put(it, have?.get(it)) } }
    }
    Hint(spec.why())
    spec.fields().forEach { f ->
        val suggestion = spec.suggest()?.get(f)
        NumberField(f, values[f], hint = suggestion?.let { tr("Suggested") + ": " + trimNumber(it) }) { values[f] = it }
    }
    Row {
        if (spec.suggest() != null)
            TextButton(onClick = {
                spec.suggest()!!.forEach { (f, v) -> if (values[f] == null) values[f] = v }
            }) { Text("Use the suggestions") }
        Spacer(Modifier.width(8.dp))
        Button(onClick = {
            nav.answer(key, spec.dist(), values.mapValues { it.value!! })
        }, enabled = values.values.all { it != null }) { Text("Save") }
    }
}

/** A new regime over an uncovered stretch ([gap] = "from..to"), or the mix of an existing one. */
@Composable
private fun RegimeForm(nav: NavigatorViewModel, g: Graph, gap: String?, regimeId: String?) {
    val span = gap?.split("..")
    var id by remember { mutableStateOf(regimeId ?: "") }
    var from by remember { mutableStateOf(span?.let { LocalDate.parse(it[0]) }) }
    var to by remember { mutableStateOf(span?.let { LocalDate.parse(it[1]) }) }
    var ideal by remember { mutableStateOf<Double?>(null) }
    var busy by remember { mutableStateOf<Double?>(null) }
    var stress by remember { mutableStateOf<Double?>(null) }
    var sureness by remember { mutableStateOf<Double?>(null) }

    if (gap != null) {
        Hint("Name the stretch (for example TERM2) and give its dates. You can split a long stretch: " +
             "save the first part, and what is left will be asked again.")
        TextField("Name", id) { id = it.trim().uppercase() }
        DateField("From", from) { from = it }
        DateField("To", to) { to = it }
    }
    Hint("Of every hundred days in this stretch, how many are ideal, busy and stressful?")
    NumberField("Ideal days (%)", ideal) { ideal = it }
    NumberField("Busy days (%)", busy) { busy = it }
    NumberField("Stress days (%)", stress) { stress = it }
    NumberField("Sureness (days)", sureness,
        hint = "How many observed days this mix is worth before your ticked day types take over: " +
               "10 for a term you know well, 2 for a guess years ahead.") { sureness = it }

    val sum = (ideal ?: 0.0) + (busy ?: 0.0) + (stress ?: 0.0)
    val clash = gap != null && g.regime(id) != null
    val ok = id.isNotBlank() && !clash && ideal != null && busy != null && stress != null &&
        sureness != null && sureness!! > 0 &&
        kotlin.math.abs(sum - 100) < 0.5 && (gap == null || (from != null && to != null && !to!!.isBefore(from)))
    if (clash) Hint("A regime with this name already exists.")
    if (ideal != null && busy != null && stress != null && kotlin.math.abs(sum - 100) >= 0.5)
        Hint("The three must add up to 100.")
    Button(onClick = {
        val mix = Triple(Overrides.regimeKey(id, "mix"), Dist.DIRICHLET,
            mapOf("IDEAL" to ideal!! / 100, "BUSY" to busy!! / 100, "STRESS" to stress!! / 100,
                  "strength" to sureness!!))
        nav.answerAll(if (gap == null) listOf(mix) else listOf(
            Triple(Overrides.regimeKey(id, "span"), Dist.POINT,
                mapOf("from" to from!!.toEpochDay().toDouble(), "to" to to!!.toEpochDay().toDouble())),
            mix))
    }, enabled = ok) { Text("Save") }
}

// --------------------------------------------------------------------- params

@Composable
private fun ParamsPanel(nav: NavigatorViewModel) {
    var open by remember { mutableStateOf(false) }
    Panel("Parameters") {
        Hint("Every number the layers read, with where it came from. Nothing here is a hidden default.")
        TextButton(onClick = { open = !open }) { Text(if (open) "Hide" else "Show all") }
        if (open) nav.params.forEach { p ->
            Column(Modifier.padding(vertical = 4.dp)) {
                Text(p.key, style = MaterialTheme.typography.bodySmall)
                Hint(p.values.entries.joinToString("  ") { "${it.key} = ${trimNumber(it.value)}" } +
                     "  ·  " + tr(sourceLabel(p.source)))
            }
        }
    }
}

private fun sourceLabel(s: Kinds.Source?) = when (s) {
    Kinds.Source.USER -> "from you"; Kinds.Source.DATA -> "learned from the log"
    Kinds.Source.LLM -> "proposed by the LLM, approved by you"; else -> "stated assumption"
}
