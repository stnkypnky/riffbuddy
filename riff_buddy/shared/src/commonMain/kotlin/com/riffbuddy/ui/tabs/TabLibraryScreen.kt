package com.riffbuddy.ui.tabs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import com.riffbuddy.ui.EmptyState
import com.riffbuddy.ui.theme.Space
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
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
import com.riffbuddy.data.Imported
import com.riffbuddy.data.TabRepo
import com.riffbuddy.domain.Song
import com.riffbuddy.domain.TabFile
import com.riffbuddy.platform.Blocking
import com.riffbuddy.platform.readBytes
import com.riffbuddy.tabs.TabLibrary
import com.riffbuddy.ui.tactile.Button
import com.riffbuddy.ui.tactile.OutlinedButton
import com.riffbuddy.ui.tactile.TextButton
import com.riffbuddy.ui.theme.tactileClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The tab library: import, list, rename, link to a song, delete.
 *
 * Files come only from the player's own device, through the system picker — no
 * download, no search, no network. The picker is asked for any file type
 * because Guitar Pro has no registered MIME type: filtering by type would hide
 * exactly the files this exists for, so the extension is checked afterwards
 * and a wrong pick gets a sentence rather than silence.
 */
@Composable
fun TabLibraryScreen(
    header: @Composable () -> Unit,
    files: List<TabFile>,
    songs: List<Song>,
    repo: TabRepo,
    onOpen: (TabFile) -> Unit,
    onChanged: () -> Unit,
) {
    val platform = com.riffbuddy.platform.LocalPlatform.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) }  // text, isError
    var renaming by remember { mutableStateOf<TabFile?>(null) }
    var linking by remember { mutableStateOf<TabFile?>(null) }
    var deleting by remember { mutableStateOf<TabFile?>(null) }

    fun report(result: Result<Imported>) {
        message = result.fold(
            onSuccess = {
                when (it) {
                    is Imported.Added -> "Added \"${it.file.name}\"." to false
                    is Imported.AlreadyHad -> "\"${it.file.name}\" is already in your library." to false
                }
            },
            onFailure = { (it.message ?: "That file could not be added.") to true },
        )
        onChanged()
    }

    // No lasting grant: a tab file's bytes are copied into the library.
    val picker = platform.rememberOpenFile(listOf("*/*"), keepAccess = false) { file ->
        if (file == null) return@rememberOpenFile
        busy = true
        scope.launch {
            val picked = withContext(Dispatchers.Blocking) { readPicked(platform, file) }
            report(picked.fold({ (name, bytes) -> repo.import(bytes, name) }, { Result.failure(it) }))
            busy = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        header()
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.screen),
            verticalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { picker() },
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Import a tab file") }
                if (busy) CircularProgressIndicator(Modifier.heightIn(max = 28.dp))
            }

            message?.let { (text, isError) ->
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { message = null },
                )
            }

            val addSamples: () -> Unit = {
                busy = true
                scope.launch {
                    val results = SAMPLES.map { name ->
                        val bytes = withContext(Dispatchers.Blocking) {
                            platform.openAsset("samples/$name").readBytes()
                        }
                        repo.import(bytes, name)
                    }
                    message = sampleReport(results)
                    onChanged()
                    busy = false
                }
            }

            if (files.isEmpty()) EmptyLibrary(enabled = !busy, onAddSamples = addSamples)

            files.forEach { file ->
                TabRow(
                    file = file,
                    songTitle = songs.firstOrNull { it.id == file.songId }?.title,
                    onOpen = { onOpen(file) },
                    onRename = { renaming = file },
                    onLink = { linking = file },
                    onDelete = { deleting = file },
                )
            }

            // Also offered once the library has files, so a deleted or replaced
            // sample can come back without emptying everything else first. Ones
            // already present are recognized by their bytes and left alone.
            if (files.isNotEmpty()) {
                TextButton(onClick = addSamples, enabled = !busy) { Text("Add the sample files") }
            }
        }
    }

    renaming?.let { file ->
        RenameDialog(
            current = file.name,
            onDismiss = { renaming = null },
            onSave = { name ->
                renaming = null
                scope.launch { repo.rename(file.id, name); onChanged() }
            },
        )
    }

    linking?.let { file ->
        LinkDialog(
            songs = songs,
            current = file.songId,
            onDismiss = { linking = null },
            onPick = { songId ->
                linking = null
                scope.launch { repo.link(file.id, songId); onChanged() }
            },
        )
    }

    deleting?.let { file ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${file.name}\"?") },
            text = {
                Text(
                    "The file is removed from Riff Buddy's storage. The original on your " +
                        "device, wherever it came from, is not touched.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch { repo.delete(file); onChanged() }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep") } },
        )
    }
}

@Composable
private fun EmptyLibrary(enabled: Boolean, onAddSamples: () -> Unit) {
    com.riffbuddy.ui.Panel(Modifier.fillMaxWidth()) {
        EmptyState(
            icon = Icons.Filled.LibraryMusic,
            title = "No tab files yet",
            body = "Import Guitar Pro, MusicXML or alphaTex files from your device — anything " +
                "you've bought, downloaded or written yourself. They're copied in, so they " +
                "keep working offline and if the original moves.",
            action = {
                OutlinedButton(
                    onClick = onAddSamples,
                    enabled = enabled,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Add three sample files") }
            },
        )
    }
}

@Composable
private fun TabRow(
    file: TabFile,
    songTitle: String?,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onLink: () -> Unit,
    onDelete: () -> Unit,
) {
    com.riffbuddy.ui.Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().tactileClickable(onClickLabel = "Open ${file.name}", onClick = onOpen)
                    .padding(start = Space.panel, end = Space.panel, top = Space.md + Space.xxs, bottom = Space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(44.dp).background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.medium),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.LibraryMusic, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.size(Space.md))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
                Text(file.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${file.format} · ${file.contents}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    songTitle?.let { "Linked to $it" } ?: "Not linked to a song",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (songTitle != null) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                }
            }
            Row(Modifier.padding(start = Space.panel + 44.dp + Space.xs, end = Space.sm)) {
                TextButton(onClick = onOpen) { Text("Open") }
                TextButton(onClick = onLink) { Text(if (songTitle == null) "Link to song" else "Change song") }
                TextButton(onClick = onRename) { Text("Rename") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, label = { Text("Name") })
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun LinkDialog(songs: List<Song>, current: Long?, onDismiss: () -> Unit, onPick: (Long?) -> Unit) {
    var choice by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Link to a song") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val options = listOf<Pair<Long?, String>>(null to "No song") +
                    songs.sortedBy { it.title.lowercase() }.map { it.id to it.title }
                options.forEach { (id, label) ->
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
        confirmButton = { TextButton(onClick = { onPick(choice) }) { Text("Link") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Says what actually happened — "added three" was wrong when two were already there. */
private fun sampleReport(results: List<Result<Imported>>): Pair<String, Boolean> {
    val added = results.count { it.getOrNull() is Imported.Added }
    val had = results.count { it.getOrNull() is Imported.AlreadyHad }
    val failed = results.count { it.isFailure }
    val parts = listOfNotNull(
        added.takeIf { it > 0 }?.let { if (it == 1) "Added 1 sample" else "Added $it samples" },
        had.takeIf { it > 0 }?.let { "$it already in your library" },
        failed.takeIf { it > 0 }?.let { "$it could not be added" },
    )
    return parts.joinToString(", ", postfix = ".") to (failed > 0)
}

/** The three bundled files, one per supported format. See samples/README.md. */
private val SAMPLES = listOf("pentatonic-study.gp", "a-minor-blues.musicxml", "pentatonic-study.alphatex")

/**
 * Reads a picked file's name and bytes. The size is checked from the provider's
 * own metadata *before* reading, so a mis-picked video is refused without first
 * being pulled into memory on a 2 GB tablet.
 */
private fun readPicked(platform: com.riffbuddy.platform.Platform, file: com.riffbuddy.platform.PickedFile): Result<Pair<String, ByteArray>> = runCatching {
    val name = file.name ?: "untitled"
    val size = file.size ?: -1L
    if (size > TabLibrary.MAX_BYTES) {
        throw IllegalArgumentException(
            "$name is ${size / 1_000_000} MB. Tab files are almost always under 1 MB, so this is probably not one."
        )
    }
    val bytes = runCatching { platform.openInput(file.ref).readBytes() }
        .getOrElse { throw IllegalStateException("$name could not be opened.") }
    name to bytes
}
