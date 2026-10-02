package com.riffbuddy.ui.theory

import com.riffbuddy.ui.tactile.AssistChip
import androidx.compose.ui.Alignment

import androidx.compose.foundation.layout.Spacer

import androidx.compose.foundation.layout.width

import androidx.compose.foundation.layout.size

import com.riffbuddy.ui.theme.RiffColors

import androidx.compose.material.icons.outlined.StarBorder

import androidx.compose.material.icons.filled.Star

import androidx.compose.material.icons.Icons

import androidx.compose.material3.Icon

import com.riffbuddy.ui.tactile.IconButton

import com.riffbuddy.domain.music.ChordFavorites

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import com.riffbuddy.ui.tactile.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.riffbuddy.ui.tactile.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.riffbuddy.domain.music.ChordLibrary
import com.riffbuddy.domain.music.ChordQuality
import com.riffbuddy.domain.music.Fretboard
import com.riffbuddy.domain.music.Key
import com.riffbuddy.domain.music.Notes
import com.riffbuddy.domain.music.Scale
import com.riffbuddy.domain.music.Tuning
import com.riffbuddy.domain.music.Phrase
import com.riffbuddy.domain.music.Phrases
import com.riffbuddy.audio.PhrasePlayer
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import kotlinx.coroutines.flow.MutableStateFlow

private enum class TheoryTab(val label: String) {
    SCALES("Scales"), CHORDS("Chords"), KEYS("Keys")
}

private val ROOT_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
private val FLAT_ROOT_NAMES = listOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")

/** Which part of the neck the scale diagram brings forward. */
private sealed interface Overlay {
    data object None : Overlay
    data class Box(val index: Int) : Overlay
    data class Nps(val index: Int) : Overlay
    data class Caged(val index: Int) : Overlay
}

/**
 * The theory reference: scales, chords and keys.
 *
 * Every diagram is computed from the current tuning, so switching to Drop D
 * changes what is drawn rather than showing a picture of a different guitar.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TheoryScreen(
    tuning: Tuning,
    onPickTuning: (Tuning) -> Unit,
    onJam: ((Key) -> Unit)? = null,
    /** Built-ins and custom tunings: every diagram follows a 6-8 string tuning. */
    tunings: List<Tuning> = Tuning.BUILT_IN,
    /** Tapping a note plays it — a MIDI note number. */
    onPlayNote: (Int) -> Unit = {},
    /** Favorite voicings, as [ChordFavorites] keys. */
    favorites: Set<String> = emptySet(),
    onToggleFavorite: (String) -> Unit = {},
    /** Hear a position, a chord or a progression. Null hides the Play buttons. */
    phrasePlayer: PhrasePlayer? = null,
    a4Hz: Double = 440.0,
) {
    var tab by remember { mutableStateOf(TheoryTab.SCALES) }
    var root by remember { mutableStateOf(9) }          // A, where most people start
    var scale by remember { mutableStateOf(Scale.MINOR_PENTATONIC) }
    var quality by remember { mutableStateOf(ChordQuality.MAJOR) }
    // Fret numbers first: they read like tab, which is how most guitarists read music.
    var label by remember { mutableStateOf(DotLabel.FRET) }
    var overlay by remember { mutableStateOf<Overlay>(Overlay.None) }
    var flats by remember { mutableStateOf(false) }
    val rootNames = if (flats) FLAT_ROOT_NAMES else ROOT_NAMES
    var minorKey by remember { mutableStateOf(false) }

    // What's playing, and where the speaker has got to in it.
    var speed by remember { mutableStateOf(Phrases.Speed.SLOW) }
    val noFlow = remember { MutableStateFlow<PhrasePlayer.Playing?>(null) }
    val playing by (phrasePlayer?.playing ?: noFlow).collectAsState()
    var nowMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(playing) {
        nowMs = 0L
        val player = phrasePlayer ?: return@LaunchedEffect
        while (playing != null) withFrameMillis { nowMs = player.positionMs() }
    }
    // Read through derived state, so the screen recomposes when the note
    // changes rather than on every frame of the poll above.
    val sounding by remember {
        derivedStateOf {
            playing?.phrase?.soundingAt(nowMs)?.filter { it.string >= 0 }?.map { it.string to it.fret }?.toSet()
                .orEmpty()
        }
    }
    val soundingGroup by remember { derivedStateOf { playing?.phrase?.groupAt(nowMs) } }
    // A phrase belongs to what was on screen when Play was tapped. Change the
    // root, scale, tuning or page, and it stops rather than playing on under
    // a diagram it no longer matches.
    LaunchedEffect(tab, root, scale, overlay, tuning, quality, minorKey) { phrasePlayer?.stop() }
    DisposableEffect(Unit) { onDispose { phrasePlayer?.stop() } }

    /** Play [phrase] as [key], or stop it if [key] is already playing. */
    fun toggle(key: String, phrase: () -> Phrase) {
        val player = phrasePlayer ?: return
        if (playing?.key == key) player.stop() else player.play(key, phrase(), a4Hz)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Theory", style = MaterialTheme.typography.headlineMedium)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TheoryTab.entries.forEach { candidate ->
                FilterChip(
                    selected = tab == candidate,
                    onClick = { tab = candidate },
                    label = { Text(candidate.label) },
                    modifier = Modifier.heightIn(min = 40.dp),
                )
            }
        }

        // Every choice is one menu chip on one row. This was twelve
        // root chips, a scale list, up to eighteen position chips and three
        // label chips — eight rows on a phone before the neck came into view.
        com.riffbuddy.ui.ChipFlow {
            com.riffbuddy.ui.MenuChip(
                text = "Root: ${rootNames[root]}", options = (0..11).toList(), selected = root,
                label = { rootNames[it] }, onPick = { root = it }, description = "Root note",
            )
            when (tab) {
                TheoryTab.SCALES -> {
                    com.riffbuddy.ui.MenuChip(
                        text = scale.label, options = Scale.COMMON + Scale.MODES + Scale.EXOTIC, selected = scale,
                        label = { it.label }, onPick = { scale = it; overlay = Overlay.None }, description = "Scale",
                    )
                    com.riffbuddy.ui.MenuChip(
                        text = tuning.name, options = tunings, selected = tuning,
                        label = { it.name }, onPick = { onPickTuning(it); overlay = Overlay.None }, description = "Tuning",
                        active = tuning.name != Tuning.STANDARD.name,
                    )
                }
                TheoryTab.CHORDS -> {
                    com.riffbuddy.ui.MenuChip(
                        text = quality.label, options = ChordQuality.entries.toList(), selected = quality,
                        label = { it.label }, onPick = { quality = it }, description = "Chord type",
                    )
                    if (phrasePlayer != null) SpeedChip(speed) { speed = it; phrasePlayer.stop() }
                }
                TheoryTab.KEYS -> {
                    com.riffbuddy.ui.MenuChip(
                        text = if (minorKey) "Minor" else "Major", options = listOf(false, true), selected = minorKey,
                        label = { if (it) "Minor" else "Major" }, onPick = { minorKey = it }, description = "Major or minor",
                    )
                    if (phrasePlayer != null) SpeedChip(speed) { speed = it; phrasePlayer.stop() }
                }
            }
        }

        when (tab) {
            TheoryTab.SCALES -> {
                val boxes = Fretboard.pentatonicBoxes(tuning, root, scale)
                val nps = Fretboard.threeNotesPerString(tuning, root, scale)
                val caged = Fretboard.cagedRegions(tuning, root, scale)
                val position = when (val o = overlay) {
                    is Overlay.Box -> boxes.getOrNull(o.index)
                    is Overlay.Nps -> nps.getOrNull(o.index)
                    is Overlay.Caged -> caged.getOrNull(o.index)
                    Overlay.None -> null
                }

                @Composable
                fun Diagram() {
                    com.riffbuddy.ui.Panel {
                        Column(Modifier.padding(12.dp)) {
                            FretboardDiagram(
                                positions = Fretboard.positions(tuning, root, scale),
                                stringCount = tuning.stringCount,
                                openNotes = tuning.midi.map { Notes.name(it, flats).dropLast(1) },
                                label = label,
                                // The highlight is sized to the position's own
                                // notes, and every note outside it is dimmed, so
                                // the shape can't be misread off the band alone.
                                highlight = position?.frets,
                                cells = position?.cells,
                                preferFlats = flats,
                                onTapNote = { onPlayNote(it.midi) },
                                sounding = sounding,
                            )
                            Text(
                                "${rootNames[root]} ${scale.label} in ${tuning.name} · tap a note to hear it",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            // Positions are stepped through, not hunted for among
                            // eighteen chips: ◀ and ▶ walk up and down the neck,
                            // and the chip opens the whole list.
                            val options = buildList {
                                add(Overlay.None to "Whole neck")
                                boxes.forEachIndexed { i, p -> add(Overlay.Box(i) to "${p.name} · frets ${p.frets.first}–${p.frets.last}") }
                                nps.forEachIndexed { i, p -> add(Overlay.Nps(i) to "${p.name} · frets ${p.frets.first}–${p.frets.last}") }
                                caged.forEachIndexed { i, p -> add(Overlay.Caged(i) to "${p.name} · frets ${p.frets.first}–${p.frets.last}") }
                            }
                            val at = options.indexOfFirst { it.first == overlay }.coerceAtLeast(0)
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                                IconButton(onClick = { overlay = options[(at - 1).mod(options.size)].first }, modifier = Modifier.size(48.dp)) {
                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous position")
                                }
                                com.riffbuddy.ui.MenuChip(
                                    text = options[at].second, options = options, selected = options[at],
                                    label = { it.second }, onPick = { overlay = it.first }, description = "Position on the neck",
                                    active = overlay != Overlay.None,
                                )
                                IconButton(onClick = { overlay = options[(at + 1).mod(options.size)].first }, modifier = Modifier.size(48.dp)) {
                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next position")
                                }
                            }
                            if (phrasePlayer != null) {
                                // Whole neck has no one shape to play, so Play
                                // runs one octave up from the lowest root.
                                val run = position ?: Fretboard.oneOctave(tuning, root, scale)
                                val key = "scale:$root:${scale.name}:${tuning.name}:${run.name}:${speed.name}"
                                com.riffbuddy.ui.ChipFlow(Modifier.padding(top = 4.dp)) {
                                    PlayButton(
                                        playing = playing?.key == key,
                                        text = if (position == null) "Play one octave" else "Play ${run.name}",
                                        onClick = { toggle(key) { Phrases.scaleRun(tuning, run.ascending(tuning), speed) } },
                                    )
                                    SpeedChip(speed) { speed = it; phrasePlayer.stop() }
                                }
                            }
                            // What the dots say, and how notes are spelled: both
                            // about reading the neck, so they sit under it.
                            com.riffbuddy.ui.ChipFlow(Modifier.padding(top = 4.dp)) {
                                com.riffbuddy.ui.MenuChip(
                                    text = "Dots: ${label.label}", options = DotLabel.entries.toList(), selected = label,
                                    label = { it.label }, onPick = { label = it }, description = "What each dot shows",
                                )
                                // A root in sharps or flats. Spelling only —
                                // but Bb is what a chart in F says, and A# is not.
                                FilterChip(flats, { flats = !flats }, { Text(if (flats) "Flats ♭" else "Sharps ♯") },
                                    modifier = Modifier.heightIn(min = 40.dp))
                            }
                        }
                    }
                }

                // The neck straight under one row of choices, on every screen:
                // it's what the page is for.
                Diagram()

            }

            TheoryTab.CHORDS -> {

                val voicings = ChordLibrary.voicings(root, quality)
                val favoriteChords = ChordFavorites.resolve(favorites)
                if (favoriteChords.isNotEmpty()) {
                    Label("FAVORITES")
                    // One entry per starred *shape*, named. It used to be one per chord,
                    // so starring the open C and the barre C showed a single "C" and
                    // looked as if a star had been lost.
                    com.riffbuddy.ui.ChipFlow {
                        favoriteChords.forEach { (r, q, v) ->
                            AssistChip(
                                onClick = { root = r; quality = q },
                                label = { Text("★ " + ChordLibrary.chord(r, q).name(flats) + " · " + v.name) },
                                modifier = Modifier.heightIn(min = 40.dp),
                            )
                        }
                    }
                }
                com.riffbuddy.ui.Panel {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            ChordLibrary.chord(root, quality).name(flats),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        if (tuning.name != Tuning.STANDARD.name) {
                            // Say so rather than showing shapes that are
                            // wrong in this tuning.
                            Text(
                                "These shapes are for standard tuning. In ${tuning.name} the " +
                                    "fingerings differ — the Scales tab shows where the chord's " +
                                    "notes actually fall.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                        if (voicings.isEmpty()) {
                            Text(
                                "No curated shape for this one yet. The Scales tab can show its " +
                                    "notes as an arpeggio.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                voicings.forEach { v ->
                                    val key = ChordFavorites.key(root, quality, v)
                                    val starred = key in favorites
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        ChordDiagram(v)
                                        Row {
                                            if (phrasePlayer != null) {
                                                // The shapes are standard tuning's, so
                                                // they sound in standard whatever the
                                                // tuning picker says.
                                                val playKey = "chord:$key:${speed.name}"
                                                PlayIcon(playing?.key == playKey, v.name) {
                                                    toggle(playKey) { Phrases.chord(Tuning.STANDARD, v.frets, speed) }
                                                }
                                            }
                                            IconButton(onClick = { onToggleFavorite(key) }, modifier = Modifier.size(48.dp)) {
                                                Icon(
                                                    if (starred) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                                    contentDescription = if (starred) "Remove ${v.name} from favorites" else "Add ${v.name} to favorites",
                                                    tint = if (starred) RiffColors.lampLit else MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            TheoryTab.KEYS -> {
                val key = Key(root, minorKey)
                com.riffbuddy.ui.Panel {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(key.name(), style = MaterialTheme.typography.headlineSmall)
                        // Straight from the key to a backing track in it.
                        onJam?.let { jam ->
                            com.riffbuddy.ui.tactile.Button(onClick = { jam(key) }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text("Jam in this key")
                            }
                        }

                        Label("CHORDS IN THIS KEY")
                        val allKey = "key:${key.tonic}:${key.isMinor}:all:${speed.name}"
                        val playingAll = playing?.key == allKey
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            if (phrasePlayer != null) {
                                PlayIcon(playingAll, "the chords in ${key.name()}") {
                                    toggle(allKey) {
                                        Phrases.progression(key.chords.map { Phrases.fretsFor(it.root, it.quality) }, speed)
                                    }
                                }
                            }
                            key.chords.forEachIndexed { index, chord ->
                                TextButton(onClick = {
                                    root = chord.root
                                    quality = chord.quality
                                    tab = TheoryTab.CHORDS
                                }) {
                                    val now = playingAll && soundingGroup == index
                                    Text(
                                        "${chord.numeral}  ${chord.name()}",
                                        fontWeight = if (now) FontWeight.Bold else null,
                                        textDecoration = if (now) TextDecoration.Underline else null,
                                    )
                                }
                            }
                        }

                        Label("RELATIVE")
                        Text(
                            "${key.relative.name()} — same notes, different home.",
                            style = MaterialTheme.typography.bodyMedium,
                        )

                        Label("SOLO WITH")
                        Text(
                            key.soloingScales.joinToString(", ") { it.label },
                            style = MaterialTheme.typography.bodyMedium,
                        )

                        Label("PROGRESSIONS")
                        Key.progressionsFor(key).forEach { progression ->
                            val chords = progression.chordsIn(key)
                            val progKey = "key:${key.tonic}:${key.isMinor}:${progression.name}:${speed.name}"
                            val now = if (playing?.key == progKey) soundingGroup else null
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (phrasePlayer != null) {
                                    PlayIcon(now != null, progression.name) {
                                        toggle(progKey) {
                                            Phrases.progression(chords.map { Phrases.fretsFor(it.root, it.quality) }, speed)
                                        }
                                    }
                                }
                                // The chord being heard is bold and underlined,
                                // not just colored.
                                Text(
                                    buildAnnotatedString {
                                        append("${progression.name}: ")
                                        chords.forEachIndexed { i, c ->
                                            if (i > 0) append(" · ")
                                            if (i == now) withStyle(
                                                SpanStyle(
                                                    fontWeight = FontWeight.Bold,
                                                    textDecoration = TextDecoration.Underline,
                                                    color = MaterialTheme.colorScheme.primary,
                                                )
                                            ) { append(c.name()) } else append(c.name())
                                        }
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { root = key.transpose(-1).tonic }) { Text("−1 semitone") }
                            TextButton(onClick = { root = key.transpose(1).tonic }) { Text("+1 semitone") }
                        }
                    }
                }
            }
        }

    }
}

/** Play/Stop, with words: the scale run is long enough to want a clear Stop. */
@Composable
private fun PlayButton(playing: Boolean, text: String, onClick: () -> Unit) {
    com.riffbuddy.ui.tactile.FilledTonalButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) {
        Icon(
            if (playing) Icons.Filled.Stop else Icons.Filled.PlayArrow,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(if (playing) "Stop" else text)
    }
}

/** Play/Stop where there's only room for an icon; the label says what it plays. */
@Composable
private fun PlayIcon(playing: Boolean, what: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(
            if (playing) Icons.Filled.Stop else Icons.Filled.PlayArrow,
            contentDescription = if (playing) "Stop" else "Play $what",
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun SpeedChip(speed: Phrases.Speed, onPick: (Phrases.Speed) -> Unit) =
    com.riffbuddy.ui.MenuChip(
        text = speed.label,
        options = Phrases.Speed.entries,
        selected = speed,
        label = { "${it.label} · ${it.bpm} BPM" },
        onPick = onPick,
        description = "Playback speed",
        icon = Icons.Filled.Speed,
    )

@Composable
private fun Label(text: String) = com.riffbuddy.ui.SectionHeader(text)
