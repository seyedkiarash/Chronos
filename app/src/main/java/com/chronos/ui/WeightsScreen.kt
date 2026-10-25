package com.chronos.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun WeightsScreen(vm: ChronosViewModel) {
    val rev = vm.revision
    // Only targets still in play are compared: a passed deadline, a finished amount or an archived
    // activity drops out, and comes back by itself when the deadline is moved or the amount raised.
    val today = java.time.LocalDate.now()
    val goals = vm.config.goals.filter { vm.outOfPlay(it, today) == null }
    val resting = vm.config.goals.mapNotNull { g -> vm.outOfPlay(g, today)?.let { g to it } }
    val result = vm.weightsPreview()
    // Priorities are between activities now (your decision of 17 Oct 2026); each is shown under its goal.
    fun label(g: com.chronos.model.Goal) = (g.name ?: "?") + (g.groupName?.takeIf { it != g.name }?.let { "  ·  $it" } ?: "")
    fun nameOf(id: String) = goals.firstOrNull { it.id == id }?.let { label(it) } ?: id

    ScreenHeader(
        "Relative priorities",
        "Compare activities two at a time instead of assigning numbers. What comes out is what the " +
        "scheduler trades against fatigue."
    )

    if (goals.size < 2) {
        Verdict(Tone.Warn, "Not enough activities to compare",
            "Add at least two goal activities in Setup and they will appear here.")
        RestingPanel(resting)
        return
    }

    Panel {
        for (i in goals.indices) for (j in i + 1 until goals.size) {
            val a = goals[i].id
            val b = goals[j].id
            val ratio = vm.comparisons["$a|$b"] ?: 1.0
            val step = if (ratio >= 1.0) ratio.roundToInt() - 1 else -((1.0 / ratio).roundToInt() - 1)

            Column(Modifier.padding(vertical = 10.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text(label(goals[i]), style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f))
                    Text(label(goals[j]), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                }
                Slider(
                    value = step.toFloat(),
                    onValueChange = { v ->
                        val s = v.roundToInt()
                        vm.setComparison(a, b, if (s >= 0) (s + 1).toDouble() else 1.0 / (1 - s))
                    },
                    valueRange = -8f..8f,
                    steps = 15
                )
                Text(
                    when {
                        abs(ratio - 1.0) < 0.01 -> "Equally valuable per hour"
                        ratio > 1 -> "${label(goals[i])} is ${"%.1f".format(ratio)}× more valuable per hour"
                        else -> "${label(goals[j])} is ${"%.1f".format(1 / ratio)}× more valuable per hour"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            HorizontalDivider()
        }
    }

    Panel("Derived weights") {
        goals.forEachIndexed { index, g ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                Text(label(g), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Text("%.1f%%".format(100 * (result.weights()[g.id] ?: 0.0)),
                    style = MaterialTheme.typography.bodySmall)
            }
            Meter((result.weights()[g.id] ?: 0.0).toFloat(), GoalColors.target(vm.config.goals, g.id))
        }
        Spacer(Modifier.height(14.dp))
        Button(onClick = { vm.applyWeights() }) { Text("Use these weights") }
    }

    RestingPanel(resting)

    val cr = result.consistencyRatio()
    Panel("Consistency") {
        Text("%.2f".format(cr), style = MaterialTheme.typography.headlineMedium,
            color = if (cr > 0.1) Palette.Brick else Palette.Moss)
        Hint(
            if (cr > 0.1)
                "Your judgments contradict each other. The least consistent pair is " +
                "${nameOf(result.worstA() ?: "")} against ${nameOf(result.worstB() ?: "")} — " +
                "revisit only that one."
            else "Internally consistent. Anything below 0.10 is fine."
        )
    }
}

/** Targets left out of the comparisons, each with why; folded until tapped. */
@Composable
private fun RestingPanel(resting: List<Pair<com.chronos.model.Goal, String>>) {
    if (resting.isEmpty()) return
    var open by rememberSaveable { mutableStateOf(false) }
    Panel("Not compared now") {
        Hint("These no longer need a priority. Move a deadline later or raise an amount in Setup and the activity comes back here.")
        TextButton(onClick = { open = !open }) { Text(if (open) tr("Hide") else tr("Show") + " (" + resting.size + ")") }
        if (open) resting.forEach { (g, why) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text((g.name ?: "?") + (g.groupName?.takeIf { it != g.name }?.let { "  ·  $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Text(tr(why), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
