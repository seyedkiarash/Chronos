package com.chronos.ui.common

import com.chronos.ui.*
import com.chronos.ui.setup.*
import com.chronos.ui.horizon.*
import com.chronos.ui.day.*
import com.chronos.ui.log.*
import com.chronos.ui.navigator.*

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Columns of time running down the page, each item a box with its own name on
 * it (your request of 23 Oct 2026) — no legend to look things up in. Items that
 * overlap in a column sit side by side, as calendar apps draw them, instead of
 * on top of each other. Used for Setup's week and for a day's plan beside what
 * was done.
 */

/** One box: [from, to) in minutes from midnight (to may pass 1440). */
internal data class TimeItem(val from: Int, val to: Int, val label: String, val color: Color, val solid: Boolean,
                             val onClick: (() -> Unit)? = null)

/** Lanes for one column: each item's lane and how many lanes its cluster of overlaps needs. */
private fun lanes(items: List<TimeItem>): List<Triple<TimeItem, Int, Int>> {
    val sorted = items.sortedWith(compareBy({ it.from }, { -(it.to - it.from) }))
    val out = mutableListOf<Triple<TimeItem, Int, Int>>()
    var cluster = mutableListOf<Pair<TimeItem, Int>>()
    var clusterEnd = Int.MIN_VALUE
    fun flush() {
        val n = (cluster.maxOfOrNull { it.second } ?: -1) + 1
        cluster.forEach { (it0, lane) -> out += Triple(it0, lane, n) }
        cluster = mutableListOf()
    }
    val laneEnds = mutableListOf<Int>()
    for (it0 in sorted) {
        if (it0.from >= clusterEnd) { flush(); laneEnds.clear() }
        var lane = laneEnds.indexOfFirst { it <= it0.from }
        if (lane < 0) { lane = laneEnds.size; laneEnds += it0.to } else laneEnds[lane] = it0.to
        cluster += it0 to lane
        clusterEnd = maxOf(clusterEnd, it0.to)
    }
    flush()
    return out
}

/**
 * @param titles  one heading per column
 * @param from/to the minutes the page shows (to may pass 1440)
 */
@Composable
internal fun TimeColumns(titles: List<String>, columns: List<List<TimeItem>>, from: Int, to: Int,
                         hourHeight: Dp = 44.dp, gutter: Dp = 34.dp) {
    val span = (to - from).coerceAtLeast(60)
    val height = hourHeight * (span / 60f)
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    Ltr {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = gutter)) {
                titles.forEach { t ->
                    Text(t, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = label,
                        textAlign = TextAlign.Center, maxLines = 1)
                }
            }
            Row {
                Box(Modifier.width(gutter).height(height)) {
                    var h = (from + 59) / 60 * 60
                    while (h < to) {
                        Text("%02d".format((h / 60) % 24), style = MaterialTheme.typography.labelSmall, color = label,
                            modifier = Modifier.offset(y = hourHeight * ((h - from) / 60f) - 7.dp))
                        h += 60
                    }
                }
                columns.forEach { items ->
                    BoxWithConstraints(Modifier.weight(1f).height(height)
                        .border(BorderStroke(0.5.dp, grid))) {
                        val w = maxWidth
                        // Hour lines.
                        var h = (from + 59) / 60 * 60
                        while (h < to) {
                            Box(Modifier.offset(y = hourHeight * ((h - from) / 60f)).fillMaxWidth().height(0.5.dp).background(grid))
                            h += 60
                        }
                        lanes(items.filter { it.to > from && it.from < to }).forEach { (it0, lane, n) ->
                            val top = hourHeight * ((maxOf(it0.from, from) - from) / 60f)
                            val tall = (hourHeight * ((minOf(it0.to, to) - maxOf(it0.from, from)) / 60f)).coerceAtLeast(10.dp)
                            val laneW = w / n
                            Box(Modifier.offset(x = laneW * lane + 1.dp, y = top + 1.dp)
                                .size(laneW - 2.dp, tall - 2.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(if (it0.solid) it0.color.copy(alpha = 0.88f) else it0.color.copy(alpha = 0.14f))
                                .border(BorderStroke(1.dp, it0.color.copy(alpha = if (it0.solid) 0f else 0.7f)), RoundedCornerShape(5.dp))
                                .let { m -> it0.onClick?.let { c -> m.clickable { c() } } ?: m }
                                .padding(horizontal = 2.dp, vertical = 1.dp),
                                contentAlignment = Alignment.TopStart) {
                                if (tall >= 14.dp)
                                    Text(it0.label, color = if (it0.solid) Palette.OnFill else it0.color,
                                        fontSize = 9.sp, lineHeight = 10.sp, maxLines = ((tall.value - 2) / 10).toInt().coerceAtLeast(1),
                                        overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}
