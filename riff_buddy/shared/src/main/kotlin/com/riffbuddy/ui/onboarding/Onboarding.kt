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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (step == 0) "" else "Step $step of $last · ${STEPS[step]}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                TextButton(onClick = onFinish) { Text(if (step == 0) "Skip setup" else "Skip the rest") }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (step) {
                    0 -> Welcome()
                    3 -> Column(Modifier.fillMaxSize()) {
                        Text(
                            "Tone cards are set up for your exact guitar and amp. Tap + Add under Guitars, then " +
                                "under Amps: type the make and model and tap Fill in with the coach, and it drafts " +
                                "the knobs, amp models and effects for you to check. The first guitar and amp you " +
                                "add become your rig. Pedals are optional.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                        Box(Modifier.weight(1f)) { gear() }
                    }
                    1 -> AboutYou(player, onPlayer, Modifier.verticalScroll(rememberScrollState()).padding(20.dp))
                    2 -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("The coach (optional)", style = MaterialTheme.typography.headlineSmall)
                        // One line: the AI section below explains the providers itself.
                        Text(
                            "Optional. Skip it and everything else still works; the coach will say how to " +
                                "add a key when you first use it.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        ai()
                    }
                }
            }

            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
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
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(com.riffbuddy.platform.LocalPlatform.current.appIcon(), contentDescription = null, modifier = Modifier.size(160.dp))
        Text("Riff Buddy", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(12.dp))
        Text(
            "Your practice companion: a metronome, a tuner, tab, a log of what you practiced, and a coach " +
                "that sets up tones for your own guitar and amp.",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.widthIn(max = 520.dp),
        )
        Spacer(Modifier.size(8.dp))
        Text(
            "Three quick steps, each one skippable.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * "About you": level, styles, goals. Also shown in Me › Settings.
 * These go into every AI prompt, so a beginner gets beginner answers.
 */
@Composable
fun AboutYou(player: PlayerProfile, onChange: (PlayerProfile) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("About you", style = MaterialTheme.typography.headlineSmall)
        Text(
            "The coach uses these to pitch its answers at you.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("How long have you been playing?", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlayerProfile.Level.entries.forEach { l ->
                FilterChip(player.level == l, { onChange(player.copy(level = l)) }, { Text(l.label) }, Modifier.heightIn(min = 40.dp))
            }
        }
        Text("What do you play?", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlayerProfile.STYLE_CHOICES.forEach { s ->
                val on = s in player.styles
                FilterChip(on, { onChange(player.copy(styles = if (on) player.styles - s else player.styles + s)) },
                    { Text(s.replaceFirstChar { it.uppercase() }) }, Modifier.heightIn(min = 40.dp))
            }
        }
        Text("What are you working towards?", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        Column(Modifier.padding(16.dp)) {
            AboutYou(player, onChange)
            TextButton(onClick = onRunSetup) { Text("Run the first-time setup again") }
        }
    }
}
