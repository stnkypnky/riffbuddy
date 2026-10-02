@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.riffbuddy.tabs

import alphaTab.AlphaTabView
import alphaTab.LayoutMode
import alphaTab.PlayerMode
import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * The rendered score, playing through alphaTab's own synthesizer and the
 * Sonivox SoundFont it bundles.
 *
 * Drawn on paper-colored ground on purpose. alphaTab paints black glyphs, and
 * notation on dark gray would mean recoloring every glyph, line and number it
 * draws — a dozen colors to keep in step with a library that adds more. Sheet
 * music on a light card is also simply how notation is read, and the contrast
 * is as high as it gets.
 */
@Composable
fun TabScoreView(player: AlphaTabPlayer, source: TabSource, modifier: Modifier = Modifier) {
    var view by remember { mutableStateOf<AlphaTabView?>(null) }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            AlphaTabView(context, null).apply {
                setBackgroundColor(PAPER)
                // the screen stays awake while the player is showing.
                // On the view rather than the window, so it ends exactly when
                // the view does, with nothing to forget to undo.
                keepScreenOn = true
                // Warm amber rather than alphaTab's yellow and blue, so the
                // cursor and the loop read as part of this app.
                barCursorFillColor = Color.argb(56, 0xE8, 0x9A, 0x2E)
                beatCursorFillColor = Color.argb(200, 0xC2, 0x5E, 0x10)
                selectionFillColor = Color.argb(64, 0xE8, 0x9A, 0x2E)
                settings.player.playerMode = PlayerMode.EnabledSynthesizer
                settings.player.enableCursor = true
                settings.player.enableUserInteraction = true
                // Zoom and layout as this file was left.
                settings.display.layoutMode = if (player.horizontal) LayoutMode.Horizontal else LayoutMode.Page
                settings.display.scale = player.zoom.toDouble()
                player.attach(api)
                view = this
            }
        },
        onRelease = { player.detach() },
    )

    // Parsing a six-track file is real work on the Fire, so it happens off the
    // main thread. Rendering is then alphaTab's job, on its own worker.
    LaunchedEffect(view, source) {
        if (view != null) player.open(source)
    }
}

private val PAPER = Color.rgb(0xF6, 0xF1, 0xE7)
