package com.riffbuddy.ui.songs

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.riffbuddy.domain.Mastery
import com.riffbuddy.domain.PartStage
import com.riffbuddy.domain.SongPart
import com.riffbuddy.ui.ConfirmDelete
import com.riffbuddy.ui.SparkBurst
import com.riffbuddy.ui.tactile.IconButton
import com.riffbuddy.ui.tactile.OutlinedButton
import com.riffbuddy.ui.tactile.TextButton
import com.riffbuddy.ui.theme.motionSpec

/**
 * A song's parts and how far along each is. Every change saves at
 * once, like ticking homework, rather than waiting for the song page's Save
 * button: a stage is something you record the moment it happens.
 */
@Composable
fun PartsPanel(
    parts: List<SongPart>,
    onAdd: (String) -> Unit,
    onStage: (SongPart, PartStage) -> Unit,
    onRename: (SongPart, String) -> Unit,
    onMove: (SongPart, Int) -> Unit,
    onDelete: (SongPart) -> Unit,
) {
    var justMastered by remember { mutableStateOf<Long?>(null) }
    var renaming by remember { mutableStateOf<SongPart?>(null) }
    var deleting by remember { mutableStateOf<SongPart?>(null) }
    var customOpen by remember { mutableStateOf(false) }
    var addOpen by remember { mutableStateOf(false) }

    fun stage(part: SongPart, to: PartStage) {
        if (to == PartStage.MASTERED && part.stage != PartStage.MASTERED) justMastered = part.id
        onStage(part, to)
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Mastery.progress(parts)?.let { p -> ProgressLine(p, parts) }

        if (parts.isEmpty()) {
            Text(
                "Break the song into parts to track each one: learning it, clean but slow, up to speed, mastered.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        parts.forEachIndexed { i, part ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Box {
                PartRow(
                    part = part,
                    first = i == 0,
                    last = i == parts.lastIndex,
                    onNext = { stage(part, part.stage.next()) },
                    onStage = { stage(part, it) },
                    onRename = { renaming = part },
                    onMove = { onMove(part, it) },
                    onDelete = { deleting = part },
                )
                // A glow and a burst on the row that just reached Mastered. The burst
                // isn't clipped, so it can fly well past a 52 dp row.
                if (justMastered == part.id && part.stage == PartStage.MASTERED) {
                    RowGlow(part.id to part.stageChangedAt, Modifier.matchParentSize())
                    SparkBurst(part.id to part.stageChangedAt, Modifier.matchParentSize(), count = 30, durationMs = 1100, reach = 2.6f)
                }
            }
        }

        // One button and an A–Z list, not a wall of chips: the list is bigger
        // than chips could hold, with "Custom" kept at the top.
        val names = parts.map { it.name }
        Box {
            OutlinedButton(
                onClick = { addOpen = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .semantics { contentDescription = "Add a part. Opens a list of ${Mastery.COMMON_PARTS.size} parts and a custom option" },
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add a part  ▾")
            }
            DropdownMenu(expanded = addOpen, onDismissRequest = { addOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Custom name…", color = MaterialTheme.colorScheme.primary) },
                    leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                    onClick = { addOpen = false; customOpen = true },
                )
                HorizontalDivider()
                Mastery.COMMON_PARTS.sortedBy { it.lowercase() }.forEach { name ->
                    val added = Mastery.uniqueName(name, names)
                    DropdownMenuItem(
                        // Say it when it'll be numbered, so "Verse 2" isn't a surprise.
                        text = { Text(if (added == name) name else "$name  (adds $added)") },
                        onClick = { addOpen = false; onAdd(added) },
                    )
                }
            }
        }
    }

    if (customOpen) NameDialog("Add a part", "", onDismiss = { customOpen = false }) {
        onAdd(Mastery.uniqueName(it, parts.map { p -> p.name })); customOpen = false
    }
    renaming?.let { part -> NameDialog("Rename", part.name, onDismiss = { renaming = null }) { onRename(part, it); renaming = null } }
    deleting?.let { part ->
        ConfirmDelete("\"${part.name}\"", "The part and its stage go. Time already logged on it stays with the song.",
            onConfirm = { onDelete(part) }, onDismiss = { deleting = null })
    }
}

/** An amber wash over a row that fades out: the part's small moment. */
@Composable
private fun RowGlow(trigger: Any, modifier: Modifier) {
    if (com.riffbuddy.ui.theme.LocalReducedMotion.current) return
    val fade = remember(trigger) { androidx.compose.animation.core.Animatable(1f) }
    androidx.compose.runtime.LaunchedEffect(fade) { fade.animateTo(0f, tween(1100)) }
    val amber = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        drawRoundRect(amber.copy(alpha = 0.28f * fade.value), cornerRadius = CornerRadius(10.dp.toPx()))
    }
}

/** "63% · 3 of 5 parts mastered", over a bar that fills to it. */
@Composable
private fun ProgressLine(progress: Float, parts: List<SongPart>) {
    val shown by animateFloatAsState(progress, motionSpec(600), label = "song-progress")
    val mastered = parts.count { it.stage == PartStage.MASTERED }
    val amber = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "${(progress * 100).toInt()}%  ·  $mastered of ${parts.size} ${if (parts.size == 1) "part" else "parts"} mastered",
            style = MaterialTheme.typography.titleSmall,
        )
        Canvas(Modifier.fillMaxWidth().height(8.dp).semantics { contentDescription = "${(progress * 100).toInt()} percent" }) {
            val r = CornerRadius(size.height / 2)
            drawRoundRect(track, cornerRadius = r)
            drawRoundRect(amber, size = Size(size.width * shown, size.height), cornerRadius = r)
        }
    }
}

@Composable
private fun PartRow(
    part: SongPart,
    first: Boolean,
    last: Boolean,
    onNext: () -> Unit,
    onStage: (PartStage) -> Unit,
    onRename: () -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val mastered = part.stage == PartStage.MASTERED
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(part.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        // The stage, and a tap moves it on. Pips and words both carry it.
        OutlinedButton(
            onClick = onNext,
            enabled = !mastered,
            modifier = Modifier.heightIn(min = 44.dp).semantics {
                contentDescription = "${part.name}: ${part.stage.label}." + if (mastered) "" else " Tap to move to ${part.stage.next().label}."
            },
        ) {
            Pips(part.stage)
            Spacer(Modifier.width(8.dp))
            if (mastered) {
                Icon(Icons.Filled.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(part.stage.label, color = if (mastered) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More for ${part.name}") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                PartStage.entries.forEach { s ->
                    DropdownMenuItem(
                        text = { Text((if (s == part.stage) "✓ " else "") + s.label) },
                        onClick = { menu = false; if (s != part.stage) onStage(s) },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                if (!first) DropdownMenuItem(text = { Text("Move up") }, onClick = { menu = false; onMove(-1) })
                if (!last) DropdownMenuItem(text = { Text("Move down") }, onClick = { menu = false; onMove(1) })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

/** Four pips for the four steps after "not started", lit as far as the part has come. */
@Composable
private fun Pips(stage: PartStage) {
    val lit = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
    Canvas(Modifier.size(width = 34.dp, height = 8.dp)) {
        val gap = 2.dp.toPx()
        val w = (size.width - gap * 3) / 4
        for (i in 0 until 4) {
            drawRoundRect(
                if (i < stage.ordinal) lit else dim,
                topLeft = Offset(i * (w + gap), 0f),
                size = Size(w, size.height),
                cornerRadius = CornerRadius(2.dp.toPx()),
            )
        }
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it.take(60) }, singleLine = true, label = { Text("Name — e.g. \"Second solo\"") }) },
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) onSave(text.trim()) }, enabled = text.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
