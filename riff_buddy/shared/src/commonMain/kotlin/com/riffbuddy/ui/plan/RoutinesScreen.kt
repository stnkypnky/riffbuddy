package com.riffbuddy.ui.plan

import com.riffbuddy.ui.IntStepper
import com.riffbuddy.domain.HelpTopic
import com.riffbuddy.ui.help.HelpButton
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import com.riffbuddy.ui.tactile.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import com.riffbuddy.ui.tactile.FilterChip
import androidx.compose.material3.Icon
import com.riffbuddy.ui.tactile.IconButton
import androidx.compose.material3.MaterialTheme
import com.riffbuddy.ui.tactile.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import com.riffbuddy.ui.ConfirmDelete
import com.riffbuddy.domain.Routine
import com.riffbuddy.domain.RoutineStep
import com.riffbuddy.domain.RoutineTemplates
import com.riffbuddy.domain.Song
import com.riffbuddy.domain.StepKind

/**
 * Routines: the player's own, one of them chosen for Today's plan, and the
 * three starter templates — which are copied in to be edited, never edited in
 * place.
 */
@Composable
fun RoutinesScreen(
    routines: List<Routine>,
    selectedId: Long?,
    songs: List<Song>,
    onBack: () -> Unit,
    onSelect: (Long?) -> Unit,
    onSave: (Routine) -> Unit,
    onDelete: (Routine) -> Unit,
) {
    var editing by remember { mutableStateOf<Routine?>(null) }
    var deleting by remember { mutableStateOf<Routine?>(null) }
    editing?.let { r ->
        RoutineEditor(r, songs, onCancel = { editing = null }) { saved -> editing = null; onSave(saved) }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Home")
            }
            Text("Routines", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Button(onClick = { editing = Routine(name = "My routine", steps = listOf(RoutineStep(StepKind.WARM_UP, "Warm up", 5))) }) {
                Text("New")
            }
            HelpButton(HelpTopic.PLAN)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (routines.isEmpty()) {
                Text("No routines of your own yet. Start from one of these and make it yours:",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            deleting?.let { r ->
                ConfirmDelete(
                    "\"${r.name}\"",
                    "Its steps go with it. Sessions it logged stay in the log.",
                    onConfirm = { onDelete(r) },
                    onDismiss = { deleting = null },
                )
            }
            routines.forEach { r ->
                val chosen = r.id == selectedId
                com.riffbuddy.ui.Panel {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(r.name + if (chosen) "  — today's" else "", style = MaterialTheme.typography.titleSmall,
                            color = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        Text("${r.steps.size} steps · ${r.minutes} min", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row {
                            if (!chosen) TextButton(onClick = { onSelect(r.id) }) { Text("Use today") }
                            else TextButton(onClick = { onSelect(null) }) { Text("Stop using") }
                            TextButton(onClick = { editing = r }) { Text("Edit") }
                            TextButton(onClick = { deleting = r }) { Text("Delete") }
                        }
                    }
                }
            }
            com.riffbuddy.ui.SectionHeader("STARTER TEMPLATES")
            RoutineTemplates.ALL.forEach { t ->
                com.riffbuddy.ui.Panel {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(t.name, style = MaterialTheme.typography.titleSmall)
                        t.steps.forEach {
                            Text("${it.minutes} min · ${it.title}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        // A copy, so the template stays as shipped.
                        TextButton(onClick = { onSave(t.copy(id = 0)) }) { Text("Add to my routines") }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoutineEditor(routine: Routine, songs: List<Song>, onCancel: () -> Unit, onSave: (Routine) -> Unit) {
    var name by remember { mutableStateOf(routine.name) }
    val steps = remember { mutableStateListOf<RoutineStep>().also { it.addAll(routine.steps) } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onCancel, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel editing")
            }
            Text("Edit routine", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Button(onClick = { onSave(routine.copy(name = name.ifBlank { "My routine" }, steps = steps.toList())) },
                enabled = steps.isNotEmpty(), modifier = Modifier.padding(end = 12.dp)) { Text("Save") }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") },
                modifier = Modifier.fillMaxWidth())
            Text("${steps.sumOf { it.minutes }} minutes", style = MaterialTheme.typography.labelLarge)
            steps.forEachIndexed { i, step ->
                com.riffbuddy.ui.Panel {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            StepKind.entries.forEach { k ->
                                FilterChip(selected = step.kind == k, onClick = { steps[i] = step.copy(kind = k) }, label = { Text(k.label) })
                            }
                        }
                        OutlinedTextField(value = step.title, onValueChange = { steps[i] = step.copy(title = it) },
                            label = { Text("What to do") }, modifier = Modifier.fillMaxWidth())
                        IntStepper(
                            label = "Length",
                            value = step.minutes,
                            range = STEP_MINUTES,
                            onValue = { steps[i] = step.copy(minutes = it) },
                            unit = "min",
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (i > 0) TextButton(onClick = { steps.add(i - 1, steps.removeAt(i)) }) { Text("Move up") }
                            TextButton(onClick = { steps.removeAt(i) }) { Text("Remove") }
                        }
                        if (step.kind == StepKind.SONG && songs.isNotEmpty()) {
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                songs.forEach { s ->
                                    FilterChip(selected = step.songId == s.id,
                                        onClick = { steps[i] = step.copy(link = if (step.songId == s.id) "" else "song:${s.id}") },
                                        label = { Text(s.title) })
                                }
                            }
                        }
                    }
                }
            }
            OutlinedButton(onClick = { steps += RoutineStep(StepKind.TECHNIQUE, "", 5) }) { Text("Add a step") }
        }
    }
}

/** One step of a routine: a minute at least, and two hours is a whole session. */
private val STEP_MINUTES = 1..120
