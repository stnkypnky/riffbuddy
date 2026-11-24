package com.riffbuddy.ui.log

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.riffbuddy.domain.Session
import com.riffbuddy.domain.SessionTag
import com.riffbuddy.domain.Song
import com.riffbuddy.domain.SongPart
import com.riffbuddy.domain.Tagging
import com.riffbuddy.domain.format
import com.riffbuddy.domain.parseDateTime
import com.riffbuddy.ui.tactile.TextButton
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/**
 * What the Log can do to a session: edit, delete or add a past one. Null
 * [save] target means a new one.
 */
class SessionEdits(
    /** [tags] null means leave the session's tags as they are. */
    val save: (existing: Session?, startedAt: Instant, seconds: Long, rating: Int?, notes: String, tags: List<SessionTag>?) -> Unit,
    val delete: (Session) -> Unit,
)

/**
 * One dialog for both editing and adding. The start is typed, not picked:
 * a date picker and a time picker are two more dialogs on top of this one,
 * and a correction is usually "that was yesterday", a few characters' change.
 */
@Composable
fun SessionEditorDialog(
    session: Session?,
    zone: TimeZone,
    onDismiss: () -> Unit,
    onSave: (Instant, Long, Int?, String, List<SessionTag>?) -> Unit,
    onDelete: (() -> Unit)?,
    tagSongs: List<Song> = emptyList(),
    tagParts: Map<Long, List<SongPart>> = emptyMap(),
) {
    val initialStart = session?.startedAt ?: Clock.System.now() - 30.minutes
    val initialStartText = initialStart.format(INPUT, zone)
    val initialMinutesText = ((session?.durationSeconds() ?: 1800L) / 60).toString()
    var start by remember { mutableStateOf(initialStartText) }
    var minutes by remember { mutableStateOf(initialMinutesText) }
    var rating by remember { mutableStateOf(session?.rating) }
    var notes by remember { mutableStateOf(session?.notes ?: "") }
    var confirmDelete by remember { mutableStateOf(false) }
    /** Null until the picker is touched, so an untouched session keeps its tags exactly. */
    var tags by remember { mutableStateOf<List<SessionTag>?>(null) }
    val initialTags = remember(session) { session?.let(Tagging::of).orEmpty() }

    // A field left as it was keeps the exact original, not its rounding: the
    // form shows whole minutes, and fixing a note on a 43-second session must
    // neither refuse to save ("0 minutes") nor shorten it to a whole minute.
    val parsedStart = if (session != null && start == initialStartText) session.startedAt
    else parseDateTime(start)?.toInstant(zone)
    val parsedSeconds = if (session != null && minutes == initialMinutesText) session.durationSeconds()
    else minutes.trim().toLongOrNull()?.takeIf { it in 1..MAX_MINUTES }?.times(60)
    // A session cannot have started in the future; the stats would count it
    // on a day that has not happened.
    val startOk = parsedStart != null && parsedStart <= Clock.System.now()

    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this session?") },
            text = { Text("It comes out of the streak and the totals. This cannot be undone.") },
            confirmButton = { TextButton(onClick = onDelete) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep it") } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (session == null) "Log a past session" else "Edit session") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = start,
                    onValueChange = { start = it },
                    label = { Text("Started (yyyy-mm-dd hh:mm)") },
                    isError = !startOk,
                    supportingText = if (!startOk) {
                        { Text(if (parsedStart == null) "Use the form 2026-09-21 18:30" else "That is in the future") }
                    } else null,
                    singleLine = true,
                )
                OutlinedTextField(
                    value = minutes,
                    onValueChange = { minutes = it.filter(Char::isDigit).take(3) },
                    label = { Text("Minutes") },
                    isError = parsedSeconds == null,
                    supportingText = if (parsedSeconds == null) {
                        { Text("1 to $MAX_MINUTES") }
                    } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                Text("Rating", style = MaterialTheme.typography.labelLarge)
                // 48 dp each and touching, so five fit in a phone's dialog.
                Row {
                    (1..5).forEach { star ->
                        TextButton(
                            onClick = { rating = if (rating == star) null else star },
                            modifier = Modifier.defaultMinSize(minWidth = 48.dp),
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            Text(
                                "$star",
                                color = if (rating != null && star <= rating!!) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (rating == star) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
                OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("Notes") })
                if (tagSongs.isNotEmpty()) {
                    Text("What did you work on? (optional)", style = MaterialTheme.typography.labelLarge)
                    SessionTagPicker(
                        candidates = tagSongs,
                        parts = tagParts,
                        totalMinutes = ((parsedSeconds ?: 0L) / 60).toInt(),
                        initial = initialTags,
                        onChange = { if (it != initialTags || tags != null) tags = it },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(parsedStart!!, parsedSeconds!!, rating, notes.trim(), tags) },
                enabled = startOk && parsedSeconds != null,
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = { confirmDelete = true }) { Text("Delete") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** Twelve hours: longer than any real session, short enough to catch a typo. */
private const val MAX_MINUTES = 720L

private const val INPUT = "yyyy-MM-dd HH:mm"
