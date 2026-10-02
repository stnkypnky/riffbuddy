package com.riffbuddy.ui.looper

import com.riffbuddy.ui.theme.tactileClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import com.riffbuddy.ui.tactile.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import com.riffbuddy.ui.tactile.TextButton
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.riffbuddy.data.AudioRepo
import com.riffbuddy.domain.AudioFile
import com.riffbuddy.domain.SavedLoop
import com.riffbuddy.domain.Song
import kotlinx.coroutines.launch

/**
 * The looper's library: recordings the player has pointed the app at.
 *
 * Adding a recording keeps a *reference* and asks the platform for lasting
 * read access to it. Nothing is copied, which is why
 * deleting here says the recording itself is untouched — it really is.
 */
@Composable
fun LooperLibraryScreen(
    header: @Composable () -> Unit,
    files: List<AudioFile>,
    songs: List<Song>,
    repo: AudioRepo,
    onOpen: (AudioFile) -> Unit,
    onChanged: () -> Unit,
) {
    val platform = com.riffbuddy.platform.LocalPlatform.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<AudioFile?>(null) }
    var linking by remember { mutableStateOf<AudioFile?>(null) }
    var deleting by remember { mutableStateOf<AudioFile?>(null) }

    // A lasting grant: the looper refers to the recording rather than copying it.
    val picker = platform.rememberOpenFile(listOf("audio/*"), keepAccess = true) { picked ->
        if (picked == null) return@rememberOpenFile
        scope.launch {
            val name = picked.name ?: "Recording"
            val id = repo.add(picked.ref, name, picked.size ?: 0)
            message = "Added \"$name\"."
            onChanged()
            repo.byId(id)?.let(onOpen)
        }
    }

    /**
     * The bundled recordings are copied out of the APK into app storage first:
     * the decoder reads from a URI, and an asset inside the APK has none it can
     * open. Re-adding finds the same file path, so it lands on the same entry.
     */
    val addSamples: () -> Unit = {
        scope.launch {
            val added = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                SAMPLE_RECORDINGS.map { (asset, name) ->
                    val dir = java.io.File(platform.filesDir, "samples").apply { mkdirs() }
                    val target = java.io.File(dir, asset)
                    if (!target.exists()) {
                        platform.openAsset("samples/$asset").use { input ->
                            target.outputStream().use { input.copyTo(it) }
                        }
                    }
                    val id = repo.add(platform.refOf(target), asset, target.length())
                    repo.rename(id, name)
                }
            }
            message = "Added the ${added.size} sample recordings."
            onChanged()
        }
    }

    Column(Modifier.fillMaxSize()) {
        header()
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = { picker() }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Add a recording")
            }
            message?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { message = null })
            }
            if (files.isEmpty()) {
                com.riffbuddy.ui.Panel {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Slow a song down without changing its pitch", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Add an mp3, m4a, wav or ogg from your device. Mark the part you're " +
                                "learning, loop it, and bring the speed up as it cleans up. The file " +
                                "stays where it is — Riff Buddy only remembers where to find it.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "No recordings on this device? Try the two samples: an A440 test tone " +
                                "to check the pitch holds when slowed, and a short guitar riff to loop.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        com.riffbuddy.ui.tactile.OutlinedButton(
                            onClick = addSamples,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text("Add the sample recordings") }
                    }
                }
            }
            files.forEach { file ->
                val song = songs.firstOrNull { it.id == file.songId }?.title
                com.riffbuddy.ui.Panel {
                    Column(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.fillMaxWidth().tactileClickable(onClickLabel = "Open ${file.name}") { onOpen(file) }
                                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
                        ) {
                            Text(file.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                listOfNotNull(
                                    file.durationMs.takeIf { it > 0 }?.let { SavedLoop.clock(it).substringBeforeLast('.') },
                                    file.bpm?.let { "$it BPM" },
                                    file.loopStartMs?.let { "loop saved" },
                                ).joinToString(" · ").ifBlank { file.originalName },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                song?.let { "Linked to $it" } ?: "Not linked to a song",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (song != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(Modifier.padding(horizontal = 8.dp)) {
                            TextButton(onClick = { onOpen(file) }) { Text("Open") }
                            TextButton(onClick = { linking = file }) { Text(if (song == null) "Link to song" else "Change song") }
                            TextButton(onClick = { renaming = file }) { Text("Rename") }
                            TextButton(onClick = { deleting = file }) { Text("Remove") }
                        }
                    }
                }
            }
            if (files.isNotEmpty()) {
                TextButton(onClick = addSamples) { Text("Add the sample recordings") }
            }
        }
    }

    renaming?.let { file ->
        var text by remember(file.id) { mutableStateOf(file.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, label = { Text("Name") }) },
            confirmButton = {
                TextButton(onClick = { renaming = null; scope.launch { repo.rename(file.id, text); onChanged() } },
                    enabled = text.isNotBlank()) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }

    linking?.let { file ->
        var choice by remember(file.id) { mutableStateOf(file.songId) }
        AlertDialog(
            onDismissRequest = { linking = null },
            title = { Text("Link to a song") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    (listOf<Pair<Long?, String>>(null to "No song") + songs.sortedBy { it.title.lowercase() }.map { it.id to it.title })
                        .forEach { (id, label) ->
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .selectable(selected = choice == id, role = Role.RadioButton) { choice = id },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = choice == id, onClick = null)
                                Text(label, Modifier.padding(start = 8.dp))
                            }
                        }
                }
            },
            confirmButton = {
                TextButton(onClick = { linking = null; scope.launch { repo.link(file.id, choice); onChanged() } }) { Text("Link") }
            },
            dismissButton = { TextButton(onClick = { linking = null }) { Text("Cancel") } },
        )
    }

    deleting?.let { file ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Remove \"${file.name}\"?") },
            text = {
                Text("Riff Buddy forgets this recording and its saved loops. The recording itself stays on your device, untouched.")
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch {
                        repo.delete(file.id)
                        platform.releaseAccess(file.uri)
                        onChanged()
                    }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep") } },
        )
    }
}

/** Asset name and the name shown in the library. See samples/README.md. */
private val SAMPLE_RECORDINGS = listOf(
    "a440-test-tone.wav" to "A440 test tone",
    "pentatonic-riff.wav" to "Pentatonic riff (sample)",
)

