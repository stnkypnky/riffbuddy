package com.riffbuddy.ui.settings

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.riffbuddy.ai.ModelInfo
import com.riffbuddy.ai.ProviderId
import com.riffbuddy.ai.SchemaSupport
import com.riffbuddy.ai.UsageToday
import com.riffbuddy.ui.theme.RiffColors

/** What Settings knows about one provider. */
data class ProviderState(
    val id: ProviderId,
    val hint: String?,
    val model: String,
    val usage: UsageToday,
    val testing: Boolean = false,
    val testResult: String? = null,
    val testPassed: Boolean = false,
    /** Fetched from the provider with the player's key — never a bundled list. */
    val models: List<ModelInfo> = emptyList(),
    val loadingModels: Boolean = false,
    val modelsError: String? = null,
)

/**
 * Settings, with the AI section that first-run setup also reuses.
 *
 * Keys are write-only from here: a stored key is shown as its last four
 * characters and can be replaced or removed, never read back out. There is no
 * legitimate reason for the app to display a key it already has, and a reveal
 * toggle on a *stored* key only creates a way to shoulder-surf it.
 */
@Composable
fun AiSettingsSection(
    providers: List<ProviderState>,
    primary: ProviderId,
    onSaveKey: (ProviderId, String) -> Unit,
    onRemoveKey: (ProviderId) -> Unit,
    onTest: (ProviderId) -> Unit,
    onSetPrimary: (ProviderId) -> Unit,
    onPickModel: (ProviderId, String) -> Unit,
    onRefreshModels: (ProviderId) -> Unit,
    /** Whether paid providers are tried after the free ones; null hides the switch. */
    paidFallback: Boolean = false,
    onPaidFallback: ((Boolean) -> Unit)? = null,
) {
    com.riffbuddy.ui.Panel(Modifier.fillMaxWidth(), contentPadding = com.riffbuddy.ui.theme.Space.panel) {
        Column(verticalArrangement = Arrangement.spacedBy(com.riffbuddy.ui.theme.Space.md)) {
            com.riffbuddy.ui.SectionHeader("AI coach")
            Text(
                "Free AI, no card. One key is enough; add a second so the coach can switch when " +
                    "one is busy. Not sure? Start with Groq: its recommended model is the smartest " +
                    "free one here. Everything except the coach works with no key at all.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            @Composable
            fun row(provider: ProviderState) = ProviderRow(
                state = provider,
                isPrimary = provider.id == primary,
                onSaveKey = { onSaveKey(provider.id, it) },
                onRemoveKey = { onRemoveKey(provider.id) },
                onTest = { onTest(provider.id) },
                onSetPrimary = { onSetPrimary(provider.id) },
                onPickModel = { onPickModel(provider.id, it) },
                onRefreshModels = { onRefreshModels(provider.id) },
            )
            providers.filter { !it.id.paid }.forEach { row(it) }

            val paid = providers.filter { it.id.paid }
            if (paid.isNotEmpty()) {
                Text(
                    "Paid providers",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = com.riffbuddy.ui.theme.Space.sm).semantics { heading() },
                )
                Text(
                    "Optional, for players who already pay for one. Each is billed by its maker, per use, " +
                        "on your own account; Riff Buddy never charges anything.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                paid.forEach { row(it) }
                if (onPaidFallback != null) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .toggleable(value = paidFallback, role = Role.Switch, onValueChange = onPaidFallback),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Use paid providers as a fallback", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (paidFallback) "On: when the free ones are busy, a paid one with a key answers and bills you."
                                else "Off: a paid provider answers only when it's the one asked first.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.size(com.riffbuddy.ui.theme.Space.md))
                        androidx.compose.material3.Switch(checked = paidFallback, onCheckedChange = null)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderRow(
    state: ProviderState,
    isPrimary: Boolean,
    onSaveKey: (String) -> Unit,
    onRemoveKey: () -> Unit,
    onTest: () -> Unit,
    onSetPrimary: () -> Unit,
    onPickModel: (String) -> Unit,
    onRefreshModels: () -> Unit,
) {
    var entry by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val stored = state.hint != null
    // Four providers, each fully open, is a long page of empty key fields. A
    // provider without a key shows one line and opens when asked.
    var open by remember { mutableStateOf(false) }
    val uri = androidx.compose.ui.platform.LocalUriHandler.current

    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(com.riffbuddy.ui.theme.Space.md),
        verticalArrangement = Arrangement.spacedBy(com.riffbuddy.ui.theme.Space.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // A key or a check in a disc: whether this provider is set up,
            // said again in words by the line under the name.
            Box(
                Modifier.size(36.dp).background(
                    if (stored) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                    androidx.compose.foundation.shape.CircleShape,
                ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (stored) Icons.Filled.Check else Icons.Filled.Key,
                    contentDescription = null,
                    tint = if (stored) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.size(com.riffbuddy.ui.theme.Space.md))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(state.id.label, style = MaterialTheme.typography.titleMedium)
                    if (state.id.paid) {
                        Spacer(Modifier.size(com.riffbuddy.ui.theme.Space.sm))
                        com.riffbuddy.ui.tone.PaidBadge()
                    }
                }
                Text(
                    state.id.freeTier,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (stored) FilterChip(
                selected = isPrimary,
                onClick = onSetPrimary,
                label = { Text(if (isPrimary) "Asked first" else "Use first") },
                modifier = Modifier.heightIn(min = 40.dp),
            ) else TextButton(onClick = { open = !open }) { Text(if (open) "Close" else "Add a key") }
        }

        if (stored) {
            ModelPicker(state, onPickModel, onRefreshModels)
        }
        if (!stored && !open) return@Column

        if (stored) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text("Key stored, ending ${state.hint}", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onRemoveKey) { Text("Remove") }
            }
        } else {
            // How to get one, first: the step people get stuck on is finding
            // the page, not pasting.
            Text(
                "1. Tap Get a ${if (state.id.paid) "" else "free "}key. 2. ${state.id.keySteps} 3. Come back and paste it below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            com.riffbuddy.ui.tactile.FilledTonalButton(
                onClick = { runCatching { uri.openUri(state.id.keyUrl) } },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(if (state.id.paid) "Get a ${state.id.label} key" else "Get a free ${state.id.label} key") }
            // Masked while typing, with a reveal — a pasted key is worth being
            // able to check once. Not offered for a key already stored.
            OutlinedTextField(
                value = entry,
                onValueChange = { entry = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Paste your ${state.id.label} key") },
                visualTransformation = if (visible) VisualTransformation.None
                else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { visible = !visible }) {
                        Icon(
                            if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (visible) "Hide key" else "Show key",
                        )
                    }
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onSaveKey(entry.trim()); entry = "" },
                    enabled = entry.isNotBlank(),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Save key") }
            }
        }

        if (stored) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onTest,
                    enabled = !state.testing,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(if (state.testing) "Testing…" else "Test connection") }
                state.testResult?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.testPassed) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.error,
                    )
                }
            }
            UsageBar(state.usage)
        }
    }
}

/**
 * Which model to ask, listed by the provider rather than bundled.
 *
 * A hard-coded list goes stale silently: providers retire models without
 * notice. Asking the provider with the player's own key is the only list that
 * is true for them today, and it means
 * a model being renamed shows up as a missing entry rather than as an
 * unexplained failure.
 */
@Composable
private fun ModelPicker(
    state: ProviderState,
    onPick: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // The smarter model, one tap away: most people never open the list.
        if (state.model != state.id.recommendedModel) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Suggested: ${state.id.recommendedModel} — ${state.id.recommendedWhy}.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onPick(state.id.recommendedModel) }) { Text("Use it") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "MODEL",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRefresh, enabled = !state.loadingModels) {
                Text(if (state.loadingModels) "Loading…" else "Refresh list")
            }
        }

        when {
            state.modelsError != null -> Text(
                state.modelsError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )

            state.models.isEmpty() -> Text(
                "Using ${state.model}" +
                    (if (com.riffbuddy.ai.WebSearch.supports(state.id, state.model)) ", which can search the web" else "") +
                    ". Tap Refresh list to see what your key can reach.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> {
                // Providers list dozens of models; A–Z in a dropdown.
                com.riffbuddy.ui.Choices(
                    options = state.models,
                    selected = state.models.firstOrNull { it.id == state.model },
                    label = { it.label },
                    onPick = { onPick(it.id) },
                    title = "Model",
                )
                state.models.firstOrNull { it.id == state.model }?.let { chosen ->
                    val schemaNote = when (chosen.schema) {
                        SchemaSupport.FULL -> "Honors a full JSON schema."
                        SchemaSupport.JSON_MODE ->
                            "JSON mode only — replies are valid JSON but the shape is checked here."
                        SchemaSupport.UNKNOWN ->
                            "Schema support unproven — Test connection will find out."
                    }
                    Text(
                        listOf(chosen.note, schemaNote).filter { it.isNotBlank() }.joinToString(" "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** The usage counter: a warning, never a blocker. */
@Composable
private fun UsageBar(usage: UsageToday) {
    // A paid provider has no free budget, and a bar against one would be invented.
    if (usage.budget <= 0) {
        Text(
            "${usage.requests} requests today, billed by ${usage.provider.label}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "${usage.requests} of about ${usage.budget} free requests today",
            style = MaterialTheme.typography.labelSmall,
            color = if (usage.nearLimit) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.outlineVariant)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(usage.fraction.coerceAtLeast(0.01f))
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (usage.nearLimit) MaterialTheme.colorScheme.error else RiffColors.lampLit)
            )
        }
        if (usage.nearLimit) {
            Text(
                "Close to the free limit. These reset daily, and your other providers pick up " +
                    "the slack in the meantime.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}


