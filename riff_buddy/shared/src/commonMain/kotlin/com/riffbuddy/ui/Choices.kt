package com.riffbuddy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.riffbuddy.ui.tactile.FilterChip
import androidx.compose.material3.MaterialTheme
import com.riffbuddy.ui.tactile.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Past this many options a row of chips becomes a dropdown. */
const val CHIP_LIMIT = 8

/**
 * One choice from a list — the way every picker in the app is drawn.
 *
 * Nothing scrolls sideways. A sideways row hides its last options past the
 * edge of a phone with nothing to say they are there (a take's Delete button,
 * 7/8 and 12/8), so short lists wrap onto a second line where everything is
 * visible, and long ones become a dropdown. The dropdown is alphabetical,
 * which suits names (models, tunings, scales).
 * Lists with an order of their own — frets, positions up the neck — pass
 * [alphabetical] false and keep it. [keepFirst] pins an "Any"/"All" option
 * above the sorted rest.
 */
@Composable
fun <T> Choices(
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    onPick: (T) -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    alphabetical: Boolean = true,
    keepFirst: Boolean = false,
    enabled: Boolean = true,
    chipLimit: Int = CHIP_LIMIT,
) {
    if (options.size <= chipLimit) {
        ChipFlow(modifier) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onPick(option) },
                    label = { Text(label(option)) },
                    enabled = enabled,
                    modifier = Modifier.heightIn(min = 40.dp),
                )
            }
        }
        return
    }

    val ordered = if (!alphabetical) options else {
        val head = if (keepFirst) options.take(1) else emptyList()
        head + (options - head.toSet()).sortedBy { label(it).lowercase() }
    }
    var open by remember { mutableStateOf(false) }
    val current = selected?.let(label) ?: "Choose"
    Box(modifier) {
        OutlinedButton(
            onClick = { open = true },
            enabled = enabled,
            modifier = Modifier.heightIn(min = 48.dp).semantics {
                contentDescription = "${title ?: "Choice"}: $current. Opens a list of ${options.size}"
            },
        ) {
            Text(if (title != null) "$title: $current  ▾" else "$current  ▾")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ordered.forEach { option ->
                val isSelected = option == selected
                DropdownMenuItem(
                    text = {
                        Text(
                            (if (isSelected) "✓ " else "") + label(option),
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    onClick = { open = false; onPick(option) },
                )
            }
        }
    }
}

/**
 * A filter or sort folded into one chip: it shows what's chosen, and a tap
 * opens the whole list as a menu.
 *
 * For controls that sit above a list, where a chip per option ate the
 * screen: the Songs tab spent six rows of chips on status, tuning and sort
 * on a phone before the first song. [active] lights the chip when it's
 * narrowing the list, so a filter left on is visible at a glance; the words
 * on the chip say which, so the state isn't carried by color alone.
 */
@Composable
fun <T> MenuChip(
    text: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onPick: (T) -> Unit,
    description: String,
    active: Boolean = false,
    icon: ImageVector? = null,
    /** Opens the menu as the chip first appears — for a chip that appears because a choice is now needed. */
    startOpen: Boolean = false,
) {
    var open by remember { mutableStateOf(startOpen) }
    Box {
        FilterChip(
            selected = active,
            onClick = { open = true },
            label = { Text(text) },
            leadingIcon = icon?.let { { Icon(it, contentDescription = null, modifier = Modifier.size(18.dp)) } },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp)) },
            modifier = Modifier.heightIn(min = 40.dp).semantics {
                contentDescription = "$description: ${label(selected)}. Opens a list of ${options.size}"
            },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                val isSelected = option == selected
                DropdownMenuItem(
                    text = {
                        Text(
                            (if (isSelected) "✓ " else "") + label(option),
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    onClick = { open = false; onPick(option) },
                )
            }
        }
    }
}

/** Chips that wrap onto more lines instead of running off the edge. */
@Composable
fun ChipFlow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) { content() }
}
