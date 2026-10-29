package com.chronos.ui.navigator

import com.chronos.ui.*
import com.chronos.ui.common.*
import com.chronos.ui.setup.*
import com.chronos.ui.horizon.*
import com.chronos.ui.day.*
import com.chronos.ui.log.*

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
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
import com.chronos.navigator.model.Issue.Severity
import com.chronos.navigator.model.Priority
import com.chronos.navigator.store.NavStore
import java.time.LocalDate

/*
 * The Navigator as a dashboard (your choice of 16 Oct 2026): a home with the
 * conversation on top — what you use every day — and four cards that each
 * answer one question and open its page. Everything used rarely sits one
 * step back, under Learning and Settings.
 */

enum class NavPage { HOME, CHAT, CHATS, INBOX, WEEK, GOALS, NEEDS, LEARNING, SETTINGS }

private fun pageTitle(p: NavPage) = when (p) {
    NavPage.HOME -> "Navigator"
    NavPage.CHAT -> "Conversation"
    NavPage.CHATS -> "Conversations"
    NavPage.INBOX -> "Inbox"
    NavPage.WEEK -> "This week"
    NavPage.GOALS -> "Goals"
    NavPage.NEEDS -> "Needs your answer"
    NavPage.LEARNING -> "Learning"
    NavPage.SETTINGS -> "Settings"
}

/** A page's top: the way back home and its name. */
@Composable
internal fun PageTop(nav: NavigatorViewModel, page: NavPage) {
    BackHandler { nav.page = NavPage.HOME }
    TextButton(onClick = { nav.page = NavPage.HOME }) { Text("‹ " + tr("Navigator")) }
    Text(tr(pageTitle(page)), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 4.dp))
}

/** What the home's cards count. */
internal data class HomeCounts(val pending: Int, val weekH: Double?, val weekAccepted: Boolean, val atRisk: Int,
                               val needs: Int)

internal fun counts(nav: NavigatorViewModel): HomeCounts {
    val g = nav.graph
    val meta = nav.week.firstOrNull { it.nodeId == NavStore.WEEK_META }
    val today = LocalDate.now()
    val atRisk = if (g == null) 0 else nav.forecast?.nodes()?.count { (id, n) ->
        val node = g.node(id) ?: return@count false
        val p = n.pByDeadline ?: return@count false
        val floor = Priority.floor(g, Priority.of(node))
        node.deadline != null && !node.deadline.isBefore(today) && floor > 0 && p < floor
    } ?: 0
    val needs = nav.issues.count { it.severity() == Severity.BLOCKING } + nav.syncDiffs.size
    return HomeCounts(nav.proposals.count { it.status() == "PENDING" }, meta?.plannedH, meta != null && meta.status != "PROPOSED",
        atRisk, needs)
}

@Composable
internal fun NavigatorHome(nav: NavigatorViewModel) {
    Text(tr("Navigator"), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 4.dp))
    if (nav.graph == null) {
        Hint("No goal graph yet: import one in Settings.")
        Button(onClick = { nav.page = NavPage.SETTINGS }) { Text("Open Settings") }
        return
    }
    CrewPanel(nav, compact = true)
    val c = counts(nav)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusCard(Modifier.weight(1f), "Inbox", c.pending.toString(),
            if (c.pending == 0) tr("nothing waiting") else
                tr("{} yours · {} from the app").replaceFirst("{}", nav.proposals.count { it.askedByYou() && it.status() == "PENDING" }.toString())
                    .replaceFirst("{}", nav.proposals.count { !it.askedByYou() && it.status() == "PENDING" }.toString()),
            if (c.pending > 0) Palette.Plum else Palette.Muted) { nav.page = NavPage.INBOX }
        StatusCard(Modifier.weight(1f), "This week", c.weekH?.let { trimNumber(Math.round(it * 2) / 2.0) + " h" } ?: "—",
            when { c.weekH == null -> tr("not planned"); c.weekAccepted -> tr("accepted"); else -> tr("to accept") },
            if (c.weekH != null && !c.weekAccepted) Palette.Sand else Palette.Indigo) { nav.page = NavPage.WEEK }
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusCard(Modifier.weight(1f), "Goals at risk", c.atRisk.toString(),
            if (c.atRisk == 0) tr("all above their minimum") else tr("below their minimum"),
            if (c.atRisk > 0) Palette.Brick else Palette.Moss) { nav.page = NavPage.GOALS }
        StatusCard(Modifier.weight(1f), "Needs your answer", c.needs.toString(),
            if (c.needs == 0) tr("nothing open") else tr("open items"),
            if (c.needs > 0) Palette.Brick else Palette.Muted) { nav.page = NavPage.NEEDS }
    }
    Spacer(Modifier.height(8.dp))
    // The map of the graph sits on the home, under the cards (your choice of 25 Oct 2026).
    nav.graph?.let { NetworkPanel(nav, it) }
    LinkRow("What my log suggests", "Allowed hours, days, session lengths and sleep, each with its reason; to approve or not") {
        nav.habitsNow(); nav.page = NavPage.INBOX }
    LinkRow("Learning", "What your log has taught") { nav.page = NavPage.LEARNING }
    LinkRow("Settings", "Goal graph, LLM and crew, automatic checks, parameters") { nav.page = NavPage.SETTINGS }
}

@Composable
private fun StatusCard(modifier: Modifier, title: String, value: String, sub: String, color: Color, open: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).border(BorderStroke(1.dp, color.copy(alpha = 0.45f)), RoundedCornerShape(10.dp))
        .clickable(onClick = open).padding(12.dp)) {
        Text(tr(title), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.headlineSmall, color = color, fontWeight = FontWeight.Medium)
        Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LinkRow(title: String, sub: String, open: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = open).padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(tr(title), style = MaterialTheme.typography.titleSmall)
            Hint(sub)
        }
        Text("›", style = MaterialTheme.typography.titleMedium, color = Palette.Muted)
    }
    HorizontalDivider()
}

/** A section that shows only its title and a one-line summary until tapped. */
@Composable
internal fun Folded(title: String, summary: String?, accent: Color, key: Any, content: @Composable ColumnScope.() -> Unit) {
    var open by remember(key) { mutableStateOf(false) }
    Panel(null, accent) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr(title), style = MaterialTheme.typography.titleSmall, color = accent)
                if (!open && summary != null) Hint(summary)
            }
            Text(if (open) "▴" else "▾", style = MaterialTheme.typography.titleMedium, color = Palette.Muted)
        }
        if (open) content()
    }
}
