package com.riffbuddy.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.riffbuddy.ui.tactile.Button
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.riffbuddy.ui.HeroCard
import com.riffbuddy.ui.PageHeader
import com.riffbuddy.ui.Panel
import com.riffbuddy.ui.theme.Space
import com.riffbuddy.ui.theme.Widths
import com.riffbuddy.ui.tactile.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.riffbuddy.ui.tactile.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import com.riffbuddy.domain.PlayerProfile

/**
 * First-run setup: welcome, gear, about you, AI, Home.
 *
 * Every step can be skipped, and "Skip setup" on the first screen goes
 * straight to Home in one tap (the AC allows six). Nothing here asks for the
 * microphone: the tuner does that when it is first opened, where the reason
 * is obvious. Run again from Me › Settings.
 *
 * The gear and AI steps are the same screens as in Me, not copies of them:
 * whatever is set up here is simply there afterwards.
 */
@Composable
fun Onboarding(
    player: PlayerProfile,
    onPlayer: (PlayerProfile) -> Unit,
    gear: @Composable () -> Unit,
    ai: @Composable () -> Unit,
    onFinish: () -> Unit,
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val last = STEPS.size - 1
    // Back steps back through setup, and from the welcome screen skips it —
    // never out of the app from under a half-finished setup.
    val platform = com.riffbuddy.platform.LocalPlatform.current
    platform.BackHandler(enabled = true) { if (step > 0) step-- else onFinish() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.md, vertical = Space.sm), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(start = Space.sm), verticalArrangement = Arrangement.spacedBy(Space.xs + Space.xxs)) {
                    if (step > 0) {
                        Text(
                            "Step $step of $last · ${STEPS[step]}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        StepBar(step, last)
                    }
                }
                Spacer(Modifier.size(Space.md))
                TextButton(onClick = onFinish) { Text(if (step == 0) "Skip setup" else "Skip the rest") }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (step) {
                    0 -> Welcome()
                    3 -> Column(Modifier.fillMaxSize()) {
                        Panel(Modifier.fillMaxWidth().padding(horizontal = Space.screen), accent = true, contentPadding = Space.panel) {
                            Text(
                                "Tone cards are set up for your exact guitar and amp. Tap + Add under Guitars, then " +
                                    "under Amps: type the make and model and tap Fill in with the coach, and it drafts " +
                                    "the knobs, amp models and effects for you to check. The first guitar and amp you " +
                                    "add become your rig. Pedals are optional.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Box(Modifier.weight(1f)) { gear() }
                    }
                    1 -> Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
                        AboutYou(player, onPlayer, Modifier.widthIn(max = Widths.reading).fillMaxWidth().padding(Space.screen))
                    }
                    2 -> Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
                        Column(
                            Modifier.widthIn(max = Widths.reading).fillMaxWidth().padding(Space.screen),
                            verticalArrangement = Arrangement.spacedBy(Space.md),
                        ) {
                            // One line: the AI section below explains the providers itself.
                            PageHeader(
                                "The coach",
                                eyebrow = "Optional",
                                subtitle = "Skip it and everything else still works; the coach will say how to " +
                                    "add a key when you first use it.",
                            )
                            ai()
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth().padding(Space.lg), horizontalArrangement = Arrangement.spacedBy(Space.md), verticalAlignment = Alignment.CenterVertically) {
                if (step > 0) TextButton(onClick = { step-- }) { Text("Back") }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { if (step == last) onFinish() else step++ },
                    modifier = Modifier.heightIn(min = 52.dp),
                ) {
                    Text(
                        when (step) {
                            0 -> "Let's get started"
                            last -> "Start practicing"
                            else -> "Next"
                        }
                    )
                }
            }
        }
    }
}

// The coach's key comes before gear: with one, "Fill in with the coach"
// drafts a whole amp profile from its model name, and setup is the moment
// someone is adding their gear.
private val STEPS = listOf("Welcome", "About you", "The coach", "Your gear")

@Composable
private fun Welcome() {
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
        HeroCard(
            Modifier.widthIn(max = Widths.dialog + Space.xxxl).fillMaxWidth().padding(Space.screen),
            contentPadding = PaddingValues(horizontal = Space.xl, vertical = Space.xxl),
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Image(com.riffbuddy.platform.LocalPlatform.current.appIcon(), contentDescription = null, modifier = Modifier.size(128.dp))
                Spacer(Modifier.size(Space.lg))
                Text(
                    "WELCOME TO",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text("Riff Buddy", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.size(Space.md))
                Text(
                    "Your practice companion: a metronome, a tuner, tab, a log of what you practiced, and a coach " +
                        "that sets up tones for your own guitar and amp.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.size(Space.xl))
                // What's ahead, so "three steps" means something before the first one.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Space.sm, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(Space.sm),
                ) {
                    StepPreview(1, "About you", Icons.Filled.Person)
                    StepPreview(2, "The coach", Icons.Filled.AutoAwesome)
                    StepPreview(3, "Your gear", Icons.Filled.Speaker)
                }
                Spacer(Modifier.size(Space.md))
                Text(
                    "Three quick steps, each one skippable.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StepPreview(number: Int, label: String, icon: ImageVector) {
    Row(
        Modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
            .padding(start = Space.xs, end = Space.md, top = Space.xs, bottom = Space.xs)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(28.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.size(Space.sm))
        Text("$number. $label", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Setup's progress as segments, one per step; the words above it say the same. */
@Composable
private fun StepBar(step: Int, last: Int) {
    Row(Modifier.widthIn(max = 240.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
        (1..last).forEach { i ->
            Box(
                Modifier.weight(1f).height(4.dp).background(
                    if (i <= step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                    CircleShape,
                )
            )
        }
    }
}

/**
 * "About you": level, styles, goals. Also shown in Me › Settings.
 * These go into every AI prompt, so a beginner gets beginner answers.
 */
@Composable
fun AboutYou(player: PlayerProfile, onChange: (PlayerProfile) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.md)) {
        PageHeader("About you", subtitle = "The coach uses these to pitch its answers at you.")
        Text("How long have you been playing?", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            PlayerProfile.Level.entries.forEach { l ->
                FilterChip(player.level == l, { onChange(player.copy(level = l)) }, { Text(l.label) }, Modifier.heightIn(min = 40.dp))
            }
        }
        Text("What do you play?", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            PlayerProfile.STYLE_CHOICES.forEach { s ->
                val on = s in player.styles
                FilterChip(on, { onChange(player.copy(styles = if (on) player.styles - s else player.styles + s)) },
                    { Text(s.replaceFirstChar { it.uppercase() }) }, Modifier.heightIn(min = 40.dp))
            }
        }
        Text("What are you working towards?", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            PlayerProfile.GOAL_CHOICES.forEach { g ->
                val on = g in player.goals
                FilterChip(on, { onChange(player.copy(goals = if (on) player.goals - g else player.goals + g)) },
                    { Text(g.replaceFirstChar { it.uppercase() }) }, Modifier.heightIn(min = 40.dp))
            }
        }
    }
}

/** Me › Settings: the same "About you", and the way back into setup. */
@Composable
fun AboutYouCard(player: PlayerProfile, onChange: (PlayerProfile) -> Unit, onRunSetup: () -> Unit) {
    com.riffbuddy.ui.Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Space.panel)) {
            AboutYou(player, onChange)
            TextButton(onClick = onRunSetup) { Text("Run the first-time setup again") }
        }
    }
}
