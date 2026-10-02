package com.riffbuddy.ui.songs

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import com.riffbuddy.ui.tactile.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.riffbuddy.ui.tactile.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.riffbuddy.ai.DifficultyEstimate
import com.riffbuddy.ai.HowToPlay
import com.riffbuddy.ai.SimilarSong

/** Which assist is showing. */
enum class Assist(val label: String) {
    SIMILAR("Songs like this"),
    DIFFICULTY("How hard is it?"),
    HOW_TO("How to play it"),
}

sealed interface AssistState {
    data object Idle : AssistState
    data object Working : AssistState
    data class Failed(val message: String) : AssistState
    data class Similar(val songs: List<SimilarSong>, val source: String) : AssistState
    data class Difficulty(val estimate: DifficultyEstimate, val source: String) : AssistState
    data class HowTo(val guide: HowToPlay, val source: String) : AssistState
}

/**
 * The three song-assist buttons.
 *
 * Every result carries the same warning, and it is not boilerplate: these are
 * guesses about music the coach has not heard, produced with the same fluency
 * whether it knows the song or not. The difficulty estimate additionally asks
 * whether it got it right, and that answer goes into the next one — an
 * estimate that never calibrates is a number that means nothing.
 */
@Composable
fun SongAssistsSection(
    state: AssistState,
    active: Assist?,
    onRun: (Assist) -> Unit,
    onAskAgain: (Assist) -> Unit,
    onClear: () -> Unit,
    onAddSong: (SimilarSong) -> Unit,
    onFindOnSongsterr: (SimilarSong) -> Unit,
    onDifficultyFeedback: (String) -> Unit,
) {
    com.riffbuddy.ui.Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            com.riffbuddy.ui.SectionHeader("ASK THE COACH")
            com.riffbuddy.ui.ChipFlow {
                Assist.entries.forEach { assist ->
                    FilterChip(
                        selected = active == assist,
                        onClick = { onRun(assist) },
                        label = { Text(assist.label) },
                        modifier = Modifier.heightIn(min = 40.dp),
                    )
                }
            }

            when (state) {
                AssistState.Idle -> Text(
                    "Estimates, not facts — useful for deciding what to learn next.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                AssistState.Working -> Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.heightIn(min = 20.dp))
                    Text("Thinking…", style = MaterialTheme.typography.bodyMedium)
                }

                is AssistState.Failed -> {
                    Text(
                        state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        active?.let {
                            TextButton(onClick = { onAskAgain(it) }) { Text("Try again") }
                        }
                        TextButton(onClick = onClear) { Text("Clear") }
                    }
                }

                is AssistState.Similar -> {
                    state.songs.forEach { suggestion ->
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "${suggestion.title}${if (suggestion.artist.isNotBlank()) " — ${suggestion.artist}" else ""}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                listOfNotNull(
                                    suggestion.reason.takeIf { it.isNotBlank() },
                                    suggestion.tuning.takeIf { it.isNotBlank() },
                                    suggestion.band,
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { onAddSong(suggestion) }) { Text("Add") }
                                TextButton(onClick = { onFindOnSongsterr(suggestion) }) { Text("Find tab") }
                            }
                        }
                    }
                    Answered(state.source, active, onAskAgain, onClear)
                }

                is AssistState.Difficulty -> {
                    val estimate = state.estimate
                    Text(
                        "${estimate.rating} out of 10",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    estimate.comparedToYou.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                    estimate.reasons.forEach {
                        Text(
                            "· ${it.factor}: ${it.detail}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (estimate.prerequisites.isNotEmpty()) {
                        Text("Worth having first:", style = MaterialTheme.typography.labelMedium)
                        estimate.prerequisites.forEach {
                            Text("· $it", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (estimate.approach.isNotEmpty()) {
                        Text("How to start:", style = MaterialTheme.typography.labelMedium)
                        estimate.approach.forEachIndexed { index, step ->
                            Text("${index + 1}. $step", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    // The calibration loop. Without it the rating is a number
                    // with no relationship to how hard the song actually was.
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onDifficultyFeedback("too_easy") }) { Text("Easier than that") }
                        TextButton(onClick = { onDifficultyFeedback("too_hard") }) { Text("Harder than that") }
                    }
                    Answered(state.source, active, onAskAgain, onClear)
                }

                is AssistState.HowTo -> {
                    Text(state.guide.overview, style = MaterialTheme.typography.bodyMedium)
                    state.guide.sections.forEach { section ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(section.name, style = MaterialTheme.typography.titleSmall)
                            Text(section.whatHappens, style = MaterialTheme.typography.bodyMedium)
                            section.watchOut.takeIf { it.isNotBlank() }?.let {
                                Text(
                                    "Watch out: $it",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                        }
                    }
                    if (state.guide.techniques.isNotEmpty()) {
                        Text(
                            "Techniques: ${state.guide.techniques.joinToString(", ")}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    state.guide.pitfalls.forEach {
                        Text(
                            "· $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Answered(state.source, active, onAskAgain, onClear)
                }
            }
        }
    }
}

/**
 * Where an answer came from, and the two ways out of it.
 *
 * "Ask again" exists because a cached answer is otherwise permanent. Asking the
 * same question returns the same row from `ai_cache` forever — which is the
 * point of the cache right up until the answer is wrong, and then it is
 * a trap. A small model produced a repetition loop for one song here and there
 * was no way to get past it; the loop is now caught before it is stored, but a
 * merely *bad* answer never will be, so the button stays.
 *
 * It costs a free-tier request, so it says so rather than looking free.
 */
@Composable
private fun Answered(
    source: String,
    active: Assist?,
    onAskAgain: (Assist) -> Unit,
    onClear: () -> Unit,
) {
    // Every result says it may be inaccurate, and to verify.
    Text(
        "$source · an estimate, and may be wrong — check anything that matters",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        active?.let {
            TextButton(onClick = { onAskAgain(it) }) { Text("Ask again") }
        }
        TextButton(onClick = onClear) { Text("Clear") }
    }
}
