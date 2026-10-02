package com.riffbuddy.ui.plan

import com.riffbuddy.domain.HelpTopic
import com.riffbuddy.ui.help.HelpButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import com.riffbuddy.ui.tactile.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import com.riffbuddy.ui.tactile.FilterChip
import androidx.compose.material3.Icon
import com.riffbuddy.ui.tactile.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import com.riffbuddy.ui.tactile.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.riffbuddy.domain.Homework
import com.riffbuddy.domain.Lesson
import com.riffbuddy.domain.Song
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * The lesson log: what the teacher covered, and the homework that
 * came out of it. Homework carries over until it is ticked off — it is not
 * tied to the lesson's date.
 *
 * Voice entry is the keyboard's own dictation button: every
 * field here is an ordinary text field, so it works wherever the keyboard does.
 */
@Composable
fun LessonsScreen(
    lessons: List<Lesson>,
    homework: List<Homework>,
    songs: List<Song>,
    onBack: () -> Unit,
    onSaveLesson: (Lesson, List<Homework>) -> Unit,
    onDeleteLesson: (Lesson) -> Unit,
    onToggleDone: (Homework, Boolean) -> Unit,
    onDeleteHomework: (Homework) -> Unit,
) {
    var editing by remember { mutableStateOf<Lesson?>(null) }
    var deleting by remember { mutableStateOf<Lesson?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Home")
            }
            Text("Lessons", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Button(onClick = { editing = Lesson(date = Instant.now()) }) { Text("Log a lesson") }
            HelpButton(HelpTopic.PLAN)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val open = homework.filter { it.open }
            if (open.isNotEmpty()) {
                com.riffbuddy.ui.SectionHeader("OPEN HOMEWORK")
                open.forEach { HomeworkLine(it, songs, onToggleDone) }
            }
            if (lessons.isEmpty()) {
                Text(
                    "No lessons logged. After a lesson, jot down what was covered and the homework — " +
                        "it shows on Home until it's done.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            lessons.forEach { lesson ->
                val items = homework.filter { it.lessonId == lesson.id }
                com.riffbuddy.ui.Panel {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            DATE.format(lesson.date.atZone(ZoneId.systemDefault())) +
                                (lesson.teacher.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        if (lesson.notes.isNotBlank()) Text(lesson.notes, style = MaterialTheme.typography.bodyMedium)
                        items.forEach { HomeworkLine(it, songs, onToggleDone) }
                        Row {
                            TextButton(onClick = { editing = lesson }) { Text("Edit / add homework") }
                            TextButton(onClick = { deleting = lesson }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }

    editing?.let { lesson ->
        LessonDialog(lesson, songs, onDismiss = { editing = null }) { saved, newItems ->
            editing = null
            onSaveLesson(saved, newItems)
        }
    }
    deleting?.let { lesson ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this lesson?") },
            text = { Text("The lesson's notes go. Its homework stays — open items still show on Home until done.") },
            confirmButton = { TextButton(onClick = { deleting = null; onDeleteLesson(lesson) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep") } },
        )
    }
}

private class Draft(val text: String, val songId: Long?, val dueInDays: Int?)

@Composable
private fun LessonDialog(lesson: Lesson, songs: List<Song>, onDismiss: () -> Unit, onSave: (Lesson, List<Homework>) -> Unit) {
    var teacher by remember { mutableStateOf(lesson.teacher) }
    var notes by remember { mutableStateOf(lesson.notes) }
    val drafts = remember { mutableStateListOf<Draft>() }
    var text by remember { mutableStateOf("") }
    var songId by remember { mutableStateOf<Long?>(null) }
    var due by remember { mutableStateOf<Int?>(7) }
    var pickingSong by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (lesson.id == 0L) "Log a lesson" else "Edit lesson") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = teacher, onValueChange = { teacher = it }, singleLine = true, label = { Text("Teacher (optional)") })
                OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("What was covered") },
                    modifier = Modifier.heightIn(min = 96.dp))
                Text("Homework", style = MaterialTheme.typography.labelLarge)
                drafts.forEach { d -> Text("• ${d.text}", style = MaterialTheme.typography.bodySmall) }
                OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("A homework item") })
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf<Pair<Int?, String>>(null to "No date", 3 to "3 days", 7 to "A week").forEach { (days, label) ->
                        FilterChip(selected = due == days, onClick = { due = days }, label = { Text(label) })
                    }
                }
                TextButton(onClick = { pickingSong = true }) {
                    Text("Song: " + (songs.firstOrNull { it.id == songId }?.title ?: "none"))
                }
                TextButton(onClick = {
                    if (text.isNotBlank()) { drafts += Draft(text, songId, due); text = ""; songId = null }
                }, enabled = text.isNotBlank()) { Text("Add this item") }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val all = drafts.toMutableList()
                if (text.isNotBlank()) all += Draft(text, songId, due)
                val today = LocalDate.now()
                onSave(
                    lesson.copy(teacher = teacher, notes = notes),
                    all.map { d ->
                        Homework(text = d.text, songId = d.songId,
                            dueAt = d.dueInDays?.let { today.plusDays(it.toLong()).atStartOfDay(ZoneId.systemDefault()).plus(18, ChronoUnit.HOURS).toInstant() })
                    },
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (pickingSong) {
        AlertDialog(
            onDismissRequest = { pickingSong = false },
            title = { Text("Which song?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    (listOf<Pair<Long?, String>>(null to "No song") + songs.map { it.id to it.title }).forEach { (id, label) ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = songId == id, role = Role.RadioButton) {
                            songId = id; pickingSong = false
                        }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = songId == id, onClick = null)
                            Text(label, Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickingSong = false }) { Text("Done") } },
        )
    }
}

private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy")
