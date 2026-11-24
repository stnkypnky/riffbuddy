package com.riffbuddy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.riffbuddy.domain.NumberEntry
import com.riffbuddy.ui.tactile.FilledTonalIconButton
import com.riffbuddy.ui.tactile.FilterChip
import com.riffbuddy.ui.tactile.TextButton
import com.riffbuddy.ui.theme.Space
import com.riffbuddy.ui.theme.tactileClickable
import kotlin.math.abs

/*
 * Settings you can type. Every number the app asks for — a speed, a tempo, a
 * count, a reference pitch — takes any value its engine can play, not just the
 * few a row of chips happened to offer. The ± buttons and a short row of quick
 * picks stay for one-handed use with a guitar on your lap; tapping the value
 * itself opens it for typing.
 */

/**
 * One number setting: its label, − and + buttons, and the value between them.
 * Tap the value to type a new one; Done, or leaving the field, keeps it. A
 * value outside [range] is refused with the range in words rather than
 * changed, and the field goes back to the last good value when abandoned.
 * [quickPicks] draws a row of one-tap values under it.
 */
@Composable
fun ValueStepper(
    label: String,
    value: Double,
    range: ClosedFloatingPointRange<Double>,
    onValue: (Double) -> Unit,
    modifier: Modifier = Modifier,
    step: Double = 1.0,
    unit: String = "",
    decimals: Int = 0,
    quickPicks: List<Double> = emptyList(),
    enabled: Boolean = true,
    format: (Double) -> String = { NumberEntry.format(it, decimals) + unitSuffix(unit) },
) {
    var editing by remember { mutableStateOf(false) }
    // A control that goes quiet (a trainer took over the speed) closes its
    // field rather than leaving a stale value waiting to be written back.
    LaunchedEffect(enabled) { if (!enabled) editing = false }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            FilledTonalIconButton(
                onClick = { onValue(stepFrom(value, -step, range, decimals)) },
                enabled = enabled && value > range.start,
                modifier = Modifier.size(48.dp).semantics { contentDescription = "$label lower" },
            ) { Icon(Icons.Filled.Remove, contentDescription = null, modifier = Modifier.size(18.dp)) }
            // A floor wide enough for "130 BPM", so − and + sit in the same
            // place on every row whatever the length of the value between them.
            Box(Modifier.widthIn(min = READOUT_WIDTH).padding(horizontal = Space.xs), contentAlignment = Alignment.Center) {
                if (editing) {
                    NumberField(
                        initial = value, range = range, decimals = decimals, unit = unit, label = label,
                        onDone = { v -> if (v != null) onValue(v); editing = false },
                    )
                } else {
                    ValueReadout(
                        format(value), enabled = enabled, label = label, onClick = { editing = true },
                        modifier = Modifier.widthIn(min = READOUT_WIDTH - Space.sm),
                    )
                }
            }
            FilledTonalIconButton(
                onClick = { onValue(stepFrom(value, step, range, decimals)) },
                enabled = enabled && value < range.endInclusive,
                modifier = Modifier.size(48.dp).semantics { contentDescription = "$label higher" },
            ) { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp)) }
        }
        if (quickPicks.isNotEmpty()) {
            ChipFlow {
                quickPicks.forEach { pick ->
                    FilterChip(
                        selected = abs(pick - value) < 1e-9,
                        onClick = { onValue(pick) },
                        enabled = enabled,
                        label = { Text(format(pick)) },
                    )
                }
            }
        }
    }
}

/** The whole-number form of [ValueStepper]: counts, bars, frets, minutes. */
@Composable
fun IntStepper(
    label: String,
    value: Int,
    range: IntRange,
    onValue: (Int) -> Unit,
    modifier: Modifier = Modifier,
    step: Int = 1,
    unit: String = "",
    quickPicks: List<Int> = emptyList(),
    enabled: Boolean = true,
    format: (Int) -> String = { it.toString() + unitSuffix(unit) },
) {
    ValueStepper(
        label = label,
        value = value.toDouble(),
        range = range.first.toDouble()..range.last.toDouble(),
        onValue = { onValue(it.toInt()) },
        modifier = modifier,
        step = step.toDouble(),
        unit = unit,
        decimals = 0,
        quickPicks = quickPicks.map { it.toDouble() },
        enabled = enabled,
        format = { format(it.toInt()) },
    )
}

/**
 * A value that opens for typing when tapped: drawn as a soft pill with an
 * underline, so it reads as something you can change, and announced as
 * "double-tap to type".
 */
@Composable
fun ValueReadout(text: String, enabled: Boolean, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier
            .heightIn(min = 40.dp)
            .background(scheme.surfaceContainerHighest, MaterialTheme.shapes.small)
            .border(1.dp, scheme.outlineVariant, MaterialTheme.shapes.small)
            .tactileClickable(enabled = enabled, onClick = onClick)
            .semantics {
                contentDescription = "$label, $text"
                stateDescription = "Double-tap to type a value"
                onClick(label = "Type a value") { onClick(); true }
            }
            .padding(horizontal = Space.md, vertical = Space.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            color = if (enabled) scheme.onSurface else scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The typing half: focused at once with the old value selected, so typing
 * replaces it. [onDone] gets the new value, or null when the field was left
 * with nothing usable in it (which keeps the old one).
 */
@Composable
fun NumberField(
    initial: Double,
    range: ClosedFloatingPointRange<Double>,
    decimals: Int,
    unit: String,
    label: String,
    onDone: (Double?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val start = NumberEntry.format(initial, decimals)
    var field by remember { mutableStateOf(TextFieldValue(start, TextRange(0, start.length))) }
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    val result = NumberEntry.parse(field.text, range, decimals)
    val refused = result !is NumberEntry.Result.Ok && field.text.isNotBlank()
    fun finish() {
        if (finished) return
        finished = true
        // Untouched text is not a choice: writing it back could undo a change
        // made elsewhere while the field sat open.
        onDone(if (field.text == start) null else (result as? NumberEntry.Result.Ok)?.value)
    }
    OutlinedTextField(
        value = field,
        onValueChange = { field = it },
        singleLine = true,
        isError = refused,
        supportingText = {
            Text(
                (if (refused) "Use " else "") + NumberEntry.describe(range, decimals, unit),
                style = MaterialTheme.typography.labelSmall,
            )
        },
        textStyle = MaterialTheme.typography.titleMedium.copy(textAlign = TextAlign.Center),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (decimals > 0) KeyboardType.Decimal else KeyboardType.Number,
            imeAction = ImeAction.Done,
        ),
        // Done on a refused value keeps the field open, showing the range,
        // until it's fixed; leaving the field abandons it instead.
        keyboardActions = KeyboardActions(onDone = { if (!refused) finish() }),
        modifier = modifier
            // Wide enough that "Use 25 to 150%" fits under the number; a
            // longer range wraps to a second line rather than being cut off.
            .widthIn(min = READOUT_WIDTH, max = 168.dp)
            .focusRequester(focus)
            .onFocusChanged { state ->
                // Leaving the field is the same as Done.
                if (focused && !state.isFocused) finish()
                focused = state.isFocused
            }
            .semantics { contentDescription = "$label, type a value from ${NumberEntry.describe(range, decimals, unit)}" },
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/**
 * A number typed in a dialog, for a value drawn too big to edit in place: the
 * metronome's tempo, the tuner's reference pitch.
 */
@Composable
fun NumberEntryDialog(
    title: String,
    initial: Double,
    range: ClosedFloatingPointRange<Double>,
    onValue: (Double) -> Unit,
    onDismiss: () -> Unit,
    decimals: Int = 0,
    unit: String = "",
) {
    val start = NumberEntry.format(initial, decimals)
    var field by remember { mutableStateOf(TextFieldValue(start, TextRange(0, start.length))) }
    val focus = remember { FocusRequester() }
    val result = NumberEntry.parse(field.text, range, decimals)
    val ok = result as? NumberEntry.Result.Ok
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = field,
                onValueChange = { field = it },
                singleLine = true,
                isError = ok == null && field.text.isNotBlank(),
                supportingText = {
                    Text(
                        when (result) {
                            is NumberEntry.Result.OutOfRange -> "Use ${NumberEntry.describe(range, decimals, unit)}"
                            else -> NumberEntry.describe(range, decimals, unit)
                        }
                    )
                },
                textStyle = MaterialTheme.typography.headlineSmall.copy(textAlign = TextAlign.Center),
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (decimals > 0) KeyboardType.Decimal else KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { ok?.let { onValue(it.value); onDismiss() } }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { ok?.let { onValue(it.value); onDismiss() } }, enabled = ok != null) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/**
 * A time of day typed in a dialog: "7:30 pm", "19:30" or "730". Any minute
 * works, not only the quarter hours a pair of buttons can reach.
 */
@Composable
fun TimeEntryDialog(
    title: String,
    hour: Int,
    minute: Int,
    onTime: (hour: Int, minute: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val start = "$hour:${com.riffbuddy.domain.pad(minute)}"
    var field by remember { mutableStateOf(TextFieldValue(start, TextRange(0, start.length))) }
    val focus = remember { FocusRequester() }
    val parsed = NumberEntry.parseTime(field.text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = field,
                onValueChange = { field = it },
                singleLine = true,
                isError = parsed == null && field.text.isNotBlank(),
                supportingText = { Text("Like 7:30 pm or 19:30") },
                textStyle = MaterialTheme.typography.headlineSmall.copy(textAlign = TextAlign.Center),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { parsed?.let { onTime(it.first, it.second); onDismiss() } }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let { onTime(it.first, it.second); onDismiss() } }, enabled = parsed != null) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/** The slot between − and +, for the value whether it is being typed or not. */
private val READOUT_WIDTH = 108.dp

private fun unitSuffix(unit: String) = when {
    unit.isEmpty() -> ""
    unit == "%" -> "%"
    else -> " $unit"
}

/**
 * One press of − or +: moves by [step] and lands on the step grid, so 63 goes
 * to 65 and then 70 rather than 68 and 73, and stops at the ends of [range].
 */
internal fun stepFrom(value: Double, step: Double, range: ClosedFloatingPointRange<Double>, decimals: Int): Double {
    val size = abs(step)
    val onGrid = abs(value / size - kotlin.math.round(value / size)) < 1e-9
    val next = when {
        onGrid -> value + step
        step > 0 -> kotlin.math.ceil(value / size) * size
        else -> kotlin.math.floor(value / size) * size
    }
    return NumberEntry.round(next.coerceIn(range.start, range.endInclusive), decimals)
}
