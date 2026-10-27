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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.chronos.engine.Jalali
import java.time.LocalDate

/* Small pieces shared by every screen. Nothing here knows about the model. */

/**
 * A card. With an [accent] it gets a coloured left rule, a tinted surface and a
 * coloured title — how the Setup wizard tells its sections apart at a glance.
 */
@Composable
fun Panel(title: String? = null, accent: Color? = null,
          content: @Composable ColumnScope.() -> Unit) {
    val surface = MaterialTheme.colorScheme.surface
    val container = if (accent == null) surface else accent.copy(alpha = 0.07f).compositeOver(surface)
    val border = accent?.copy(alpha = 0.5f) ?: MaterialTheme.colorScheme.outline
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = container),
        border = BorderStroke(1.dp, border),
        shape = RoundedCornerShape(6.dp)
    ) {
        // The accent rule is painted behind the content rather than laid out beside it.
        // Laying it out needed the row's intrinsic height, and a long form (time
        // pickers, number boxes, wrapped tags) could be measured short and have its
        // last rows — the Save button among them — clipped off by the card.
        Box(Modifier.fillMaxWidth().drawBehind {
            if (accent != null) {
                val w = 4.dp.toPx()
                // On the start side: the right in Persian, as the laid-out rule was.
                val x = if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl) size.width - w else 0f
                drawRect(accent, topLeft = androidx.compose.ui.geometry.Offset(x, 0f),
                    size = androidx.compose.ui.geometry.Size(w, size.height))
            }
        }) {
            Column(Modifier.fillMaxWidth().padding(start = if (accent != null) 20.dp else 16.dp,
                top = 16.dp, end = 16.dp, bottom = 16.dp)) {
                if (title != null) {
                    Text(title, style = MaterialTheme.typography.titleMedium,
                        color = accent ?: MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(10.dp))
                }
                content()
            }
        }
    }
}

@Composable
fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 3.dp)
    )
}

@Composable
fun ScreenHeader(title: String, blurb: String) {
    Column(Modifier.padding(bottom = 10.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(5.dp))
        Text(blurb, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

enum class Tone { Good, Warn, Bad }

@Composable
fun Verdict(tone: Tone, title: String, body: String) {
    val accent = when (tone) {
        Tone.Good -> Palette.Moss; Tone.Warn -> Palette.Sand; Tone.Bad -> Palette.Brick
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp)
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(6.dp))
            .background(accent.copy(alpha = 0.08f))
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(accent))
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (body.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(body, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun Meter(fraction: Float, color: Color = MaterialTheme.colorScheme.primary) {
    Box(
        Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            Modifier.fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .background(if (fraction > 1f) Palette.Brick else color)
        )
    }
}

@Composable
fun Stat(label: String, value: String, sub: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.headlineMedium)
        Text(sub, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/* ---------------------------------------------------------------- inputs */

@Composable
fun TextField(
    label: String,
    value: String,
    hint: String? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit
) {
    val focus = LocalFocusManager.current
    Column(modifier.padding(vertical = 4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
            enabled = enabled,
            singleLine = true,
            // Done closes the keyboard and releases the field. Without this the
            // caret stays in the box after the keyboard goes, and the next tap
            // edits the wrong thing.
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
            modifier = Modifier.fillMaxWidth()
        )
        if (hint != null) Hint(hint)
    }
}

/**
 * A number field that keeps what was typed while it is being typed. Parsing on
 * every keystroke would delete a half-written "0." before the user finishes it,
 * and an empty box has to stay empty so Readiness can report it as unanswered.
 */
@Composable
fun NumberField(
    label: String,
    value: Double?,
    hint: String? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onChange: (Double?) -> Unit
) {
    val focus = LocalFocusManager.current
    var text by remember { mutableStateOf(value?.let { trimNumber(it) } ?: "") }
    // Resync only when the stored value stops matching what is typed (e.g. it was
    // changed elsewhere); a keystroke that parses to the same number keeps "0.".
    LaunchedEffect(value) {
        val typed = if (text.isBlank()) null else text.toDoubleOrNull()
        if (typed != value) text = value?.let { trimNumber(it) } ?: ""
    }
    Column(modifier.padding(vertical = 4.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                onChange(if (it.isBlank()) null else it.toDoubleOrNull() ?: value)
            },
            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
            modifier = Modifier.fillMaxWidth()
        )
        if (hint != null) Hint(hint)
    }
}

@Composable
fun IntField(
    label: String, value: Int?, hint: String? = null, enabled: Boolean = true,
    modifier: Modifier = Modifier, onChange: (Int?) -> Unit
) = NumberField(label, value?.toDouble(), hint, enabled, modifier) { onChange(it?.toInt()) }

/**
 * A number as people read it: at most two decimals, no trailing zeros. Learned
 * values (and slider floats widened to Double) would otherwise print a dozen digits.
 */
internal fun trimNumber(v: Double): String =
    if (v.isNaN() || v.isInfinite()) v.toString()
    else java.math.BigDecimal(v).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        .let { if (it == "-0") "0" else it }

/** A clock time, entered in the platform picker so nobody types a colon. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeField(label: String, value: String?, modifier: Modifier = Modifier,
              onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val shown = value ?: "--:--"
    Column(modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(shown)
        }
    }
    if (open) {
        val parts = (value ?: "09:00").split(":")
        val state = rememberTimePickerState(
            initialHour = parts.getOrNull(0)?.toIntOrNull() ?: 9,
            initialMinute = parts.getOrNull(1)?.toIntOrNull() ?: 0,
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    onChange(String.format("%02d:%02d", state.hour, state.minute))
                    open = false
                }) { Text("Set") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
            text = { TimePicker(state = state) }
        )
    }
}

/** A date, shown in both calendars because the week starts on Saturday here. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(label: String, value: LocalDate?, modifier: Modifier = Modifier,
              onChange: (LocalDate) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(if (value == null) "Not set" else dateText(value))
        }
    }
    if (open) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (value ?: LocalDate.now())
                .toEpochDay() * 86_400_000L
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(LocalDate.ofEpochDay(it / 86_400_000L)) }
                    open = false
                }) { Text("Set") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } }
        ) { DatePicker(state = state) }
    }
}

@Composable
fun <T> Choice(label: String, value: T, options: List<Pair<T, String>>,
               hint: String? = null, modifier: Modifier = Modifier, onChange: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(options.firstOrNull { it.first == value }?.second ?: "Choose")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (v, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { onChange(v); open = false })
            }
        }
        if (hint != null) Hint(hint)
    }
}

@Composable
fun Toggle(label: String, checked: Boolean, hint: String? = null, onChange: (Boolean) -> Unit) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = checked, onCheckedChange = onChange)
            Spacer(Modifier.width(10.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
        if (hint != null) Hint(hint)
    }
}

@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier.padding(end = 4.dp, bottom = 4.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(text, color = fg, style = MaterialTheme.typography.labelSmall)
    }
}

/** Weekday picker. Saturday first, to match the week the planner uses. */
@Composable
fun DayPicker(days: Set<Int>, onChange: (Set<Int>) -> Unit) {
    FlowRowSimple {
        com.chronos.engine.Slots.DOW.forEachIndexed { index, name ->
            Pill(name, days.contains(index)) {
                onChange(if (days.contains(index)) days - index else days + index)
            }
        }
    }
}

@Composable
fun TagPicker(all: List<String>, chosen: List<String>, onChange: (List<String>) -> Unit) {
    FlowRowSimple {
        all.forEach { tag ->
            Pill(tag, chosen.contains(tag)) {
                onChange(if (chosen.contains(tag)) chosen - tag else chosen + tag)
            }
        }
    }
}

/** One to five, with the ends named so the number means something. */
@Composable
fun Scale(label: String, value: Int, low: String, high: String, hint: String? = null,
          onChange: (Int) -> Unit) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            (1..5).forEach { n ->
                Box(Modifier.weight(1f).padding(end = 4.dp)) {
                    Pill(n.toString(), value == n) { onChange(n) }
                }
            }
        }
        Hint("$low  to  $high" + if (hint != null) "  ·  $hint" else "")
    }
}

/**
 * One thing a day could not hold: its name, and underneath why and what to
 * change. Two texts rather than one, so the reason is translated while the
 * name — the user's own word — is left alone.
 */
@Composable
fun MissingRow(name: String, reason: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(name, style = MaterialTheme.typography.bodySmall, color = Palette.Brick)
        if (reason.isNotBlank())
            Text(reason, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Wrapping row of pills. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FlowRowSimple(content: @Composable () -> Unit) {
    FlowRow(modifier = Modifier.fillMaxWidth()) { content() }
}
