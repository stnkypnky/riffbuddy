package com.riffbuddy.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import com.riffbuddy.ui.tactile.TextButton
import androidx.compose.runtime.Composable

/**
 * One tap away from losing something built by hand — a routine's steps,
 * a backing track's chart — is too close. Everything that deletes asks first.
 */
@Composable
fun ConfirmDelete(what: String, consequence: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete $what?") },
        text = { Text(consequence) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep") } },
    )
}
