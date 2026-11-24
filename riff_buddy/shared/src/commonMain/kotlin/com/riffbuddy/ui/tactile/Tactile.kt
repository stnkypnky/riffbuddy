package com.riffbuddy.ui.tactile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.ChipColors
import androidx.compose.material3.ChipElevation
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.SelectableChipElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import com.riffbuddy.ui.theme.pressScale

/*
 * Material's buttons and chips, with a press you can feel.
 *
 * Same names and parameters as androidx.compose.material3's, so a screen
 * opts in by changing its imports and nothing else. Each one makes (or
 * borrows) the interaction source, adds [pressScale], and hands everything
 * to the Material component, called by its full name so a wrapper can
 * never call itself.
 *
 * New screens should import these, not Material's (the project notes).
 */

@Composable
private fun source(given: MutableInteractionSource?) = given ?: remember { MutableInteractionSource() }

@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val s = source(interactionSource)
    androidx.compose.material3.Button(
        onClick = onClick, modifier = modifier.pressScale(s), enabled = enabled, shape = shape,
        colors = colors, elevation = elevation, border = border, contentPadding = contentPadding,
        interactionSource = s, content = content,
    )
}

@Composable
fun FilledTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.filledTonalShape,
    colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(),
    elevation: ButtonElevation? = ButtonDefaults.filledTonalButtonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val s = source(interactionSource)
    androidx.compose.material3.FilledTonalButton(
        onClick = onClick, modifier = modifier.pressScale(s), enabled = enabled, shape = shape,
        colors = colors, elevation = elevation, border = border, contentPadding = contentPadding,
        interactionSource = s, content = content,
    )
}

@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.outlinedShape,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = ButtonDefaults.outlinedButtonBorder(enabled),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val s = source(interactionSource)
    androidx.compose.material3.OutlinedButton(
        onClick = onClick, modifier = modifier.pressScale(s), enabled = enabled, shape = shape,
        colors = colors, elevation = elevation, border = border, contentPadding = contentPadding,
        interactionSource = s, content = content,
    )
}

@Composable
fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.textShape,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val s = source(interactionSource)
    androidx.compose.material3.TextButton(
        onClick = onClick, modifier = modifier.pressScale(s), enabled = enabled, shape = shape,
        colors = colors, elevation = elevation, border = border, contentPadding = contentPadding,
        interactionSource = s, content = content,
    )
}

@Composable
fun IconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    val s = source(interactionSource)
    androidx.compose.material3.IconButton(
        onClick = onClick, modifier = modifier.pressScale(s, 0.88f), enabled = enabled,
        colors = colors, interactionSource = s, content = content,
    )
}

@Composable
fun FilledIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = IconButtonDefaults.filledShape,
    colors: IconButtonColors = IconButtonDefaults.filledIconButtonColors(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    val s = source(interactionSource)
    androidx.compose.material3.FilledIconButton(
        onClick = onClick, modifier = modifier.pressScale(s, 0.9f), enabled = enabled, shape = shape,
        colors = colors, interactionSource = s, content = content,
    )
}

@Composable
fun FilledTonalIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = IconButtonDefaults.filledShape,
    colors: IconButtonColors = IconButtonDefaults.filledTonalIconButtonColors(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    val s = source(interactionSource)
    androidx.compose.material3.FilledTonalIconButton(
        onClick = onClick, modifier = modifier.pressScale(s, 0.9f), enabled = enabled, shape = shape,
        colors = colors, interactionSource = s, content = content,
    )
}

@Composable
fun OutlinedIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = IconButtonDefaults.outlinedShape,
    colors: IconButtonColors = IconButtonDefaults.outlinedIconButtonColors(),
    border: BorderStroke? = IconButtonDefaults.outlinedIconButtonBorder(enabled),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    val s = source(interactionSource)
    androidx.compose.material3.OutlinedIconButton(
        onClick = onClick, modifier = modifier.pressScale(s, 0.9f), enabled = enabled, shape = shape,
        colors = colors, border = border, interactionSource = s, content = content,
    )
}

@Composable
fun FilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    shape: Shape = FilterChipDefaults.shape,
    colors: SelectableChipColors = selectedChipColors(),
    elevation: SelectableChipElevation? = FilterChipDefaults.filterChipElevation(),
    border: BorderStroke? = selectedChipBorder(enabled, selected),
    interactionSource: MutableInteractionSource? = null,
) {
    val s = source(interactionSource)
    // The fill alone is not enough: on some finishes a chosen chip is only a
    // shade lighter than the rest. So a chosen chip also gets a check (unless
    // it brings its own icon) and a heavier label, which any finish shows.
    val icon = leadingIcon ?: if (selected) {
        { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
    } else null
    val shownLabel: @Composable () -> Unit = if (selected) {
        { ProvideTextStyle(LocalTextStyle.current.copy(fontWeight = FontWeight.SemiBold)) { label() } }
    } else label
    androidx.compose.material3.FilterChip(
        selected = selected, onClick = onClick, label = shownLabel, modifier = modifier.pressScale(s),
        enabled = enabled, leadingIcon = icon, trailingIcon = trailingIcon, shape = shape,
        colors = colors, elevation = elevation, border = border, interactionSource = s,
    )
}

/**
 * Selected chips filled amber. Material's default fills them with the
 * secondary container, which in this theme is the same color as a dialog's
 * background — so in the restore dialog the chosen option looked like plain
 * text, and elsewhere it was a faint shade away from the unchosen ones.
 */
@Composable
fun selectedChipColors(): SelectableChipColors = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedTrailingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
)

@Composable
fun selectedChipBorder(enabled: Boolean, selected: Boolean): BorderStroke? =
    if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
    else FilterChipDefaults.filterChipBorder(enabled, selected)

@Composable
fun InputChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: @Composable (() -> Unit)? = null,
    avatar: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    shape: Shape = InputChipDefaults.shape,
    colors: SelectableChipColors = InputChipDefaults.inputChipColors(),
    elevation: SelectableChipElevation? = InputChipDefaults.inputChipElevation(),
    border: BorderStroke? = InputChipDefaults.inputChipBorder(enabled, selected),
    interactionSource: MutableInteractionSource? = null,
) {
    val s = source(interactionSource)
    androidx.compose.material3.InputChip(
        selected = selected, onClick = onClick, label = label, modifier = modifier.pressScale(s),
        enabled = enabled, leadingIcon = leadingIcon, avatar = avatar, trailingIcon = trailingIcon,
        shape = shape, colors = colors, elevation = elevation, border = border, interactionSource = s,
    )
}

@Composable
fun AssistChip(
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    shape: Shape = AssistChipDefaults.shape,
    colors: ChipColors = AssistChipDefaults.assistChipColors(),
    elevation: ChipElevation? = AssistChipDefaults.assistChipElevation(),
    border: BorderStroke? = AssistChipDefaults.assistChipBorder(enabled),
    interactionSource: MutableInteractionSource? = null,
) {
    val s = source(interactionSource)
    androidx.compose.material3.AssistChip(
        onClick = onClick, label = label, modifier = modifier.pressScale(s), enabled = enabled,
        leadingIcon = leadingIcon, trailingIcon = trailingIcon, shape = shape, colors = colors,
        elevation = elevation, border = border, interactionSource = s,
    )
}
