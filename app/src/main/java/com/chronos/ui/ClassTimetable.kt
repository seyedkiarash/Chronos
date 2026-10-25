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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chronos.model.Commitment
import com.chronos.model.Enums

/*
 * Classes as a university timetable (your choice of 20 Oct 2026): the days of
 * the week down the side, the hours across, each class a block in its own
 * colour, the trips to and from as thin bars around the hours out of the house.
 * Tapping an empty hour adds a class there, or another session of one you have;
 * tapping a block opens that class at its times. In Persian the hours run from
 * right to left, as printed timetables do.
 */

private val CLASS_COLORS = listOf(
    Color(0xFFB07A3C), Color(0xFF3F7F87), Color(0xFF8A6A9E), Color(0xFF4A4E8C),
    Color(0xFF5E8C6A), Color(0xFF9E5A6B), Color(0xFF2F5D7C)
)

/** Saturday to Friday, short, as Persian timetables print them. */
private val FA_DAYS = arrayOf("شنبه", "۱شنبه", "۲شنبه", "۳شنبه", "۴شنبه", "۵شنبه", "جمعه")

private val HOUR_W = 46.dp
private val ROW_H = 48.dp
private val LABEL_W = 46.dp

private fun minutes(t: String?): Int? = t?.split(":")?.takeIf { it.size == 2 }?.let { p ->
    val h = p[0].toIntOrNull() ?: return null
    val m = p[1].toIntOrNull() ?: return null
    (h * 60 + m).let { if (it == 0 && t == "00:00") 1440 else it }
}

private fun hhmm(m: Int) = "%02d:%02d".format((m / 60) % 24, m % 60)

/** A class's colour, by its place among the classes, so it is the same block colour everywhere. */
internal fun classColor(cfg: com.chronos.model.Config, id: String?): Color {
    val i = cfg.commitments.filter { it.kind == Enums.CommitmentKind.FIXED }.indexOfFirst { it.id == id }
    return if (i < 0) Palette.Ochre else CLASS_COLORS[i % CLASS_COLORS.size]
}

private data class Placed(val c: Commitment, val index: Int, val day: Int, val from: Int, val to: Int, val color: Color)

@Composable
internal fun ClassTimetable(vm: ChronosViewModel, flagged: Set<String>, open: (Editing) -> Unit) {
    val cfg = vm.config
    val lang = LocalLang.current
    val classes = cfg.commitments.filter { it.kind == Enums.CommitmentKind.FIXED }
    val trips = cfg.commitments.filter { it.kind == Enums.CommitmentKind.VARIABLE }
    val placed = classes.flatMap { c ->
        c.sessions.mapIndexedNotNull { j, s ->
            val a = minutes(s.start) ?: return@mapIndexedNotNull null
            val b = minutes(s.end) ?: return@mapIndexedNotNull null
            if (b <= a) null else Placed(c, j, s.day, a, b, classColor(cfg, c.id))
        }
    }
    // Two classes at once cannot both be kept: both are outlined, and named below.
    val clashes = placed.filter { p -> placed.any { q -> q !== p && q.day == p.day && q.from < p.to && p.from < q.to } }.toSet()

    // Hours shown: at least 8 to 18, widened to every class and the trips around them.
    val trip = trips.firstOrNull()
    val tripMin = trip?.medianMin?.toInt() ?: 0
    val awayDays = (0..6).associateWith { d -> placed.filter { it.day == d && it.c.away } }
    val earliest = (placed.map { it.from } + awayDays.values.filter { it.isNotEmpty() }.map { it.minOf { p -> p.from } - tripMin }).minOrNull()
    val latest = (placed.map { it.to } + awayDays.values.filter { it.isNotEmpty() }.map { it.maxOf { p -> p.to } + tripMin }).maxOrNull()
    val firstH = minOf(8, (earliest ?: 480) / 60).coerceAtLeast(0)
    val lastH = maxOf(18, ((latest ?: 1080) + 59) / 60).coerceAtMost(24)
    val hours = lastH - firstH

    var adding by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    fun x(min: Int): Dp = HOUR_W * ((min - firstH * 60) / 60f)

    Hint("Tap an empty hour to add a class there; tap a class to change it.")
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Column(Modifier.width(LABEL_W)) {
            Spacer(Modifier.height(20.dp))
            (0..6).forEach { d ->
                Box(Modifier.height(ROW_H).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(if (lang.rtl) FA_DAYS[d] else com.chronos.engine.Slots.DOW[d],
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1)
                }
            }
        }
        val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
        Box(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
            Column {
                Row(Modifier.height(20.dp)) {
                    (firstH until lastH).forEach { h ->
                        Text(localiseDigits(lang, "%02d".format(h)), Modifier.width(HOUR_W),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Box(Modifier.width(HOUR_W * hours).height(ROW_H * 7)) {
                    // Empty hours, each a place to add a class.
                    (0..6).forEach { d ->
                        (firstH until lastH).forEach { h ->
                            Box(Modifier.offset(x = HOUR_W * (h - firstH), y = ROW_H * d).size(HOUR_W, ROW_H)
                                .border(BorderStroke(0.5.dp, grid)).clickable { adding = d to h })
                        }
                    }
                    // The trips around the hours out of the house.
                    if (trip != null && tripMin > 0) awayDays.forEach { (d, list) ->
                        if (list.isEmpty()) return@forEach
                        val go = list.minOf { it.from }
                        val back = list.maxOf { it.to }
                        listOfNotNull(
                            (go - tripMin to go).takeIf { trip.outbound?.mode != Enums.LegMode.NONE },
                            (back to back + tripMin).takeIf { trip.inbound?.mode != Enums.LegMode.NONE }
                        ).forEach { (a, b) ->
                            Box(Modifier.offset(x = x(a), y = ROW_H * d + ROW_H - 9.dp).size(x(b) - x(a), 5.dp)
                                .clip(RoundedCornerShape(3.dp)).background(Palette.Muted.copy(alpha = 0.55f))
                                .clickable { open(Editing.OfCommitment(trip.copy(), false)) })
                        }
                    }
                    placed.forEach { p ->
                        val clash = p in clashes
                        Box(Modifier.offset(x = x(p.from) + 1.dp, y = ROW_H * p.day + 3.dp)
                            .size(x(p.to) - x(p.from) - 2.dp, ROW_H - 14.dp)
                            .clip(RoundedCornerShape(6.dp)).background(p.color.copy(alpha = 0.88f))
                            .border(BorderStroke(if (clash || p.c.id in flagged) 2.dp else 0.dp,
                                if (clash || p.c.id in flagged) Palette.Brick else Color.Transparent), RoundedCornerShape(6.dp))
                            .clickable { open(Editing.OfCommitment(p.c.copy(), false, "Times")) }
                            .padding(horizontal = 4.dp, vertical = 2.dp)) {
                            Column {
                                Text(p.c.name ?: "?", color = Color.White, fontSize = 11.sp, lineHeight = 12.sp,
                                    fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(localiseDigits(lang, hhmm(p.from) + "–" + hhmm(p.to)), color = Color.White.copy(alpha = 0.85f),
                                    fontSize = 9.sp, lineHeight = 10.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
    if (clashes.isNotEmpty()) {
        val names = clashes.map { it.c.name ?: "?" }.distinct().joinToString("، ")
        Text(tr("These overlap; only one can happen at a time") + ": " + names, style = MaterialTheme.typography.labelSmall,
            color = Palette.Brick, modifier = Modifier.padding(top = 4.dp))
    }

    // The classes themselves, and the trips, as a list beside the grid.
    FlowRowSimple {
        classes.forEach { c ->
            val color = classColor(cfg, c.id)
            Row(Modifier.padding(end = 6.dp, top = 6.dp).clip(RoundedCornerShape(14.dp))
                .background(color.copy(alpha = 0.13f))
                .border(BorderStroke(1.dp, if (c.id in flagged) Palette.Brick else color.copy(alpha = 0.4f)), RoundedCornerShape(14.dp))
                .clickable { open(Editing.OfCommitment(c.copy(), false)) }.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(6.dp))
                Text((c.name ?: "?") + "  ·  " + c.sessions.size + " " + tr(if (c.sessions.size == 1) "session" else "sessions") +
                    (if (!c.away) "  ·  " + tr("at home") else ""),
                    style = MaterialTheme.typography.labelMedium, color = color)
            }
        }
        trips.forEach { t ->
            Row(Modifier.padding(end = 6.dp, top = 6.dp).clip(RoundedCornerShape(14.dp))
                .border(BorderStroke(1.dp, if (t.id in flagged) Palette.Brick else Palette.Muted.copy(alpha = 0.5f)), RoundedCornerShape(14.dp))
                .clickable { open(Editing.OfCommitment(t.copy(), false)) }.padding(horizontal = 10.dp, vertical = 6.dp)) {
                Text("➝ " + (t.name ?: "?") + (t.medianMin?.let { "  ·  " + it.toInt() + " " + tr("min") } ?: ""),
                    style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
            }
        }
    }
    Row(Modifier.padding(top = 6.dp)) {
        TextButton(onClick = { open(Editing.OfCommitment(newClass(null), true, "Basics")) }) { Text("+ " + tr("Class")) }
        TextButton(onClick = { open(Editing.OfCommitment(newTrip(), true, "Basics")) }) { Text("+ " + tr("Travel")) }
    }

    adding?.let { (d, h) ->
        val at = (if (lang.rtl) FA_DAYS[d] else com.chronos.engine.Slots.DOW[d]) + " " + localiseDigits(lang, "%02d:00".format(h))
        AlertDialog(
            onDismissRequest = { adding = null },
            title = { Text(at) },
            text = {
                Column {
                    TextButton(onClick = {
                        adding = null
                        open(Editing.OfCommitment(newClass(Commitment.Session(d, hhmm(h * 60), hhmm(minOf(24, h + 2) * 60))), true, "Basics"))
                    }) { Text("+ " + tr("A new class at this time")) }
                    if (classes.isNotEmpty()) Hint("Or another session of:")
                    classes.forEach { c ->
                        TextButton(onClick = {
                            adding = null
                            val copy = c.copy()
                            val len = c.sessions.firstOrNull()?.let { s ->
                                val a = minutes(s.start); val b = minutes(s.end)
                                if (a != null && b != null && b > a) b - a else null
                            } ?: 120
                            copy.sessions.add(Commitment.Session(d, hhmm(h * 60), hhmm(minOf(1440, h * 60 + len))))
                            open(Editing.OfCommitment(copy, false, "Times"))
                        }) {
                            Box(Modifier.size(9.dp).clip(CircleShape).background(classColor(cfg, c.id)))
                            Spacer(Modifier.width(8.dp))
                            Text(c.name ?: "?")
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { adding = null }) { Text("Cancel") } }
        )
    }
}

/** A class, out of the house, with its first session when it was started from the grid. */
private fun newClass(first: Commitment.Session?) = Commitment().apply {
    id = java.util.UUID.randomUUID().toString(); name = ""
    kind = Enums.CommitmentKind.FIXED; away = true
    if (first != null) sessions.add(first)
}

/** A trip with the starting values the Travel kind always had. */
private fun newTrip() = Commitment().apply {
    id = java.util.UUID.randomUUID().toString(); name = ""
    kind = Enums.CommitmentKind.VARIABLE
    medianMin = 30.0; sigma = 0.25; quantile = 0.8; load = 0.2; recovery = 0.0; physLoad = 0.2; physRecovery = 0.0
}
