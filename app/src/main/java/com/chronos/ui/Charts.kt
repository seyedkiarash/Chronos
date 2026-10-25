package com.chronos.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.chronos.engine.Slots
import com.chronos.model.Block
import com.chronos.model.Goal

/**
 * The day plot. Capacity is the solid line, mental fatigue the dashed red one,
 * physical fatigue the dotted ochre one, and the
 * blocks sit underneath on the same time axis so the arrangement can be read
 * against the curve that produced it.
 */
@Composable
fun DayPlot(
    capacity: DoubleArray,
    fatigue: DoubleArray,
    physical: DoubleArray? = null,
    /** Stimulation from leisure; drawn only on a day that has some. */
    dopamine: DoubleArray? = null,
    blocks: List<Block>,
    gaps: List<Block>,
    goals: List<Goal>,
    safeLine: Double,
    selected: Block?,
    onPick: (Block) -> Unit
) {
    val all = (blocks + gaps).sortedBy { it.start }
    val lanes = assignLanes(all)
    val laneCount = (lanes.values.maxOrNull() ?: 0) + 1
    val outline = MaterialTheme.colorScheme.outline
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val selectionColor = MaterialTheme.colorScheme.onSurface
    val upkeepColor = Palette.Moss.copy(alpha = 0.55f)

    Column {
        Canvas(
            Modifier.fillMaxWidth()
                .height((150 + laneCount * 22).dp)
                .pointerInput(all) {
                    detectTapGestures { offset ->
                        val laneTop = size.height - laneCount * 22f * density
                        val laneHeight = 22f * density
                        val lane = ((offset.y - laneTop) / laneHeight).toInt()
                        val slot = (offset.x / size.width * Slots.PER_DAY).toInt()
                        all.firstOrNull {
                            lanes[it] == lane && slot >= it.start && slot < it.start + it.dur
                        }?.let(onPick)
                    }
                }
        ) {
            val laneHeight = 22.dp.toPx()
            val laneArea = laneCount * laneHeight
            val plotHeight = size.height - laneArea
            fun x(slot: Double): Float = ((slot / Slots.PER_DAY) * size.width).toFloat()
            fun y(v: Double): Float = (plotHeight * (1.0 - v)).toFloat()

            // grid
            listOf(0.0, 0.5, 1.0).forEach {
                drawLine(outline.copy(alpha = 0.4f), Offset(0f, y(it)), Offset(size.width, y(it)), 1f)
            }
            for (t in 0..Slots.PER_DAY step 16) {
                val px = x(t.toDouble())
                drawLine(outline.copy(alpha = 0.25f),
                    Offset(px, 0f), Offset(px, plotHeight + laneArea), 1f)
            }
            drawLine(Palette.Sand.copy(alpha = 0.7f), Offset(0f, y(safeLine)),
                Offset(size.width, y(safeLine)), 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))

            if (fatigue.isNotEmpty()) {
                drawTrace(fatigue, ::x, ::y, Palette.Brick.copy(alpha = 0.85f), 2f,
                    PathEffect.dashPathEffect(floatArrayOf(10f, 7f)))
                if (physical != null && physical.isNotEmpty())
                    drawTrace(physical, ::x, ::y, Palette.Ochre.copy(alpha = 0.85f), 2f,
                        PathEffect.dashPathEffect(floatArrayOf(3f, 5f)))
                drawTrace(capacity, ::x, ::y, Palette.Steel, 4f, null)
            }
            if (dopamine != null && dopamine.any { it > 0.01 })
                drawTrace(dopamine, ::x, ::y, Palette.Plum.copy(alpha = 0.9f), 2f,
                    PathEffect.dashPathEffect(floatArrayOf(2f, 4f)))

            // blocks
            all.forEach { b ->
                val lane = lanes[b] ?: 0
                val top = plotHeight + lane * laneHeight + 2f
                val left = x(b.start.toDouble())
                val width = x((b.start + b.dur).toDouble()) - left
                val color = when {
                    b.gap -> surfaceVariant
                    b.fixed -> outline
                    b.goalId != null -> GoalColors.target(goals, b.goalId)
                    b.leisure -> Palette.Plum.copy(alpha = 0.6f)
                    else -> upkeepColor
                }
                drawRect(color, Offset(left, top), Size(width, laneHeight - 5f))
                if (selected === b) {
                    drawRect(selectionColor, Offset(left, top),
                        Size(width, laneHeight - 5f), style = Stroke(width = 3f))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(0, 24, 48, 72, 95).forEach {
                Text(Slots.format(it), style = MaterialTheme.typography.labelSmall, color = muted)
            }
        }
    }
}

private fun DrawScope.drawTrace(
    values: DoubleArray,
    x: (Double) -> Float,
    y: (Double) -> Float,
    color: Color,
    width: Float,
    effect: PathEffect?
) {
    val path = Path()
    values.forEachIndexed { i, v ->
        val px = x(i.toDouble()); val py = y(v)
        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
    }
    drawPath(path, color, style = Stroke(width = width, pathEffect = effect))
}

/** Overlapping blocks stack, so nothing is hidden behind anything else. */
private fun assignLanes(blocks: List<Block>): Map<Block, Int> {
    val lanes = mutableListOf<MutableList<Block>>()
    val out = mutableMapOf<Block, Int>()
    blocks.forEach { b ->
        var lane = 0
        while (lane < lanes.size &&
               lanes[lane].any { !(b.start + b.dur <= it.start || b.start >= it.start + it.dur) }) lane++
        if (lane == lanes.size) lanes.add(mutableListOf())
        lanes[lane].add(b)
        out[b] = lane
    }
    return out
}

