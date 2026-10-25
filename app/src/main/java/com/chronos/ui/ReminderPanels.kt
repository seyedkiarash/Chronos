package com.chronos.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chronos.auto.QuickLog

/*
 * Log reminders (your request of 20 Oct 2026): when to ask, and what became of
 * the answers. An answer read by the model waits here, entry by entry, until
 * you keep it; a question from the model is answered in the same place.
 */

/** Answers to reminders that are being read or wait to be confirmed. Shown only when there are any. */
@Composable
internal fun QuickLogPanel(vm: ChronosViewModel) {
    val open = vm.quickDrafts.filter { it.status != "DONE" }
    if (open.isEmpty()) return
    Panel("Your quick answers", Palette.Plum) {
        open.forEach { d -> QuickDraftCard(vm, d) }
    }
}

@Composable
private fun QuickDraftCard(vm: ChronosViewModel, d: QuickLog.Draft) {
    val lang = LocalLang.current
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        d.turns.forEach { t ->
            Text((if (t.who == "you") "› " else "") + t.text, style = MaterialTheme.typography.bodySmall,
                color = if (t.who == "you") MaterialTheme.colorScheme.onSurface else Palette.Plum)
        }
        when (d.status) {
            "WAITING" -> Hint(if (vm.reminderViaModel) "Waiting to be read by the model (it needs the network)."
                              else "Kept as you wrote it: fill in the boxes below.")
            "FAILED" -> Hint(tr("Not read") + ": " + (d.error ?: "") + ". " + tr("Fill in the boxes below, or send it again."))
            "READ" -> {
                val ticked = remember(d.id, d.entries.size) { mutableStateListOf<Boolean>().apply { addAll(d.entries.map { true }) } }
                if (d.entries.isEmpty() && d.question == null) Hint("The model found nothing to log in it.")
                d.entries.forEachIndexed { i, e ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = ticked.getOrElse(i) { true }, onCheckedChange = { ticked[i] = it })
                        Text(describe(e, lang), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (d.entries.isNotEmpty())
                    Button(onClick = { vm.confirmQuick(d.id, d.entries.filterIndexed { i, _ -> ticked.getOrElse(i) { true } }) },
                        enabled = !vm.busy && ticked.any { it }) { Text("Keep the ticked ones") }
            }
        }
        // Answer the model's question, or correct what it read: it reads the whole conversation again.
        var draft by remember(d.id, d.turns.size) { mutableStateOf("") }
        if (d.status == "READ" || d.status == "FAILED") {
            OutlinedTextField(value = draft, onValueChange = { draft = it }, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(if (d.question != null) tr("Your answer") else tr("A correction, if any"),
                    style = MaterialTheme.typography.bodySmall) }, minLines = 1)
            Row {
                TextButton(onClick = { val t = draft.trim(); draft = ""; vm.answerQuick(d.id, t) },
                    enabled = draft.isNotBlank() && !vm.busy) { Text("Send") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { vm.dropQuick(d.id) }, enabled = !vm.busy) { Text("Discard") }
            }
        } else TextButton(onClick = { vm.dropQuick(d.id) }, enabled = !vm.busy) { Text("Discard") }
        HorizontalDivider()
    }
}

private fun describe(e: QuickLog.Entry, lang: Lang): String = when (e.kind) {
    "energy" -> translate(lang, "Energy") + " " + "%02d:00".format(e.hour ?: 0) + ": " + (e.rating ?: 0) + "/5"
    else -> buildString {
        append(e.start ?: "?").append("  ").append(e.activity ?: "?").append(" · ").append(e.minutes ?: 0).append(' ')
            .append(translate(lang, "min"))
        e.donePercent?.let { append(" · ").append(it.toInt()).append('%') }
        e.units?.let { append(" · ").append(trimNumber(it)).append(' ').append(translate(lang, "units")) }
        e.focus?.let { append(" · ").append(translate(lang, "focus")).append(' ').append(it).append("/5") }
        e.mind?.let { append(" · ").append(translate(lang, "mind")).append(' ').append(it).append("/5") }
        e.body?.let { append(" · ").append(translate(lang, "body")).append(' ').append(it).append("/5") }
        e.restored?.let { append(" · ").append(translate(lang, "restored")).append(' ').append(it).append("/5") }
    }
}

/** When to be asked, and whether answers go to the model. */
@Composable
internal fun RemindersPanel(vm: ChronosViewModel) {
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.notificationsAllowed = granted
        if (granted) vm.changeReminders(true)
    }
    Panel("Log reminders") {
        Hint("At the times below, and whenever several waking hours pass with nothing logged, a notification asks how " +
             "it is going — never while you sleep or are in a class. Answer it in one line and the model turns it into " +
             "entries for you to keep, or open the Log.")
        Toggle("Remind me", vm.remindersOn && vm.notificationsAllowed,
            if (!vm.notificationsAllowed && vm.remindersOn) "Notifications are blocked for Chronos: allow them to get reminders." else null) { on ->
            if (on && !vm.notificationsAllowed && Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else vm.changeReminders(on)
        }
        if (!vm.remindersOn) return@Panel
        Text("Ask at", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRowSimple {
            vm.reminderTimes.forEach { t ->
                InputChip(selected = false, onClick = { vm.changeReminderTimes(vm.reminderTimes - t) },
                    label = { Text("$t  ×") }, modifier = Modifier.padding(end = 6.dp))
            }
        }
        var adding by remember { mutableStateOf<String?>(null) }
        TimeField("Add a time", adding) { t -> adding = null; vm.changeReminderTimes(vm.reminderTimes + t) }
        val gap = vm.reminderGapH
        val never = tr("never"); val h = tr("h")
        LevelSlider("Ask after this long with nothing logged", if (gap <= 0) 0.0 else gap, 0.0..6.0, 0.5, "never", "6 h",
            Palette.Steel, format = { if (it <= 0) never else trimNumber(it) + " " + h }) { v -> vm.changeReminderGap(v) }
        Toggle("Let the model read my quick answers", vm.reminderViaModel,
            "One call each, from the provider and budget in the Navigator's LLM settings. Off: the answer is only kept for you to fill in.") {
            vm.changeReminderViaModel(it)
        }
        var line by remember { mutableStateOf("") }
        OutlinedTextField(value = line, onValueChange = { line = it }, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(tr("Or write it here: what you did and how you feel"), style = MaterialTheme.typography.bodySmall) })
        Row {
            TextButton(onClick = { val t = line.trim(); line = ""; vm.quickAnswer(t) }, enabled = line.isNotBlank() && !vm.busy) { Text("Send") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { vm.testReminder() }, enabled = !vm.busy && vm.notificationsAllowed) { Text("Try a reminder now") }
        }
    }
}
