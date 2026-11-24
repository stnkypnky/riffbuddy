package com.riffbuddy.ui.tone

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.riffbuddy.ai.ControlSetting
import com.riffbuddy.domain.toFixed
import kotlin.math.roundToLong

import androidx.compose.runtime.mutableStateOf

import androidx.compose.runtime.setValue

import androidx.compose.runtime.getValue

import com.riffbuddy.ai.ToneCardText

import androidx.compose.ui.text.AnnotatedString

import androidx.compose.ui.platform.LocalClipboardManager

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.riffbuddy.ui.tactile.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.riffbuddy.ui.tactile.OutlinedButton
import androidx.compose.material3.Text
import com.riffbuddy.ui.tactile.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.riffbuddy.ai.ToneCard
import com.riffbuddy.domain.gear.EffectCategory

/** What the coach is doing right now, for the song page to render. */
sealed interface ToneCardState {
    data object Idle : ToneCardState
    data class Working(val message: String) : ToneCardState
    data class Ready(val card: ToneCard, val provider: String, val model: String, val elapsedMs: Long) : ToneCardState
    data class Problem(val message: String, val canRetry: Boolean) : ToneCardState
}

/**
 * The tone card.
 *
 * Ordered by how it is used: what it should sound like, then the amp
 * model, then the knobs, then effects, then how to play it. The honesty — what
 * was assumed, what the rig cannot do, how sure the coach is — sits at the
 * bottom where it can be read but does not crowd the numbers that matter.
 */
@Composable
fun ToneCardView(
    state: ToneCardState,
    onGenerate: () -> Unit,
    onRefine: (String) -> Unit,
    onRegenerate: () -> Unit,
    onSave: () -> Unit = {},
    onRate: (Boolean) -> Unit = {},
    saved: Boolean = false,
    /** Heads the copied text: song, part and rig. */
    heading: String = "Tone card",
) {
    // No scroll of its own: the Coach page scrolls as a whole, so the song
    // and part pickers above go up with the card instead of pinning it.
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (state) {
            ToneCardState.Idle -> Idle(onGenerate)
            is ToneCardState.Working -> Working(state.message)
            is ToneCardState.Problem -> Problem(state, onGenerate)
            is ToneCardState.Ready -> Ready(state, onRefine, onRegenerate, onSave, onRate, saved, heading)
        }
    }
}

@Composable
private fun Idle(onGenerate: () -> Unit) {
    com.riffbuddy.ui.Panel {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Set up my tone", style = MaterialTheme.typography.titleLarge)
            Text(
                // Not a count: it was the LT25's twenty models and twenty-six
                // effects, word for word, whatever amp was in the rig.
                "The coach sets up your own amp's knobs, and only ever uses the amp models and " +
                    "effects in its profile. It is a starting point to finish by ear.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onGenerate, modifier = Modifier.heightIn(min = 52.dp)) {
                Text("Dial me in")
            }
        }
    }
}

@Composable
private fun Working(message: String) {
    com.riffbuddy.ui.Panel {
        Row(
            Modifier.padding(20.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CircularProgressIndicator(Modifier.heightIn(min = 24.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Problem(state: ToneCardState.Problem, onRetry: () -> Unit) {
    com.riffbuddy.ui.Panel {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(state.message, style = MaterialTheme.typography.bodyMedium)
            Text(
                "Everything else still works — metronome, songbook, log.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.canRetry) {
                OutlinedButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Try again")
                }
            }
        }
    }
}

@Composable
private fun Ready(
    state: ToneCardState.Ready,
    onRefine: (String) -> Unit,
    onRegenerate: () -> Unit,
    onSave: () -> Unit,
    onRate: (Boolean) -> Unit,
    saved: Boolean,
    heading: String,
) {
    val card = state.card
    val clipboard = LocalClipboardManager.current
    var copied by remember(card) { mutableStateOf(false) }

    com.riffbuddy.ui.Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(card.toneDescription, style = MaterialTheme.typography.titleMedium)
            // Copy as text — for a message, or notes
            // taped to the amp.
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(ToneCardText.format(card, heading)))
                copied = true
            }) { Text(if (copied) "Copied" else "Copy as text") }

            card.pickup?.let {
                Label("PICKUP")
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    if (card.ampModel != null || card.ampControls.isNotEmpty()) {
        AmpSettings(card.ampModel, card.ampControls)
    }

    if (card.effects.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Label("EFFECTS")
            // Pedals in signal order — stomp, modulation, delay, reverb — as
            // the amp chains them.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                EffectCategory.entries.forEach { category ->
                    card.effects.firstOrNull { it.category == category }?.let { EffectPedal(it) }
                }
            }
        }
    }

    if (card.playingTips.isNotEmpty()) {
        com.riffbuddy.ui.Panel {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Label("PLAYING IT")
                card.playingTips.forEach {
                    Text("· $it", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }

    // Feedback chips. Wired to refinement so the card changes what was
    // complained about and leaves the rest alone.
    com.riffbuddy.ui.Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Label("NOT QUITE?")
            // Wrapped, not scrolled sideways: "Too thin" and "Too harsh" were
            // past the edge of the phone with nothing to say they were there.
            com.riffbuddy.ui.ChipFlow {
                listOf(
                    "Too bright", "Too muddy", "Too much gain",
                    "Not enough gain", "Too thin", "Too harsh",
                ).forEach { feedback ->
                    AssistChip(onClick = { onRefine(feedback) }, label = { Text(feedback) })
                }
            }
            TextButton(onClick = onRegenerate) { Text("Start over") }

            // Saving keeps the card against the song, so a tone that worked is
            // still there next week. Without it a good card lives only as long
            // as the screen does, which is the shortest possible life for the
            // thing this whole app is built around.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onSave,
                    enabled = !saved,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(if (saved) "Saved to song" else "Save to song") }
                TextButton(onClick = { onRate(true) }) { Text("Close enough") }
                TextButton(onClick = { onRate(false) }) { Text("Not useful") }
            }
        }
    }

    /*
     * The honesty block. Last, deliberately: it must be readable without
     * crowding the numbers the card was opened for, and it must never be
     * omitted — an AI tone card that hides what it guessed is worse than one
     * that admits it.
     */
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                when (card.confidence) {
                    "high" -> "Fairly confident about this one."
                    "low" -> "Not confident — treat this as a rough direction."
                    else -> "Reasonably confident. A starting point, not the answer."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            card.assumptions.forEach {
                Text(
                    "Assumed: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (card.unavailable.isNotEmpty()) {
                Label("NOT ON YOUR AMP")
                card.unavailable.forEach {
                    Text(
                        "· $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                card.caveats.ifBlank { "A starting point — finish by ear at your own volume." },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${state.provider} · ${state.model} · ${state.elapsedMs} ms",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Label(text: String) = com.riffbuddy.ui.SectionHeader(text)

/**
 * The amp half of the card, drawn as the Gear tab draws the amp:
 * grille cloth, the amp model silkscreened on the panel, and the knobs —
 * here turned to the card's settings, with the number under each.
 */
@Composable
private fun AmpSettings(ampModel: String?, controls: List<ControlSetting>) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
    ) {
        com.riffbuddy.ui.gear.GrilleCloth(Modifier.fillMaxWidth().height(22.dp))
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Label("SET THESE")
            ampModel?.let {
                Text("AMP MODEL", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(it, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            }
            Faceplate(controls)
        }
    }
}

/**
 * One effect from the card, as a stompbox with its knobs set.
 *
 * The card carries each effect's settings (the schema asks for them), and a
 * line like "Delay: model — purpose" would leave the delay time and mix
 * unseen. So they're on the pedal, numbers beside every knob as on the amp.
 */
@Composable
private fun EffectPedal(effect: com.riffbuddy.ai.EffectSetting) {
    val shape = RoundedCornerShape(14.dp)
    val onBody = MaterialTheme.colorScheme.onPrimaryContainer
    Column(
        Modifier
            .widthIn(min = 148.dp, max = 220.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(12.dp)
            .semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(effect.category.label.uppercase(), style = MaterialTheme.typography.labelSmall, color = onBody)
        Text(effect.model, style = MaterialTheme.typography.titleSmall, color = onBody, textAlign = TextAlign.Center)
        if (effect.settings.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                effect.settings.forEach { c ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.semantics {
                            contentDescription = "${c.name}, ${fmt(c.value)} out of ${fmt(c.max)}"
                        },
                    ) {
                        KnobFace(KnobGeometry.angleDegrees(c.value, c.min, c.max), size = 30.dp)
                        Text(fmt(c.value), style = MaterialTheme.typography.labelLarge, color = onBody)
                        Text(c.name.uppercase(), style = MaterialTheme.typography.labelSmall, color = onBody,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 64.dp))
                    }
                }
            }
        }
        if (effect.purpose.isNotBlank()) {
            Text(effect.purpose, style = MaterialTheme.typography.bodySmall, color = onBody, textAlign = TextAlign.Center)
        }
        com.riffbuddy.ui.gear.Footswitch()
    }
}

private fun fmt(v: Double): String =
    if (v == v.roundToLong().toDouble()) v.roundToLong().toString() else v.toFixed(1)
