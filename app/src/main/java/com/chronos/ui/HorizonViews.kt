package com.chronos.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chronos.engine.HorizonPlanner
import com.chronos.model.Enums
import java.time.LocalDate

/*
 * Horizon in three pictures (your choice of 24 Oct 2026): four tiles that each
 * answer one question, the month as a calendar coloured by how full each day
 * is, and each goal as a row running to its deadline. A tile takes you to the
 * picture that answers it.
 */

/** One status tile: a number, what it counts, and where it leads. */
@Composable
internal fun HorizonTile(modifier: Modifier, title: String, value: String, sub: String, color: Color, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).border(BorderStroke(1.dp, color.copy(alpha = 0.45f)), RoundedCornerShape(10.dp))
        .clickable(onClick = onClick).padding(12.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(value, style = MaterialTheme.typography.headlineSmall, color = color, fontWeight = FontWeight.Medium, maxLines = 1)
        Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
    }
}

/** Dated targets the plan still works toward, soonest deadline first. */
internal fun datedInPlay(vm: ChronosViewModel, start: LocalDate) = vm.targetsInPlay()
    .filter { it.mode == Enums.GoalMode.CLOSED && it.deadline != null && !it.deadline.isBefore(start) }
    .sortedBy { it.deadline }

// ------------------------------------------------------------------ the month

/**
 * The horizon as a calendar: weeks of Saturday to Friday, each day shaded by how much of its
 * usable time the plan fills, a dot in a goal's colour on its deadline, a red edge where the
 * arranged day left something out. Tap a day for what it holds.
 */
@Composable
internal fun MonthHeat(vm: ChronosViewModel, plan: HorizonPlanner.Plan) {
    val lang = LocalLang.current
    val goals = vm.config.goals
    val days = plan.days()
    // What each day was given, from the allocation itself: a plan read back from storage has no usedH.
    val used = plan.allocation().map { it.values.sum() }
    var picked by remember(plan) { mutableIntStateOf(0) }
    val troubled = vm.upcoming.filter { it.missing().isNotEmpty() }.map { it.date() }.toSet()
    val dated = datedInPlay(vm, days.firstOrNull()?.date() ?: LocalDate.now())
    val empty = MaterialTheme.colorScheme.surfaceVariant
    val full = Palette.Steel
    val lead = days.firstOrNull()?.dow() ?: 0
    val cells: List<Int?> = List(lead) { null } + days.indices.toList()

    Row(Modifier.fillMaxWidth()) {
        (0..6).forEach { d ->
            Text(weekday(lang, d).take(if (lang.rtl) 3 else 3), Modifier.weight(1f), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
    cells.chunked(7).forEach { week ->
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            week.forEach { i ->
                Box(Modifier.weight(1f).padding(2.dp).aspectRatio(1f)) {
                    if (i == null) return@Box
                    val day = days[i]
                    val cap = day.capacityH()
                    val fill = if (cap <= 0.01) 1f else (used.getOrElse(i) { 0.0 } / cap).toFloat().coerceIn(0f, 1f)
                    val bg = if (cap <= 0.01) empty.copy(alpha = 0.5f) else lerp(empty, full, 0.12f + 0.88f * fill)
                    val number = if (lang.rtl) com.chronos.engine.Jalali.of(day.date()).day() else day.date().dayOfMonth
                    Column(Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)).background(bg)
                        .border(BorderStroke(if (i == picked) 2.dp else if (day.date() in troubled) 1.5.dp else 0.dp,
                            if (i == picked) MaterialTheme.colorScheme.onSurface else if (day.date() in troubled) Palette.Brick else Color.Transparent),
                            RoundedCornerShape(6.dp))
                        .clickable { picked = i }.padding(2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
                        Text(localiseDigits(lang, number.toString()), fontSize = 11.sp,
                            color = if (fill > 0.55f) Color.White else MaterialTheme.colorScheme.onSurface)
                        Row(horizontalArrangement = Arrangement.Center) {
                            dated.filter { it.deadline == day.date() }.take(3).forEach { g ->
                                Box(Modifier.padding(horizontal = 1.dp).size(6.dp).clip(CircleShape).background(GoalColors.target(goals, g.id))
                                    .border(BorderStroke(0.5.dp, Color.White), CircleShape))
                            }
                        }
                    }
                }
            }
            repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Box(Modifier.size(10.dp).background(lerp(empty, full, 0.12f)))
        Text("  " + tr("room left") + "    ", style = MaterialTheme.typography.labelSmall)
        Box(Modifier.size(10.dp).background(full))
        Text("  " + tr("full") + "    ", style = MaterialTheme.typography.labelSmall)
        Box(Modifier.size(8.dp).clip(CircleShape).background(Palette.Indigo))
        Text("  " + tr("deadline"), style = MaterialTheme.typography.labelSmall)
    }

    // The day picked: its room, what it holds, what it could not.
    val day = days.getOrNull(picked) ?: return
    val alloc = plan.allocation().getOrNull(picked).orEmpty().filterValues { it > 0 }
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text(dateText(day.date()), style = MaterialTheme.typography.titleSmall)
        Text(tr("usable") + " " + trimNumber(day.capacityH()) + " h  ·  " + tr("free") + " " + trimNumber(day.freeH()) + " h  ·  " +
            tr("upkeep") + " " + trimNumber(day.upkeepH()) + " h  ·  " + tr("travel") + " " + trimNumber(day.travelH()) + " h",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (alloc.isEmpty()) Hint("No goal hours on this day.")
        FlowRowSimple {
            alloc.entries.sortedByDescending { it.value }.forEach { (id, h) ->
                val g = vm.config.goal(id) ?: return@forEach
                Row(Modifier.padding(end = 6.dp, top = 6.dp).clip(RoundedCornerShape(12.dp))
                    .background(GoalColors.target(goals, id).copy(alpha = 0.15f)).padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(GoalColors.target(goals, id)))
                    Text("  " + (g.name ?: "?") + "  " + trimNumber(h) + " h", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        dated.filter { it.deadline == day.date() }.forEach { g ->
            Text("⚑ " + (g.name ?: "?") + " " + tr("is due") + (g.deadlineTime?.let { " $it" } ?: ""),
                style = MaterialTheme.typography.labelSmall, color = GoalColors.target(goals, g.id), modifier = Modifier.padding(top = 4.dp))
        }
        vm.upcoming.firstOrNull { it.date() == day.date() }?.let { p ->
            p.missing().forEach { m -> MissingRow(m.name() ?: "", m.reason() ?: "") }
            p.notes().forEach { n -> Hint(n) }
        }
    }
}

// --------------------------------------------------------------- the timeline

/**
 * Each dated goal as a row from today to its deadline, every day shaded by the hours the plan
 * gives it, its deadline marked; ongoing goals after, without an end. The axis runs left to
 * right in both languages, like the other time charts. Tap a row to change that goal.
 */
@Composable
internal fun GoalsTimeline(vm: ChronosViewModel, plan: HorizonPlanner.Plan) {
    val goals = vm.config.goals
    val days = plan.days()
    if (days.isEmpty()) return
    val start = days.first().date()
    val dated = datedInPlay(vm, start)
    val ongoing = vm.targetsInPlay().filter { g -> g.mode != Enums.GoalMode.CLOSED && plan.allocation().any { (it[g.id] ?: 0.0) > 0 } }
    val rows = dated + ongoing
    if (rows.isEmpty()) { Hint("No goal is waiting for hours in this horizon."); return }
    val short = vm.coverage.filter { !it.finishes() }.associateBy { it.goalId() }
    val cellW = 12.dp
    val rowH = 26.dp
    val track = MaterialTheme.colorScheme.surfaceVariant
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    Ltr {
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.width(118.dp)) {
                Spacer(Modifier.height(16.dp))
                rows.forEach { g ->
                    val total = g.totalUnits ?: 0.0
                    val pct = if (g.mode == Enums.GoalMode.CLOSED && total > 0) " " + Math.round(100 * (g.doneUnits ?: 0.0) / total) + "%" else ""
                    Column(Modifier.height(rowH).fillMaxWidth().clickable { vm.goFix("target:" + g.id) }, verticalArrangement = Arrangement.Center) {
                        Text((g.name ?: "?").trim() + pct, fontSize = 11.sp, lineHeight = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (g.id in short) Palette.Brick else GoalColors.target(goals, g.id))
                        short[g.id]?.let { Text(tr("short") + " " + trimNumber(Math.round(it.missingH() * 10) / 10.0) + " h", fontSize = 9.sp,
                            lineHeight = 10.sp, color = Palette.Brick) }
                    }
                }
            }
            Column(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                Row(Modifier.height(16.dp)) {
                    days.forEachIndexed { i, d ->
                        Box(Modifier.width(cellW)) {
                            if (i % 7 == 0) Text(d.date().dayOfMonth.toString(), fontSize = 9.sp, color = label,
                                maxLines = 1, softWrap = false)
                        }
                    }
                }
                rows.forEach { g ->
                    val color = GoalColors.target(goals, g.id)
                    val last = g.deadline?.let { java.time.temporal.ChronoUnit.DAYS.between(start, it).toInt() } ?: days.lastIndex
                    Row(Modifier.height(rowH).clickable { vm.goFix("target:" + g.id) }, verticalAlignment = Alignment.CenterVertically) {
                        days.forEachIndexed { i, _ ->
                            val h = plan.allocation().getOrNull(i)?.get(g.id) ?: 0.0
                            val inside = i <= last
                            Box(Modifier.width(cellW).height(16.dp).padding(horizontal = 0.5.dp)
                                .background(when {
                                    !inside -> Color.Transparent
                                    h > 0 -> color.copy(alpha = (0.35f + 0.65f * (h / 3.0).toFloat()).coerceAtMost(1f))
                                    else -> track
                                })) {
                                if (g.deadline != null && i == last)
                                    Box(Modifier.align(Alignment.CenterEnd).width(2.dp).fillMaxHeight().background(Palette.Brick))
                            }
                        }
                    }
                }
            }
        }
    }
    Hint("Each row runs from today to its deadline (the red mark); the darker a day, the more hours the plan gives it. " +
         "Tap a row to change the goal. Swipe sideways for the rest of the month.")
}
