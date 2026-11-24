package com.riffbuddy.ui.tone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.riffbuddy.ai.ProviderId
import com.riffbuddy.ui.ChipFlow
import com.riffbuddy.ui.tactile.FilterChip
import com.riffbuddy.ui.tactile.IconButton
import com.riffbuddy.ui.tactile.TextButton
import com.riffbuddy.ui.theme.Radius
import com.riffbuddy.ui.theme.Space
import com.riffbuddy.ui.theme.tactileClickable

/** One provider the player has a key for, and the models it offers. */
data class ModelChoice(
    val id: ProviderId,
    /** The model it answers with now. */
    val model: String,
    /** Fetched with the player's key; empty until asked for. */
    val models: List<String> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

/**
 * Who answers, changeable from the coach without a trip to Settings. Only
 * providers with a key are offered, and paid ones say so before they are picked.
 */
data class ModelSwitch(
    val choices: List<ModelChoice>,
    val current: ProviderId,
    /** Whether paid providers are tried when the first choice can't answer. */
    val paidFallback: Boolean,
    val onPick: (ProviderId, String) -> Unit,
    val onRefresh: (ProviderId) -> Unit,
)

/** "Paid", in words and an outline, so it isn't told by color alone. */
@Composable
internal fun PaidBadge(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Text(
        "Paid",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = scheme.onSurface,
        modifier = modifier
            .clip(RoundedCornerShape(Radius.pill))
            .background(scheme.surfaceContainerHighest)
            .border(1.dp, scheme.outline, RoundedCornerShape(Radius.pill))
            .padding(horizontal = Space.sm, vertical = Space.xxs),
    )
}

@Composable
internal fun ModelSwitchDialog(switch: ModelSwitch, onDismiss: () -> Unit) {
    val current = switch.choices.firstOrNull { it.id == switch.current }
    // The list is fetched with the player's key the first time it's wanted,
    // never bundled, so it is always what the provider offers today.
    LaunchedEffect(current?.id) {
        if (current != null && current.models.isEmpty() && !current.loading && current.error == null) {
            switch.onRefresh(current.id)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Who answers") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Space.md),
            ) {
                Column(Modifier.selectableGroup()) {
                    switch.choices.forEach { c ->
                        val on = c.id == switch.current
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(Radius.sm))
                                .tactileClickable { switch.onPick(c.id, c.model) }
                                .semantics(mergeDescendants = true) {
                                    role = Role.RadioButton
                                    selected = on
                                    contentDescription = "${c.id.label}, ${c.model}" + if (c.id.paid) ", paid" else ""
                                }
                                .padding(end = Space.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = on, onClick = null, modifier = Modifier.padding(Space.sm))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(c.id.label, style = MaterialTheme.typography.titleSmall)
                                    if (c.id.paid) {
                                        Spacer(Modifier.width(Space.sm))
                                        PaidBadge()
                                    }
                                }
                                Text(
                                    c.model.substringAfterLast('/'),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                if (current != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${current.id.label} models",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f),
                        )
                        if (current.loading) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            IconButton(onClick = { switch.onRefresh(current.id) }) {
                                Icon(Icons.Filled.Refresh, contentDescription = "Load ${current.id.label}'s models again")
                            }
                        }
                    }
                    current.error?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    val models = (listOf(current.model) + current.models).distinct()
                    ChipFlow {
                        models.forEach { m ->
                            FilterChip(
                                selected = m == current.model,
                                onClick = { switch.onPick(current.id, m) },
                                label = { Text(m.substringAfterLast('/')) },
                            )
                        }
                    }
                }
                Text(
                    if (switch.paidFallback) "If this one can't answer, the others with keys are tried, paid ones included."
                    else "If this one can't answer, your free providers are tried. Paid ones are only used when picked here, or when \"Use paid providers as a fallback\" is on in Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
