package com.chronos.ui.navigator

import com.chronos.ui.*
import com.chronos.ui.common.*

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chronos.navigator.llm.client.Companion

/*
 * The day's conversation (3 Nov 2026): the Navigator as a companion that asks how the day went,
 * listens, and at the end writes down what the day said — kept on this phone, sent on only when you
 * choose. See Companion for what the model sees.
 */

@Composable
internal fun TodayPanel(nav: NavigatorViewModel) {
    LaunchedEffect(Unit) { nav.openToday() }
    val d = nav.today
    Panel(null, Palette.Teal) {
        Hint("Talk about the day: how you felt, what happened, what is on your mind, what you want. It stays on this phone. " +
             "The model sees this conversation and the app's short summary of today — not your log; the crew later sees " +
             "only the summary you wrap up below.")
        ReminderRow(nav)
        if (d == null) { LinearProgressIndicator(Modifier.fillMaxWidth()); return@Panel }
        d.turns.forEach { t ->
            when {
                t.safety -> Text(t.text, style = MaterialTheme.typography.bodySmall, color = Palette.Brick,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                else -> Bubble(t.text, mine = t.who == "you")
            }
            t.failed?.let { why ->
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                    Text(tr("No answer") + ": " + why.lineSequence().first().take(160), style = MaterialTheme.typography.labelSmall,
                        color = Palette.Brick, modifier = Modifier.fillMaxWidth(0.85f))
                    if (t == d.turns.lastOrNull { it.who == "you" })
                        TextButton(onClick = { nav.answerAgain() }, enabled = !nav.talking) { Text("Ask again") }
                }
            }
        }
        if (nav.talking) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
        var draft by remember { mutableStateOf("") }
        fun send() { nav.sayToday(draft); draft = "" }
        OutlinedTextField(value = draft, onValueChange = { draft = it }, modifier = Modifier.fillMaxWidth(), minLines = 2,
            placeholder = { Text(tr("Write as you would to a friend…"), style = MaterialTheme.typography.bodySmall) },
            trailingIcon = { SendIcon(draft.isNotBlank() && !nav.talking) { send() } })

        // How the day was, in two numbers you set yourself.
        Text(tr("How you felt today"), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
        ScaleRow(d.mood) { nav.setMood(if (d.mood == it) null else it, d.stress) }
        Hint("1 very low · 5 very good")
        Text(tr("How much pressure"), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
        ScaleRow(d.stress) { nav.setMood(d.mood, if (d.stress == it) null else it) }
        Hint("1 calm · 5 heavy")

        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { nav.wrapToday() }, enabled = !nav.talking && d.turns.any { it.who == "you" }) {
                Text(if (d.signals == null) "Wrap up the day" else "Wrap up again")
            }
        }
        Hint("Wrapping up writes the day's summary from this conversation: what happened, how you felt, ideas, worries and new limits on your time. It is kept in the journal below, and the crew sees that summary, not the talk. If something should change your plans, it also prepares a message for the Navigator, sent only if you tap it.")
        d.signals?.let { SignalsCard(nav, d) }
    }
    JournalPanel(nav)
}

/** The evening's reminder: on or off, and when. Only sent if you have not talked yet that day. */
@Composable
private fun ReminderRow(nav: NavigatorViewModel) {
    val permission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
        Text(tr("Remind me in the evening"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Switch(checked = nav.talkOn, onCheckedChange = { on ->
            nav.changeTalkOn(on)
            if (on && !nav.notificationsAllowed && android.os.Build.VERSION.SDK_INT >= 33)
                permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        })
    }
    if (nav.talkOn) {
        var text by remember { mutableStateOf(nav.talkTime) }
        val ok = text.matches(Regex("([01]\\d|2[0-3]):[0-5]\\d"))
        OutlinedTextField(value = text, singleLine = true, isError = !ok,
            onValueChange = { text = it; if (it.matches(Regex("([01]\\d|2[0-3]):[0-5]\\d"))) nav.changeTalkTime(it) },
            label = { Text(tr("Time"), style = MaterialTheme.typography.labelSmall) },
            modifier = Modifier.width(120.dp))
        Hint("Once a day at this time, only if you have not talked yet.")
        if (!nav.notificationsAllowed) {
            Text(tr("Notifications are off for Chronos, so the reminder cannot reach you."),
                style = MaterialTheme.typography.labelSmall, color = Palette.Brick)
            if (android.os.Build.VERSION.SDK_INT >= 33)
                TextButton(onClick = { permission.launch(android.Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow notifications") }
        }
    }
}

@Composable
private fun ScaleRow(value: Int?, pick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        (1..5).forEach { n -> Box(Modifier.weight(1f).padding(end = 4.dp)) { Pill(n.toString(), value == n) { pick(n) } } }
    }
}

/** What the day said, as the model wrote it down: read it, and send to the Navigator what should change plans. */
@Composable
private fun SignalsCard(nav: NavigatorViewModel, d: Companion.Day) {
    val s = d.signals ?: return
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text(tr("The day in brief"), style = MaterialTheme.typography.titleSmall, color = Palette.Teal)
        s.summary?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        @Composable fun lines(title: String, items: List<String>?) {
            if (items.isNullOrEmpty()) return
            Text(tr(title), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
            items.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }
        lines("What happened", s.events)
        lines("Feelings", s.feelings)
        lines("Ideas", s.ideas)
        lines("Worries", s.worries)
        lines("New limits on your time", s.constraints)
        lines("About your goals", s.goalNotes)
        val msg = s.forNavigator
        if (!msg.isNullOrBlank()) {
            var text by remember(msg) { mutableStateOf(msg) }
            Text(tr("For the Navigator"), style = MaterialTheme.typography.labelMedium, color = Palette.Indigo,
                modifier = Modifier.padding(top = 8.dp))
            Hint("Sent only if you tap the button: the crew then proposes changes to your goals and Chronos, for you to approve. Edit it first if you like.")
            OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            Button(onClick = { nav.sendToNavigator(text) }, enabled = text.isNotBlank() && !nav.busy) {
                Text(if (d.sentToNavigator) "Send again" else "Send to the Navigator")
            }
            if (d.sentToNavigator) Hint("Sent.")
        } else Hint("Nothing in it for your plans; it is kept in the journal, and the crew sees its summary.")
    }
}

/** The days kept, the latest first. */
@Composable
private fun JournalPanel(nav: NavigatorViewModel) {
    val past = nav.journal.filter { it.date != nav.today?.date && (it.signals != null || it.turns.any { t -> t.who == "you" }) }
    if (past.isEmpty()) return
    Folded("Journal", tr("{} days").replace("{}", past.size.toString()), Palette.Teal, "journal") {
        past.forEach { d ->
            var open by remember(d.date) { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 6.dp)) {
                Text(dateText(java.time.LocalDate.parse(d.date)) +
                    (d.mood?.let { "  ·  " + tr("mood") + " " + it + "/5" } ?: "") +
                    (d.stress?.let { "  ·  " + tr("pressure") + " " + it + "/5" } ?: ""),
                    style = MaterialTheme.typography.labelMedium)
                d.signals?.summary?.let { Hint(it) }
                if (open) d.turns.filter { !it.safety }.forEach { Bubble(it.text, mine = it.who == "you") }
            }
            HorizontalDivider()
        }
    }
}
