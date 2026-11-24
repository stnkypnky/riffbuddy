package com.riffbuddy.ui.tone

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.riffbuddy.data.Conversation
import com.riffbuddy.ui.theme.LocalReducedMotion
import com.riffbuddy.ui.theme.Radius
import com.riffbuddy.ui.theme.Space
import com.riffbuddy.ui.theme.tactileClickable
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

/** The coach's two halves. Ask comes first: it is what most visits are for. */
internal enum class CoachMode(val label: String, val icon: ImageVector) {
    ASK("Ask anything", Icons.AutoMirrored.Filled.Chat),
    TONE("Tone card", Icons.Filled.Tune),
}

/** Whether the coach can answer right now, and if not, why. */
internal enum class Readiness {
    READY, NO_KEY, OFFLINE,
    /** Only paid keys, none chosen yet: nothing is spent until the player picks one. */
    PAID_UNCHOSEN,
}

/**
 * The mode switch: two halves of one pill, the chosen one filled.
 *
 * Built by hand rather than from two chips so it reads as a single control
 * with one answer. The chosen half is also bold and announced as selected,
 * so it is not told apart by color alone.
 */
@Composable
internal fun ModeSwitch(selected: CoachMode, onSelect: (CoachMode) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val pill = RoundedCornerShape(Radius.pill)
    Row(
        modifier
            .clip(pill)
            .background(scheme.surfaceContainerHighest)
            .border(1.dp, scheme.outlineVariant.copy(alpha = 0.6f), pill)
            .padding(Space.xs)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        CoachMode.entries.forEach { mode ->
            val on = mode == selected
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(pill)
                    .background(if (on) scheme.primary else scheme.surfaceContainerHighest)
                    .tactileClickable(onClickLabel = "Switch to ${mode.label}", pressedScale = 0.96f) { onSelect(mode) }
                    .semantics { this.selected = on; role = Role.Tab },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    mode.icon,
                    contentDescription = null,
                    tint = if (on) scheme.onPrimary else scheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(Space.sm))
                Text(
                    mode.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                    color = if (on) scheme.onPrimary else scheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The welcome at the top of a new chat: the coach, a greeting, and what it
 * already knows, so the player can see the answers will be about their own
 * guitar and songs before they type anything.
 */
@Composable
internal fun WelcomeHero(greeting: String, knows: String?, answering: String?, readiness: Readiness, onSwitch: (() -> Unit)? = null) {
    com.riffbuddy.ui.HeroCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CoachAvatar(size = 60.dp)
            Spacer(Modifier.width(Space.lg))
            Column(Modifier.weight(1f)) {
                Text(
                    "YOUR COACH",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    greeting,
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Spacer(Modifier.height(Space.md))
        Text(
            knows ?: "Add your gear and songs, and my answers are about your guitar and the music you're learning.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Space.xs))
        Text(
            "Ask me about technique, tone, theory, or what to practice next.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (readiness == Readiness.READY && answering != null) {
            Spacer(Modifier.height(Space.md))
            ReadyLine(answering, onClick = onSwitch)
        }
    }
}

/** "Ready · answering with Groq · model", with a tick so it doesn't rest on color. */
@Composable
internal fun ReadyLine(answering: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier
            .clip(RoundedCornerShape(Radius.pill))
            .background(scheme.surfaceContainerHighest)
            .then(
                if (onClick != null) Modifier
                    .heightIn(min = 48.dp)
                    .tactileClickable(onClick = onClick)
                    .semantics(mergeDescendants = true) {
                        role = Role.Button
                        contentDescription = "Answering with $answering. Change who answers"
                    }
                else Modifier
            )
            .padding(horizontal = Space.md, vertical = Space.xs + Space.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(Space.sm))
        Text(
            "Ready · answering with $answering",
            style = MaterialTheme.typography.labelLarge,
            color = scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (onClick != null) {
            Spacer(Modifier.width(Space.sm))
            Icon(Icons.Filled.SwapHoriz, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Space.xs))
            Text("Change", style = MaterialTheme.typography.labelLarge, color = scheme.primary)
        }
    }
}

/**
 * Why the coach can't answer yet, shown before a question is wasted on it.
 * Nothing at all when it can.
 */
@Composable
internal fun ReadinessCard(readiness: Readiness, onOpenSettings: () -> Unit, offlineNote: String, onSwitch: (() -> Unit)? = null) {
    when (readiness) {
        Readiness.READY -> Unit
        Readiness.NO_KEY -> com.riffbuddy.ui.Panel(Modifier.fillMaxWidth(), accent = true, contentPadding = Space.panel) {
            Row(verticalAlignment = Alignment.Top) {
                IconDisc(Icons.Filled.VpnKey, 44.dp)
                Spacer(Modifier.width(Space.md))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Text("Unlock your coach", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "The coach answers with a free AI key — no card, no cost. Making one at Groq or " +
                            "Gemini takes about two minutes; paste it into Settings and you're ready.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(Space.md))
            com.riffbuddy.ui.tactile.Button(onClick = onOpenSettings, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Filled.VpnKey, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Space.sm))
                Text("Add a free key in Settings")
            }
        }
        Readiness.PAID_UNCHOSEN -> com.riffbuddy.ui.Panel(Modifier.fillMaxWidth(), accent = true, contentPadding = Space.panel) {
            Row(verticalAlignment = Alignment.Top) {
                IconDisc(Icons.Filled.VpnKey, 44.dp)
                Spacer(Modifier.width(Space.md))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Text("Choose who answers", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Your keys are for paid providers, so nothing is asked until you pick one. " +
                            "Its answers are billed on your account with that provider.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(Space.md))
            com.riffbuddy.ui.tactile.Button(onClick = onSwitch ?: onOpenSettings, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Filled.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Space.sm))
                Text(if (onSwitch != null) "Choose a provider" else "Choose one in Settings")
            }
        }
        Readiness.OFFLINE -> com.riffbuddy.ui.Panel(Modifier.fillMaxWidth(), contentPadding = Space.panel) {
            Row(verticalAlignment = Alignment.Top) {
                IconDisc(Icons.Filled.CloudOff, 44.dp)
                Spacer(Modifier.width(Space.md))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Text("You're offline", style = MaterialTheme.typography.titleMedium)
                    Text(offlineNote, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun IconDisc(icon: ImageVector, size: Dp) {
    Box(
        Modifier.size(size).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(size * 0.5f))
    }
}

private fun StarterKind.icon(): ImageVector = when (this) {
    StarterKind.TECHNIQUE -> Icons.Filled.Speed
    StarterKind.TONE -> Icons.Filled.GraphicEq
    StarterKind.THEORY -> Icons.Filled.School
    StarterKind.PRACTICE -> Icons.Filled.Schedule
}

/**
 * Cards in rows of equal height, as many across as fit: one on a very narrow
 * phone, two on most phones, three or four on a tablet or a computer.
 */
@Composable
private fun <T> CardGrid(items: List<T>, minCell: Dp, maxColumns: Int, cell: @Composable (T, Modifier) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = Space.md
        val columns = ((maxWidth + gap) / (minCell + gap)).toInt().coerceIn(1, maxColumns)
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            items.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { cell(it, Modifier.weight(1f).fillMaxHeight()) }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** A tappable card in the coach's own style: raised fill, hairline edge. */
@Composable
private fun CoachCard(modifier: Modifier, onClickLabel: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Radius.lg)
    Box(
        modifier
            .clip(shape)
            .background(scheme.surfaceContainer)
            .border(1.dp, scheme.outlineVariant.copy(alpha = 0.7f), shape)
            .tactileClickable(onClickLabel = onClickLabel, onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(Space.md),
    ) { content() }
}

/**
 * Suggested first questions as cards, by kind. A tap puts the question in
 * the box to read over and send — it never sends on its own, so a question
 * the player didn't quite mean never spends a free request.
 */
@Composable
internal fun StarterGrid(starters: List<Starter>, onPick: (String) -> Unit) {
    CardGrid(starters, minCell = 168.dp, maxColumns = 3) { starter, mod ->
        CoachCard(mod.heightIn(min = 112.dp), onClickLabel = "Put this question in the box", onClick = { onPick(starter.question) }) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(28.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(starter.kind.icon(), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(Space.sm))
                    Text(starter.kind.label.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                Text(starter.question, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/** Recent chats to pick up again, newest first. */
@Composable
internal fun ContinueChats(chats: List<Conversation>, now: Instant, zone: TimeZone, onOpen: (Conversation) -> Unit) {
    CardGrid(chats, minCell = 260.dp, maxColumns = 2) { chat, mod ->
        CoachCard(mod, onClickLabel = "Continue this chat", onClick = { onOpen(chat) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconDisc(Icons.Filled.Forum, 36.dp)
                Spacer(Modifier.width(Space.md))
                Column(Modifier.weight(1f)) {
                    Text(chat.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${CoachHome.whenAgo(chat.lastAt, now, zone)} · ${chat.messages} messages",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Every chat in a column beside the conversation, for a window wide enough
 * to spare it. The open one is filled, bold and announced as selected.
 */
@Composable
internal fun PastChatsColumn(
    chats: List<Conversation>,
    current: String?,
    now: Instant,
    zone: TimeZone,
    showNew: Boolean,
    newEnabled: Boolean,
    onNew: () -> Unit,
    onOpen: (Conversation) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier
            .background(scheme.surfaceContainerLow)
            .padding(Space.md),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        com.riffbuddy.ui.SectionHeader("Chats", Modifier.padding(start = Space.xs, top = Space.sm, bottom = Space.xs))
        // Hidden on a fresh chat, where it would do nothing, rather than shown
        // greyed out: a dim button there read as broken.
        if (showNew) {
            com.riffbuddy.ui.tactile.FilledTonalButton(
                onClick = onNew,
                enabled = newEnabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Space.sm))
                Text("New chat")
            }
            Spacer(Modifier.height(Space.xs))
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Space.xxs),
        ) {
            chats.forEach { chat ->
                val on = chat.id == current
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .clip(RoundedCornerShape(Radius.md))
                        .background(if (on) scheme.secondaryContainer else scheme.surfaceContainerLow)
                        .tactileClickable(onClickLabel = "Open this chat") { onOpen(chat) }
                        .semantics(mergeDescendants = true) { this.selected = on }
                        .padding(horizontal = Space.md, vertical = Space.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            chat.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                            color = if (on) scheme.onSecondaryContainer else scheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            CoachHome.whenAgo(chat.lastAt, now, zone),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (on) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Three dots that rise and fade in turn while the coach writes. The words
 * ("Thinking…", "Searching the web…") are its label for a screen reader, and
 * a web search also shows them, since that can take a while. Moves by
 * graphicsLayer alone, and stands still when motion is reduced.
 */
@Composable
internal fun TypingIndicator(label: String, showLabel: Boolean) {
    val reduced = LocalReducedMotion.current
    val dot = MaterialTheme.colorScheme.onSurfaceVariant
    val phase = if (reduced) null else rememberInfiniteTransition().animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
    )
    Row(
        Modifier.heightIn(min = 24.dp).semantics(mergeDescendants = true) { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { i ->
            Box(
                Modifier
                    .size(8.dp)
                    .graphicsLayer {
                        val p = phase?.value
                        if (p == null) {
                            alpha = 0.6f
                        } else {
                            // Each dot peaks a third of the cycle after the one before.
                            val d = ((p - i + 3f) % 3f)
                            val lift = if (d < 1f) 1f - kotlin.math.abs(d * 2f - 1f) else 0f
                            alpha = 0.35f + 0.65f * lift
                            translationY = -4.dp.toPx() * lift
                        }
                    }
                    .background(dot, CircleShape),
            )
            if (i < 2) Spacer(Modifier.width(5.dp))
        }
        if (showLabel) {
            Spacer(Modifier.width(Space.md))
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
