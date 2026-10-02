package com.riffbuddy.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import com.riffbuddy.ui.tactile.FilterChip
import androidx.compose.material3.MaterialTheme
import com.riffbuddy.ui.tactile.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import com.riffbuddy.ui.tactile.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.riffbuddy.data.BackupFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What Settings knows about the data on this device. */
data class DataState(
    val rowCounts: Map<String, Int> = emptyMap(),
    val lastBackup: Instant? = null,
    val busy: String? = null,
    val message: String? = null,
    val problem: String? = null,
    /** Set once a file has been inspected and is waiting for the player's decision. */
    val pendingImport: PendingImport? = null,
)

data class PendingImport(val summary: Map<String, Int>, val createdAt: Instant?)

/**
 * Export, import and clear.
 *
 * The import flow deliberately has a stop in the middle: the file is read and
 * summarized, and nothing is applied until the player has seen what is in it and
 * chosen how. A restore is the one operation here that can destroy a year of
 * practice history, and it should not be one tap away from a mis-tap.
 */
@Composable
fun DataSection(
    state: DataState,
    onExport: () -> Unit,
    onPickFile: () -> Unit,
    onConfirmImport: (BackupFormat.Mode) -> Unit,
    onCancelImport: () -> Unit,
    onClearAll: () -> Unit,
) {
    var confirmingClear by remember { mutableStateOf(false) }

    com.riffbuddy.ui.Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            com.riffbuddy.ui.SectionHeader("YOUR DATA")

            val total = state.rowCounts.values.sum()
            Text(
                if (total == 0) "Nothing saved yet."
                else "$total rows across ${state.rowCounts.size} tables — " +
                    state.rowCounts.entries.sortedByDescending { it.value }
                        .take(3).joinToString(", ") { "${it.value} ${it.key}" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                state.lastBackup?.let { "Last backed up ${DATE.format(it.atZone(ZoneId.systemDefault()))}" }
                    ?: "Never backed up.",
                style = MaterialTheme.typography.bodySmall,
                color = if (BackupFormat.needsReminder(state.lastBackup))
                    MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onExport,
                    enabled = state.busy == null && total > 0,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Export") }
                OutlinedButton(
                    onClick = onPickFile,
                    enabled = state.busy == null,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Import") }
            }

            Text(
                "The file holds your songs, sessions, gear and tone cards. Your API keys are " +
                    "not in it — they never leave secure storage on this device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            state.busy?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            state.message?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
            }
            state.problem?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }

            TextButton(onClick = { confirmingClear = true }) { Text("Clear all data") }
        }
    }

    state.pendingImport?.let { pending ->
        ImportDialog(pending, onConfirmImport, onCancelImport)
    }

    if (confirmingClear) {
        ClearAllDialog(onCancel = { confirmingClear = false }, onConfirm = {
            confirmingClear = false
            onClearAll()
        })
    }
}

/** Show a summary and offer Merge or Replace before anything is applied. */
@Composable
private fun ImportDialog(
    pending: PendingImport,
    onConfirm: (BackupFormat.Mode) -> Unit,
    onCancel: () -> Unit,
) {
    var mode by remember { mutableStateOf(BackupFormat.Mode.MERGE) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Restore this backup?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                pending.createdAt?.let {
                    Text(
                        "Made ${DATE.format(it.atZone(ZoneId.systemDefault()))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    pending.summary.entries.sortedByDescending { it.value }
                        .joinToString("\n") { "${it.value} ${it.key}" },
                    style = MaterialTheme.typography.bodyMedium,
                )
                // A flow, not a row: in a phone-width dialog a row squeezed the
                // second chip until "Replace all" broke over two lines. A chip
                // that doesn't fit moves down whole instead.
                com.riffbuddy.ui.ChipFlow {
                    BackupFormat.Mode.entries.forEach { candidate ->
                        FilterChip(
                            selected = mode == candidate,
                            onClick = { mode = candidate },
                            label = { Text(candidate.label, maxLines = 1, softWrap = false) },
                            modifier = Modifier.heightIn(min = 40.dp),
                        )
                    }
                }
                Text(
                    mode.explanation,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (mode == BackupFormat.Mode.REPLACE) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(mode) }) { Text("Restore") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/**
 * Clearing everything requires typing a word.
 *
 * Not a nuisance. This is the only button in the app that destroys a year of
 * practice history with no way back, and a confirmation you can tap through
 * without reading is not a confirmation.
 */
@Composable
private fun ClearAllDialog(onCancel: () -> Unit, onConfirm: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    val word = "DELETE"

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Clear everything?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Every song, session, tone card and gear profile goes, and there is no " +
                        "undo. Export first if you are not certain.",
                )
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text("Type $word to confirm") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = typed.trim().equals(word, ignoreCase = false)) {
                Text("Clear everything")
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Keep it") } },
    )
}

private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy, h:mm a")
