package com.riffbuddy.ui.help

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.riffbuddy.domain.Faq
import com.riffbuddy.domain.HelpCatalog
import com.riffbuddy.domain.HelpTopic
import com.riffbuddy.ui.Panel
import com.riffbuddy.ui.tactile.FilledTonalButton
import com.riffbuddy.ui.tactile.FilterChip
import com.riffbuddy.ui.tactile.IconButton
import com.riffbuddy.ui.theme.LocalReducedMotion
import com.riffbuddy.ui.theme.motionSpec
import com.riffbuddy.ui.theme.tactileClickable
import com.riffbuddy.ui.tuner.Tool
import kotlinx.coroutines.launch

/**
 * What the helper can do beyond answering.
 *
 * A CompositionLocal rather than a parameter: the "?" sits on sixteen
 * screens, and threading one callback through every one of them, and every
 * screen between them and the Shell, would be sixteen signatures changed for
 * a single button. The Shell provides this once; any [HelpButton] below it
 * reads it. Null means nothing is provided — a preview, say — and the Ask the
 * Coach button simply isn't shown.
 */
class HelpActions(val onAskCoach: ((String) -> Unit)?)

val LocalHelpActions = staticCompositionLocalOf { HelpActions(onAskCoach = null) }

/** The Tools tab's "?" follows the tool on screen. No `else`, so a new tool can't be forgotten. */
fun helpTopicFor(tool: Tool): HelpTopic = when (tool) {
    Tool.METRONOME -> HelpTopic.METRONOME
    Tool.TUNER -> HelpTopic.TUNER
    Tool.THEORY -> HelpTopic.THEORY
    Tool.TABS -> HelpTopic.TABS
    Tool.LOOPER -> HelpTopic.LOOPER
    Tool.RECORDER -> HelpTopic.RECORDER
    Tool.BACKING -> HelpTopic.BACKING
}

/**
 * The "?" in a screen's title row. Owns whether its sheet is open, so
 * a screen adds help with one line and no state of its own.
 */
@Composable
fun HelpButton(topic: HelpTopic, modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = modifier.size(48.dp)) {
        Icon(
            Icons.AutoMirrored.Outlined.HelpOutline,
            contentDescription = "Help with ${topic.title}",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (open) HelpSheet(startTopic = topic, onDismiss = { open = false })
}

/**
 * The helper itself: the FAQ for the screen it was opened from, a search over
 * every answer, and chips to jump to another screen's questions.
 *
 * A bottom sheet rather than a page, so it slides up *over* whatever was going
 * on — the metronome keeps clicking, the tuner keeps its tuning — and a swipe
 * down or Back returns exactly where things were. The slide, the drag and
 * the dimmed screen behind all come with Material's sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpSheet(startTopic: HelpTopic, onDismiss: () -> Unit) {
    // Straight to full height. Half-open, on a tablet in landscape, left room
    // for the title and the topics and not one question.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val askCoach = LocalHelpActions.current.onAskCoach
    var topic by rememberSaveable { mutableStateOf(startTopic) }
    var query by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }

    val searching = query.isNotBlank()
    val results: List<Pair<HelpTopic?, Faq>> =
        if (searching) HelpCatalog.search(query) else HelpCatalog.forTopic(topic).map { null to it }

    // Close the sheet with its own slide before acting, rather than letting
    // it vanish in the same frame as the Coach tab arrives.
    fun closeThen(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss(); action() }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(
            Modifier.fillMaxWidth().navigationBarsPadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    if (searching) "Help · Search" else "Help · ${topic.title}",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it; expanded = null },
                    placeholder = { Text("Search all answers") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searching) IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // One sideways row, not a wrapped wall: sixteen chips wrapped to
            // three lines and pushed this screen's questions out of sight.
            if (!searching) item {
                val row = rememberLazyListState(initialFirstVisibleItemIndex = startTopic.ordinal)
                LazyRow(state = row, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(HelpTopic.entries) { t ->
                        FilterChip(
                            selected = t == topic,
                            onClick = { topic = t; expanded = null },
                            label = { Text(t.title) },
                            modifier = Modifier.heightIn(min = 40.dp),
                        )
                    }
                }
            }
            if (searching && results.isEmpty()) item {
                Text(
                    "Nothing here matches \"${query.trim()}\". Try fewer words, or ask the coach.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(results, key = { (t, faq) -> "${t?.name}:${faq.question}" }) { (t, faq) ->
                FaqRow(
                    faq = faq,
                    topicLabel = t?.title,
                    expanded = expanded == faq.question,
                    onToggle = { expanded = if (expanded == faq.question) null else faq.question },
                )
            }
            if (askCoach != null) item {
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Didn't find it?",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilledTonalButton(
                        onClick = { closeThen { askCoach(query.trim()) } },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Ask the Coach")
                    }
                }
            }
        }
    }
}

/**
 * One question, tap to open its answer.
 *
 * The answer's space appears at once and only its look is animated — a fade
 * and a small rise — with the chevron turning over. All of it is
 * graphicsLayer, so an answer opening redraws one layer and never animates
 * the list's layout, which on a slow tablet is what would stutter.
 */
@Composable
private fun FaqRow(faq: Faq, topicLabel: String?, expanded: Boolean, onToggle: () -> Unit) {
    val reduced = LocalReducedMotion.current
    val turn by animateFloatAsState(if (expanded) 180f else 0f, motionSpec(180), label = "chevron")
    Panel(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .tactileClickable(onClickLabel = if (expanded) "Hide the answer" else "Show the answer", onClick = onToggle)
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                .heightIn(min = 48.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (topicLabel != null) {
                Text(topicLabel.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(faq.question, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Icon(
                    Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.graphicsLayer { rotationZ = turn },
                )
            }
            if (expanded) {
                // Made in composition, starting at 0, for the same reason as
                // screenEntrance: the answer's first frame is already faded
                // out, rather than flashing in fully before the fade begins.
                val shown = remember { Animatable(if (reduced) 1f else 0f) }
                LaunchedEffect(shown) { shown.animateTo(1f, tween(200, easing = FastOutSlowInEasing)) }
                AnswerText(
                    faq.answer,
                    Modifier.graphicsLayer {
                        alpha = shown.value
                        translationY = (1f - shown.value) * 6.dp.toPx()
                    },
                )
            }
        }
    }
}

/** "• " or "1. " at the start of a line: a list item, drawn with a hanging indent. */
private val LIST_MARKER = Regex("^(•|\\d+\\.)\\s+")

/**
 * An answer as written in Help.kt: a blank line starts a new paragraph, and a
 * line starting "• " or "1. " is a list item. List items get a hanging indent,
 * so a wrapped line lines up under its text rather than under the bullet —
 * the difference between a list you can scan and a block of text.
 */
@Composable
private fun AnswerText(answer: String, modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.bodyMedium
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        answer.split("\n").forEach { line ->
            val marker = LIST_MARKER.find(line)
            when {
                line.isBlank() -> Spacer(Modifier.size(4.dp))
                marker != null -> Row {
                    Text(marker.groupValues[1], style = style, color = color, modifier = Modifier.width(22.dp))
                    Text(line.substring(marker.range.last + 1), style = style, color = color, modifier = Modifier.weight(1f))
                }
                else -> Text(line, style = style, color = color)
            }
        }
    }
}
