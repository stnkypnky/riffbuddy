package com.riffbuddy.ui.gear

import androidx.compose.foundation.horizontalScroll

import com.riffbuddy.domain.gear.ControlRole

import com.riffbuddy.domain.gear.Reach

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import com.riffbuddy.ui.tactile.FilterChip
import androidx.compose.material3.Icon
import com.riffbuddy.ui.tactile.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.riffbuddy.ui.tactile.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.riffbuddy.domain.gear.AmpControl
import com.riffbuddy.domain.gear.ControlKind

/**
 * A full-screen editor: Cancel, a title, Save. Full screen rather than an
 * alert because an amp's control list does not fit in one, and a dialog that
 * scrolls inside a screen that scrolls is two things to fight with a thumb.
 */
@Composable
fun EditorSheet(
    title: String,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    saveEnabled: Boolean,
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onSave, enabled = saveEnabled) { Text("Save", fontWeight = FontWeight.SemiBold) }
                }
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) { content() }
            }
        }
    }
}

@Composable
fun SectionLabel(text: String) = com.riffbuddy.ui.SectionHeader(text)

@Composable
fun Field(label: String, value: String, modifier: Modifier = Modifier.fillMaxWidth(), onChange: (String) -> Unit) =
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true, modifier = modifier)

/** Up, down and remove, each 48 dp, with what they act on in the label. */
@Composable
private fun RowTools(what: String, index: Int, size: Int, onMove: (Int) -> Unit, onRemove: () -> Unit) {
    IconButton(onClick = { onMove(-1) }, enabled = index > 0, modifier = Modifier.size(48.dp)) {
        Icon(Icons.Filled.ArrowUpward, contentDescription = "Move $what up")
    }
    IconButton(onClick = { onMove(1) }, enabled = index < size - 1, modifier = Modifier.size(48.dp)) {
        Icon(Icons.Filled.ArrowDownward, contentDescription = "Move $what down")
    }
    IconButton(onClick = onRemove, modifier = Modifier.size(48.dp)) {
        Icon(Icons.Filled.Close, contentDescription = "Remove $what")
    }
}

fun <T> List<T>.moved(index: Int, by: Int): List<T> {
    val to = index + by
    if (to !in indices) return this
    return toMutableList().also { val x = it.removeAt(index); it.add(to, x) }
}

/** An ordered list of names — pickups, guitar controls, channels, pedal knobs. */
@Composable
fun NameListEditor(label: String, items: List<String>, placeholder: String, onChange: (List<String>) -> Unit) {
    var adding by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionLabel(label)
        items.forEachIndexed { i, item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                RowTools(item, i, items.size, { by -> onChange(items.moved(i, by)) }, { onChange(items - item) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = adding, onValueChange = { adding = it }, singleLine = true,
                placeholder = { Text(placeholder) }, modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { adding.trim().takeIf { it.isNotEmpty() && it !in items }?.let { onChange(items + it) }; adding = "" },
                enabled = adding.isNotBlank(),
            ) { Text("Add") }
        }
    }
}

/**
 * The ordered amp control list: knobs (0–10 or a custom range),
 * switches and selectors, added, removed and reordered.
 */
@Composable
fun ControlListEditor(controls: List<AmpControl>, onChange: (List<AmpControl>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("CONTROLS, LEFT TO RIGHT ON THE PANEL")
        controls.forEachIndexed { i, c ->
            fun set(updated: AmpControl) = onChange(controls.toMutableList().also { it[i] = updated })
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = c.name, onValueChange = { set(c.copy(name = it)) }, singleLine = true,
                            label = { Text("Name") }, modifier = Modifier.weight(1f),
                        )
                        RowTools(c.name.ifBlank { "control" }, i, controls.size, { by -> onChange(controls.moved(i, by)) }, { onChange(controls.filterIndexed { j, _ -> j != i }) })
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ControlKind.entries.forEach { k ->
                            FilterChip(selected = c.kind == k, onClick = { set(c.copy(kind = k)) }, label = { Text(k.label) })
                        }
                        // What the tone card needs to know: whether the knob
                        // is on the front (or only in a display or app), and
                        // whether it is loudness, which a card never sets.
                        FilterChip(
                            selected = c.reach == Reach.DISPLAY,
                            onClick = { set(c.copy(reach = if (c.reach == Reach.DISPLAY) Reach.PANEL else Reach.DISPLAY)) },
                            label = { Text("Not on the panel") },
                        )
                        FilterChip(
                            selected = c.role == ControlRole.LEVEL,
                            onClick = { set(c.copy(role = if (c.role == ControlRole.LEVEL) ControlRole.TONE else ControlRole.LEVEL)) },
                            label = { Text("Loudness only") },
                        )
                    }
                    when (c.kind) {
                        ControlKind.KNOB -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            NumberBox("From", c.min) { set(c.copy(min = it)) }
                            NumberBox("To", c.max) { set(c.copy(max = it)) }
                            if (c.min >= c.max) Text("From must be below To", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        ControlKind.SELECTOR -> OutlinedTextField(
                            value = c.options.joinToString(", "),
                            onValueChange = { t -> set(c.copy(options = t.split(",").map { it.trim() }.filter { it.isNotEmpty() })) },
                            label = { Text("Positions, comma-separated") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                        ControlKind.SWITCH -> Unit
                    }
                }
            }
        }
        TextButton(onClick = { onChange(controls + AmpControl("")) }, enabled = controls.size < 16) {
            Text(if (controls.size < 16) "Add a control" else "16 is the most a card can draw")
        }
    }
}

/** A number typed as text, kept as typed until it parses. */
@Composable
private fun NumberBox(label: String, value: Double, onValue: (Double) -> Unit) {
    var text by remember(value) { mutableStateOf(fmt(value)) }
    OutlinedTextField(
        value = text,
        onValueChange = { t -> text = t; t.toDoubleOrNull()?.let(onValue) },
        label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.widthIn(max = 110.dp).heightIn(min = 56.dp),
    )
}

private fun fmt(v: Double) = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
