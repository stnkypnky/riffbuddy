package com.riffbuddy.ui.tuner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import com.riffbuddy.ui.tactile.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import com.riffbuddy.ui.tactile.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.riffbuddy.domain.music.Notes
import com.riffbuddy.domain.music.Tuning
import com.riffbuddy.domain.music.TuningGroup

/**
 * The custom-tuning editor: a name, six to eight strings, and each string's
 * note and octave.
 *
 * Each string steps by a semitone rather than being typed. A typed "Eb2" is
 * easy to get wrong in ways that still parse ("Eb3" is a valid note an octave
 * away), and a tuning is almost always a few semitones off one you already
 * know — so the editor starts from the tuning on screen.
 */
@Composable
fun TuningEditorDialog(
    start: Tuning,
    /** Why the last save failed, e.g. a name already taken. */
    problem: String?,
    onDismiss: () -> Unit,
    onSave: (Tuning) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var name by remember { mutableStateOf(if (start.isCustom) start.name else "") }
    var midi by remember { mutableStateOf(start.midi.take(Tuning.STRING_COUNTS.last)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (start.isCustom) "Edit tuning" else "New tuning") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text("Name") },
                    singleLine = true,
                    isError = problem != null,
                    supportingText = problem?.let { { Text(it) } },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tuning.STRING_COUNTS.forEach { n ->
                        FilterChip(
                            selected = midi.size == n,
                            onClick = { midi = resized(midi, n) },
                            label = { Text("$n strings") },
                        )
                    }
                }
                Text(
                    "Low string first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                midi.forEachIndexed { i, note ->
                    val stringNumber = midi.size - i
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("String $stringNumber", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(
                            onClick = { midi = midi.toMutableList().also { it[i] = (note - 1).coerceIn(LOWEST, HIGHEST) } },
                            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "String $stringNumber down a semitone" },
                        ) { Text("−") }
                        Text(
                            Notes.name(note),
                            style = MaterialTheme.typography.titleSmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.widthIn(min = 48.dp),
                        )
                        TextButton(
                            onClick = { midi = midi.toMutableList().also { it[i] = (note + 1).coerceIn(LOWEST, HIGHEST) } },
                            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "String $stringNumber up a semitone" },
                        ) { Text("+") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onSave(
                        Tuning(
                            id = if (start.isCustom) start.id else 0,
                            name = name.trim(),
                            group = TuningGroup.CUSTOM,
                            midi = midi,
                            isCustom = true,
                        )
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/**
 * Adds or removes strings at the low end, where extended-range guitars add
 * them: a seventh string a fourth below the sixth, an eighth a fourth below that.
 */
internal fun resized(midi: List<Int>, count: Int): List<Int> {
    var out = midi
    while (out.size > count) out = out.drop(1)
    while (out.size < count) out = listOf((out.first() - 5).coerceAtLeast(LOWEST)) + out
    return out
}

/** A0 to C6: wider than any guitar string, narrow enough to catch a slip. */
private const val LOWEST = 21
private const val HIGHEST = 84
