package com.riffbuddy.ui.songs

import com.riffbuddy.domain.format
import androidx.compose.runtime.LaunchedEffect
import com.riffbuddy.ai.ToneCardDiff
import com.riffbuddy.domain.HelpTopic
import com.riffbuddy.domain.Mastery
import com.riffbuddy.domain.PartStage
import com.riffbuddy.domain.SongPart
import com.riffbuddy.ui.help.HelpButton

import androidx.compose.ui.semantics.Role

import androidx.compose.foundation.selection.selectable

import androidx.compose.material3.RadioButton

import com.riffbuddy.domain.music.Tuning

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.riffbuddy.ui.HeroCard
import com.riffbuddy.ui.PageHeader
import com.riffbuddy.ui.Panel
import com.riffbuddy.ui.SectionHeader
import com.riffbuddy.ui.theme.Space
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import com.riffbuddy.ui.tactile.Button
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.riffbuddy.domain.Session
import com.riffbuddy.domain.Song
import com.riffbuddy.domain.SongStatus
import com.riffbuddy.ai.SimilarSong
import com.riffbuddy.data.SavedToneCard
import com.riffbuddy.domain.Songsterr
import com.riffbuddy.domain.TabFile
import com.riffbuddy.domain.AudioFile
import com.riffbuddy.domain.Recording
import com.riffbuddy.data.SavedBacking

/**
 * A song's page — the hub for everything about one song: its details, the
 * Songsterr link, practice history, parts, tone cards and practice files.
 */
@Composable
fun SongPage(
    song: Song,
    sessions: List<Session>,
    onBack: () -> Unit,
    onSave: (Song) -> Unit,
    onDelete: () -> Unit,
    onOpenSongsterr: (String) -> Unit,
    onPractice: () -> Unit,
    onOpenMetronome: (bpm: Int, timeSignature: String?) -> Unit,
    toneCards: List<SavedToneCard> = emptyList(),
    onStarCard: (Long) -> Unit = {},
    assistState: AssistState = AssistState.Idle,
    activeAssist: Assist? = null,
    onRunAssist: (Assist) -> Unit = {},
    onAskAssistAgain: (Assist) -> Unit = {},
    onClearAssist: () -> Unit = {},
    onAddSuggestion: (SimilarSong) -> Unit = {},
    onFindSuggestion: (SimilarSong) -> Unit = {},
    onDifficultyFeedback: (String) -> Unit = {},
    tabFiles: List<TabFile> = emptyList(),
    onOpenTab: (TabFile) -> Unit = {},
    audioFiles: List<AudioFile> = emptyList(),
    onOpenAudio: (AudioFile) -> Unit = {},
    backings: List<SavedBacking> = emptyList(),
    onOpenBacking: (SavedBacking) -> Unit = {},
    /** Recorder takes linked to this song. */
    takes: List<Recording> = emptyList(),
    onPlayTake: (Recording) -> Unit = {},
    onLoopTake: (Recording) -> Unit = {},
    /** The song's tuning, and a way to open the tuner already in it. */
    tunings: List<Tuning> = Tuning.BUILT_IN,
    onOpenTuner: (tuningId: Long?, capo: Int) -> Unit = { _, _ -> },
    /** The song's parts and what can be done to them. */
    parts: List<SongPart> = emptyList(),
    onAddPart: (String) -> Unit = {},
    onPartStage: (SongPart, PartStage) -> Unit = { _, _ -> },
    onRenamePart: (SongPart, String) -> Unit = { _, _ -> },
    onMovePart: (SongPart, Int) -> Unit = { _, _ -> },
    onDeletePart: (SongPart) -> Unit = {},
) {
    /**
     * The edit buffer, re-seeded whenever the saved song actually changes.
     *
     * The key is [song] and not `song.id`, and that is the whole point. Without
     * a key this was seeded once and never again: after saving a Songsterr link
     * the reloaded song had the link, the buffer still did not, so the page
     * looked unsaved, and pressing "Save changes" wrote the stale buffer back
     * and deleted the link. [Song] is a data class, so an unrelated reload hands
     * back an equal value and does not disturb anything being typed — only a
     * real change to this row re-seeds.
     */
    var editing by remember(song) { mutableStateOf(song) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var pastingUrl by remember { mutableStateOf(false) }
    var renamingSong by remember { mutableStateOf(false) }
    val dirty = editing != song
    // When the last part reaches Mastered, ask whether the song is.
    var askMaster by remember { mutableStateOf(false) }
    var lastAllMastered by remember(song.id) { mutableStateOf(Mastery.allMastered(parts)) }
    LaunchedEffect(parts) {
        val now = Mastery.allMastered(parts)
        if (now && !lastAllMastered && song.status != SongStatus.MASTERED) askMaster = true
        lastAllMastered = now
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
    // Two columns of panels on a wide window: what you edit on the left,
    // what's linked to the song on the right, so nothing stretches to a
    // line too long to read.
    val wide = maxWidth >= 840.dp
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = if (wide) Space.screenWide else Space.screen, vertical = Space.lg),
        verticalArrangement = Arrangement.spacedBy(Space.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to songs")
            }
            Spacer(Modifier.size(Space.xs))
            PageHeader(
                title = song.title,
                eyebrow = song.artist.ifBlank { "Song" },
                modifier = Modifier.weight(1f),
                actions = {
                    IconButton(onClick = { renamingSong = true }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit song name and artist")
                    }
                    HelpButton(HelpTopic.SONG_PAGE)
                    IconButton(onClick = { confirmingDelete = true }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete song")
                    }
                },
            )
        }

        HeroCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(Space.xl)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                StatusPill(song.status)
                val facts = listOfNotNull(
                    song.key?.takeIf { it.isNotBlank() }?.let { "Key $it" },
                    tunings.firstOrNull { it.id == song.tuningId }?.name,
                    song.capo.takeIf { it > 0 }?.let { "Capo $it" },
                )
                if (facts.isNotEmpty()) {
                    Text(
                        facts.joinToString(" · "),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            song.targetBpm?.let { target ->
                Spacer(Modifier.size(Space.lg))
                TempoReadout(song.currentBpm, target)
            }
            Spacer(Modifier.size(Space.lg))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                Button(
                    onClick = onPractice,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.size(Space.sm))
                    Text("Practice")
                }
                OutlinedButton(
                    onClick = {
                        onOpenSongsterr(Songsterr.openUrl(editing.songsterrUrl, song.title, song.artist))
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                    Spacer(Modifier.size(Space.sm))
                    Text(if (editing.songsterrUrl.isNullOrBlank()) "Find tab" else "Open tab")
                }
            }
        }


        val assistsPanel: @Composable () -> Unit = {
            SongAssistsSection(
                state = assistState,
                active = activeAssist,
                onRun = onRunAssist,
                onAskAgain = onAskAssistAgain,
                onClear = onClearAssist,
                onAddSong = onAddSuggestion,
                onFindOnSongsterr = onFindSuggestion,
                onDifficultyFeedback = onDifficultyFeedback,
            )
        }
        val partsPanel: @Composable () -> Unit = {
            Section("Parts") {
                PartsPanel(
                    parts = parts,
                    onAdd = onAddPart,
                    onStage = onPartStage,
                    onRename = onRenamePart,
                    onMove = onMovePart,
                    onDelete = onDeletePart,
                )
            }
        }
        val statusPanel: @Composable () -> Unit = {
            Section("Status") {
                com.riffbuddy.ui.ChipFlow {
                    SongStatus.entries.forEach { s ->
                        FilterChip(
                            selected = editing.status == s,
                            onClick = { editing = editing.copy(status = s) },
                            label = { Text(s.label) },
                            modifier = Modifier.heightIn(min = 40.dp),
                        )
                    }
                }
            }
        }
        val tempoPanel: @Composable () -> Unit = {
            Section("Tempo and feel") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                    NumberField("Target BPM", editing.targetBpm, Modifier.weight(1f)) {
                        editing = editing.copy(targetBpm = it)
                    }
                    NumberField("Reached", editing.currentBpm, Modifier.weight(1f)) {
                        editing = editing.copy(currentBpm = it)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                    TextField("Key", editing.key ?: "", Modifier.weight(1f)) {
                        editing = editing.copy(key = it.ifBlank { null })
                    }
                    TextField("Time sig.", editing.timeSignature ?: "", Modifier.weight(1f)) {
                        editing = editing.copy(timeSignature = it.ifBlank { null })
                    }
                    NumberField("Capo", editing.capo.takeIf { it > 0 }, Modifier.weight(1f)) {
                        editing = editing.copy(capo = it ?: 0)
                    }
                }
                val songTuning = tunings.firstOrNull { it.id == editing.tuningId }
                var choosingTuning by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Tuning", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(songTuning?.name ?: "Not set", style = MaterialTheme.typography.bodyLarge)
                    }
                    TextButton(onClick = { choosingTuning = true }) { Text("Change") }
                    OutlinedButton(
                        // Saved first, as the metronome button is: this leaves the page.
                        onClick = { if (dirty) onSave(editing); onOpenTuner(editing.tuningId, editing.capo) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Tune to this") }
                }
                if (choosingTuning) {
                    TuningChooserDialog(
                        tunings = tunings,
                        selectedId = editing.tuningId,
                        onPick = { editing = editing.copy(tuningId = it); choosingTuning = false },
                        onDismiss = { choosingTuning = false },
                    )
                }
                editing.targetBpm?.let { bpm ->
                    OutlinedButton(
                        // Saved first: this leaves the page, and the target just
                        // typed is exactly the edit that would otherwise be lost.
                        onClick = { if (dirty) onSave(editing); onOpenMetronome(bpm, editing.timeSignature) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Open $bpm BPM in the metronome") }
                }
            }
        }
        val difficultyPanel: @Composable () -> Unit = {
            Section("How hard is it") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (1..5).forEach { level ->
                        FilterChip(
                            selected = editing.selfDifficulty == level,
                            onClick = {
                                editing = editing.copy(
                                    selfDifficulty = if (editing.selfDifficulty == level) null else level,
                                )
                            },
                            label = { Text("$level") },
                            modifier = Modifier.heightIn(min = 44.dp),
                        )
                    }
                }
            }
        }
        val tagsPanel: @Composable () -> Unit = {
            Section("Tags and notes") {
                TextField("Tags, comma separated", editing.tags, Modifier.fillMaxWidth()) {
                    editing = editing.copy(tags = it)
                }
                OutlinedTextField(
                    value = editing.notes,
                    onValueChange = { editing = editing.copy(notes = it) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                    label = { Text("Notes") },
                )
            }
        }
        val tabPanel: @Composable () -> Unit = {
            Section("Tab") {
                Text(
                    editing.songsterrUrl?.takeIf { it.isNotBlank() }
                        ?: "No saved link. \"Find tab\" searches Songsterr; paste the song's own " +
                        "link here once and it opens straight there next time.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { pastingUrl = true },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(if (editing.songsterrUrl.isNullOrBlank()) "Paste Songsterr link" else "Change link")
                }
            }
        }
        val filesPanel: @Composable () -> Unit = {
            // Files linked to this song, one tap from where it is practiced.
            Section("Practice files") {
                // A linked backing track appears on the song page.
                backings.forEach { track ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(track.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Backing track · ${track.spec.style.label} · ${track.spec.tempo} BPM",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { onOpenBacking(track) }) { Text("Jam") }
                    }
                }
                // Takes were missing here: linking one to a song saved the link,
                // but this list only knew the looper's files, tabs and backing tracks.
                // Starred first, then newest.
                takes.sortedWith(compareByDescending<Recording> { it.starred }.thenByDescending { it.createdAt }).forEach { take ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text((if (take.starred) "★ " else "") + take.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Take · ${com.riffbuddy.ui.recorder.clock(take.durationMs)} · " +
                                    take.createdAt.format(TAKE_DATE),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { onPlayTake(take) }) { Text("Play") }
                        TextButton(onClick = { onLoopTake(take) }) { Text("Loop it") }
                    }
                }
                audioFiles.forEach { audio ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(audio.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Recording" + (audio.loopStartMs?.let { " · loop saved" } ?: ""),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { onOpenAudio(audio) }) { Text("Loop it") }
                    }
                }
                if (tabFiles.isEmpty() && audioFiles.isEmpty() && backings.isEmpty() && takes.isEmpty()) {
                    Text(
                        "None linked. Add tab files in Tools › Tabs, audio in Tools › Looper and takes in " +
                            "Tools › Recorder, then link them to this song so they open from here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                tabFiles.forEach { file ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(file.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${file.format} · ${file.trackCount} tracks · ${file.barCount} bars",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { onOpenTab(file) }) { Text("Open") }
                    }
                }
            }
        }
        val tonePanel: @Composable () -> Unit = {
            if (toneCards.isNotEmpty()) {
                Section("Tone cards") {
                    // Grouped by part, newest first within each, because a rhythm
                    // tone and a solo tone are two answers rather than two attempts
                    // at one — and starring is scoped the same way.
                    toneCards.groupBy { it.part }.forEach { (part, cards) ->
                        Text(
                            part.replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.titleSmall,
                        )
                        // Oldest to newest to find each version's predecessor;
                        // shown as listed, newest first.
                        val byAge = cards.sortedBy { it.createdAt }
                        cards.forEach { card ->
                            // Versions are listed with timestamps and what changed.
                            val previous = byAge.getOrNull(byAge.indexOf(card) - 1)
                            val changes = previous?.let { ToneCardDiff.changes(it.contentJson, card.contentJson) }
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 44.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        summarize(card),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        "v${card.version} · ${card.provider.label} · " +
                                            card.createdAt.format(DATE),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    changes?.let { list ->
                                        Text(
                                            if (list.isEmpty()) "Same settings as v${previous.version}"
                                            else "Since v${previous.version}: " + list.joinToString(" · "),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.tertiary,
                                        )
                                    }
                                }
                                TextButton(onClick = { onStarCard(card.id) }) {
                                    Text(if (card.isStarred) "★ In use" else "Use this")
                                }
                            }
                        }
                    }
                }
            }
        }
        val historyPanel: @Composable () -> Unit = {
            Section("Practice history") {
                val mine = sessions.filter { s -> s.items.any { it.songId == song.id } }
                if (mine.isEmpty()) {
                    Text(
                        "Nothing logged for this song yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    mine.take(10).forEach { session ->
                        val seconds = session.items.filter { it.songId == song.id }.sumOf { it.seconds }
                        val bpm = session.items.firstOrNull { it.songId == song.id }?.bpmEnd
                        Text(
                            buildString {
                                append(session.startedAt.format(DATE))
                                append(" · ${seconds / 60} min")
                                if (bpm != null) append(" · reached $bpm BPM")
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }

        val left = listOf(assistsPanel, partsPanel, statusPanel, tempoPanel, difficultyPanel, tagsPanel)
        val right = listOf(tabPanel, filesPanel, tonePanel, historyPanel)
        if (wide) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.lg)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.lg)) { left.forEach { it() } }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.lg)) { right.forEach { it() } }
            }
        } else {
            (left + right).forEach { it() }
        }

        if (dirty) {
            Button(
                onClick = { onSave(editing) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) { Text("Save changes") }
        }
        Spacer(Modifier.size(Space.xl))
    }
    }

    if (askMaster) {
        AlertDialog(
            onDismissRequest = { askMaster = false },
            title = { Text("Every part is mastered") },
            text = { Text("Mark ${song.title} as Mastered?") },
            confirmButton = {
                TextButton(onClick = {
                    askMaster = false
                    onSave(editing.copy(status = SongStatus.MASTERED))
                }) { Text("Mastered!") }
            },
            dismissButton = { TextButton(onClick = { askMaster = false }) { Text("Not yet") } },
        )
    }
    if (renamingSong) {
        // Saves straight away, along with anything else changed on the page:
        // saving only the name would reload the song and drop those edits.
        EditNameDialog(
            title = editing.title, artist = editing.artist,
            onDismiss = { renamingSong = false },
            onSave = { t, a -> renamingSong = false; onSave(editing.copy(title = t, artist = a)) },
        )
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete \"${song.title}\"?") },
            text = {
                Text(
                    "The song goes. Practice time already logged against it stays in your " +
                        "history — deleting a song should not rewrite what you did.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmingDelete = false; onDelete() }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Keep") } },
        )
    }

    if (pastingUrl) {
        PasteLinkDialog(
            current = editing.songsterrUrl.orEmpty(),
            onDismiss = { pastingUrl = false },
            onSave = { url ->
                // Built from the buffer, not from the saved row: pasting a link
                // used to discard anything else edited but not yet saved.
                editing = editing.copy(songsterrUrl = url)
                onSave(editing)
                pastingUrl = false
            },
        )
    }
}

/** A pasted link is validated before it is stored, not when it is opened. */
@Composable
private fun EditNameDialog(title: String, artist: String, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var t by remember { mutableStateOf(title) }
    var a by remember { mutableStateOf(artist) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit song") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(t, { t = it.take(120) }, singleLine = true, label = { Text("Song name") }, isError = t.isBlank())
                OutlinedTextField(a, { a = it.take(120) }, singleLine = true, label = { Text("Artist") })
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(t.trim(), a.trim()) }, enabled = t.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PasteLinkDialog(current: String, onDismiss: () -> Unit, onSave: (String?) -> Unit) {
    var text by remember { mutableStateOf(current) }
    val check = if (text.isBlank()) null else Songsterr.validateSavedUrl(text)
    val problem = check?.exceptionOrNull()?.message

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Songsterr link") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Paste the song's Songsterr URL") },
                    isError = problem != null,
                )
                Text(
                    problem ?: "Opening it uses your own browser or the Songsterr app, so your " +
                        "subscription applies as usual.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (problem != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(text.trim().ifBlank { null }) },
                enabled = text.isBlank() || check?.isSuccess == true,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Section(title: String, content: @Composable ColumnScopeAlias.() -> Unit) {
    Panel(Modifier.fillMaxWidth(), contentPadding = Space.panel) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
            SectionHeader(title)
            content()
        }
    }
}

/** The song's tempo against its goal, big enough to read from a music stand. */
@Composable
private fun TempoReadout(current: Int?, target: Int) {
    val fraction = ((current ?: 0).toFloat() / target).coerceIn(0f, 1f)
    val fill = if (fraction >= 1f) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(
        Modifier.semantics(mergeDescendants = true) {
            contentDescription = if (current == null) "Goal $target beats per minute, nothing reached yet"
            else "Reached $current of $target beats per minute"
        },
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                current?.toString() ?: "—",
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.size(Space.sm))
            Text(
                "of $target BPM",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Space.sm),
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (fraction >= 1f) "At goal tempo" else "${(fraction * 100).toInt()}% of goal",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Space.sm),
            )
        }
        Canvas(Modifier.fillMaxWidth().height(8.dp)) {
            val r = CornerRadius(size.height / 2)
            drawRoundRect(track, cornerRadius = r)
            if (fraction > 0f) drawRoundRect(fill, size = Size(size.width * fraction, size.height), cornerRadius = r)
        }
    }
}

private typealias ColumnScopeAlias = androidx.compose.foundation.layout.ColumnScope

@Composable
private fun TextField(label: String, value: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = modifier,
    )
}

@Composable
private fun NumberField(label: String, value: Int?, modifier: Modifier = Modifier, onChange: (Int?) -> Unit) {
    OutlinedTextField(
        value = value?.toString() ?: "",
        onValueChange = { raw -> onChange(raw.filter { it.isDigit() }.take(3).toIntOrNull()) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

private const val DATE = "MMM d"

/**
 * One line describing a saved card, read straight from its stored JSON.
 *
 * Reading the raw JSON rather than a parsed card on purpose: this is a summary
 * for a list, and re-validating every card against the current rig just to draw
 * a row would make opening a song page depend on gear that may since have
 * changed.
 */
private fun summarize(card: SavedToneCard): String {
    val json = runCatching { com.riffbuddy.json.JSONObject(card.contentJson) }.getOrNull()
        ?: return "Saved tone"
    val model = json.optString("amp_model").takeIf { it.isNotBlank() }
    val description = json.optString("tone_description").takeIf { it.isNotBlank() }
    return listOfNotNull(model, description).joinToString(" — ").ifBlank { "Saved tone" }
}

/** Every tuning, grouped as the tuner groups them, plus "not set". */
@Composable
private fun TuningChooserDialog(
    tunings: List<Tuning>,
    selectedId: Long?,
    onPick: (Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tuning") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                TuningChoice("Not set", selectedId == null) { onPick(null) }
                tunings.groupBy { it.group }.forEach { (group, inGroup) ->
                    Text(
                        group.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                    inGroup.forEach { t ->
                        TuningChoice("${t.name} — ${t.noteNames().joinToString(" ")}", t.id == selectedId) { onPick(t.id) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun TuningChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.size(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

private const val TAKE_DATE = "MMM d, h:mm a"
