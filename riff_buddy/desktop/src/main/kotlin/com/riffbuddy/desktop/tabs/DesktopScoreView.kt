@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.riffbuddy.desktop.tabs

import alphaTab.platform.IMouseEventArgs
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.riffbuddy.tabs.AlphaTabPlayer
import com.riffbuddy.tabs.TabSource
import kotlin.math.roundToInt

/**
 * The rendered score, playing through alphaTab's synthesizer and the
 * SoundFont it ships, in a desktop window.
 *
 * Drawn on paper-colored ground, as on a phone: alphaTab paints black
 * glyphs, and sheet music on a light card is how notation is read.
 */
@Composable
fun DesktopScoreView(player: AlphaTabPlayer, source: TabSource, modifier: Modifier = Modifier) {
    val density = LocalDensity.current.density
    val host = remember {
        AlphaTabDesktop.prepare(density.toDouble())
        ScoreHost(density, player.horizontal, player.zoom)
    }
    host.viewScope = rememberCoroutineScope()
    DisposableEffect(host) {
        player.attach(host.api)
        onDispose {
            player.detach()
            host.destroy()
        }
    }
    // Laid out first, so alphaTab knows how wide a line is before it draws one.
    LaunchedEffect(host, source) {
        host.awaitVisible()
        player.open(source)
    }

    Box(
        modifier
            .background(PAPER)
            .onSizeChanged { host.viewportChanged(it.width / density.toDouble(), it.height / density.toDouble()) },
    ) {
        Box(Modifier.fillMaxSize().verticalScroll(host.vertical).horizontalScroll(host.horizontal)) {
            val width = maxOf(host.totalWidth, host.surface.width) + host.overflowX
            val height = maxOf(host.totalHeight, host.surface.height) + host.overflowY
            Box(
                Modifier
                    .size(width.dp, height.dp)
                    .pointerInput(host) { forwardMouse(host, density) },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    for (part in host.parts) {
                        val image = part.image ?: continue
                        drawImage(
                            image,
                            dstOffset = IntOffset((part.x * density).roundToInt(), (part.y * density).roundToInt()),
                            dstSize = IntSize((part.w * density).roundToInt(), (part.h * density).roundToInt()),
                        )
                    }
                }
                if (host.cursorsShown) {
                    for (selected in host.selectionLayer.children) Marker(selected, SELECTION)
                    Marker(host.barCursor, BAR_CURSOR)
                    Marker(host.beatCursor, BEAT_CURSOR, minWidth = 2.0)
                }
            }
        }
    }
}

/**
 * Presses, drags and releases on the score go to alphaTab as mouse events,
 * which is how it picks a beat to play from and selects a stretch of bars.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.forwardMouse(host: ScoreHost, density: Float) {
    awaitPointerEventScope {
        var down = false
        while (true) {
            val event = awaitPointerEvent()
            val position = event.changes.firstOrNull()?.position ?: continue
            val mouse = ScoreMouse(position.x / density.toDouble(), position.y / density.toDouble(), host)
            @Suppress("UNCHECKED_CAST")
            when (event.type) {
                PointerEventType.Press -> if (event.buttons.isPrimaryPressed) {
                    down = true
                    (host.surface.mouseDown as EmitterOf<IMouseEventArgs>).trigger(mouse)
                }
                PointerEventType.Move -> if (down) (host.surface.mouseMove as EmitterOf<IMouseEventArgs>).trigger(mouse)
                PointerEventType.Release -> if (down) {
                    down = false
                    (host.surface.mouseUp as EmitterOf<IMouseEventArgs>).trigger(mouse)
                }
            }
        }
    }
}

/** A cursor or selection box, following its container; the beat cursor glides between beats. */
@Composable
private fun Marker(box: BoxContainer, color: Color, minWidth: Double = 0.0) {
    var x by remember { mutableStateOf(box.x) }
    val glide = box.glide
    LaunchedEffect(glide, box.x) {
        if (glide == null) {
            x = box.x
            return@LaunchedEffect
        }
        while (true) {
            val done = withFrameNanos {
                val now = System.nanoTime()
                x = glide.xAt(now)
                x == glide.toX
            }
            if (done) break
        }
    }
    if (box.h <= 0) return
    Box(
        Modifier
            .offset(x.dp, box.y.dp)
            .size(maxOf(box.w, minWidth).dp, box.h.dp)
            .background(color),
    )
}

private val PAPER = Color(0xFFF6F1E7)
// Warm amber rather than alphaTab's yellow and blue, so the cursor and the loop read as part of the app.
private val BAR_CURSOR = Color(0xE8, 0x9A, 0x2E, 56)
private val BEAT_CURSOR = Color(0xC2, 0x5E, 0x10, 200)
private val SELECTION = Color(0xE8, 0x9A, 0x2E, 64)
