package com.riffbuddy.ui.theory

import androidx.compose.runtime.getValue

import androidx.compose.runtime.rememberUpdatedState

import kotlin.math.ceil

import kotlin.math.roundToInt

import androidx.compose.ui.input.pointer.pointerInput

import androidx.compose.foundation.gestures.detectTapGestures

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riffbuddy.domain.music.FretPosition
import com.riffbuddy.domain.music.Fretboard
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.riffbuddy.ui.theme.RiffColors

/** What to print inside each dot. */
/** What each dot on the neck says. Fret numbers read the way tab does. */
enum class DotLabel(val label: String) { FRET("Fret numbers"), NOTE("Note names"), DEGREE("Scale degrees") }

/**
 * The neck.
 *
 * Drawn horizontally and scrolled, rather than squeezed to fit. A fretboard
 * crushed into 360 dp has frets two millimeters apart and is useless for the
 * one thing it is for — recognizing a shape and putting fingers on it.
 *
 * The string order is flipped: the neck is drawn as a player looks down at it,
 * with the low string at the bottom. A diagram that puts the low E on top is a
 * diagram to mentally invert every time.
 */
@Composable
fun FretboardDiagram(
    positions: List<FretPosition>,
    stringCount: Int,
    openNotes: List<String>,
    label: DotLabel,
    highlight: IntRange? = null,
    maxFret: Int = Fretboard.MAX_FRET,
    /**
     * Pitch classes to bring forward — the chord tones of whatever chord is
     * playing. The rest of the scale is still drawn, smaller and
     * dimmer: size carries the difference as well as color.
     */
    emphasis: Set<Int>? = null,
    /** The three-notes-per-string overlay: the (string, fret) cells in the shape. */
    cells: Set<Pair<Int, Int>>? = null,
    /** Note names spelled with flats. */
    preferFlats: Boolean = false,
    /** Tapping a note plays it. Null leaves the neck inert. */
    onTapNote: ((FretPosition) -> Unit)? = null,
    /** The (string, fret) cells sounding right now, as a phrase plays through. */
    sounding: Set<Pair<Int, Int>> = emptySet(),
    /**
     * Tapping any spot on the neck, a dot or not, as (string, fret): for
     * placing dots of your own. Takes the place of [onTapNote].
     */
    onTapCell: ((Int, Int) -> Unit)? = null,
    /** Holding a spot, as (string, fret). */
    onHoldCell: ((Int, Int) -> Unit)? = null,
    /** What a screen reader says for the neck, which is one drawing to it. */
    description: String? = null,
) = BoxWithConstraints {
    val measurer = rememberTextMeasurer()
    // Read through state, not captured: the tap detector outlives recompositions.
    val tapNote by rememberUpdatedState(onTapNote)
    val tapCell by rememberUpdatedState(onTapCell)
    val holdCell by rememberUpdatedState(onHoldCell)

    /*
     * Fit the whole neck when there is room, scroll when there is not.
     *
     * A tablet in landscape has 1200 dp of width and no business making anyone
     * drag a fretboard sideways — the whole point of the big screen is seeing
     * the shape at once. A phone in portrait genuinely cannot show twenty-five
     * frets at a legible size, so there it still scrolls.
     *
     * The threshold is the smallest fret that a dot with a note name inside it
     * stays readable in. Below that, fitting would technically succeed and be
     * useless.
     */
    val gutter = 40.dp
    val available = maxWidth - gutter
    val fitted = available / (maxFret + 1)
    val fitsWithoutScrolling = fitted >= MIN_READABLE_FRET

    val fretWidth = if (fitsWithoutScrolling) fitted else COMFORTABLE_FRET
    // Strings spread to match, so a wide neck does not end up long and thin.
    val stringGap = if (fitsWithoutScrolling) {
        (fretWidth * 0.55f).coerceIn(26.dp, 34.dp)
    } else 30.dp

    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val wood = MaterialTheme.colorScheme.surfaceContainerHigh
    val dotColor = MaterialTheme.colorScheme.primary
    val rootColor = RiffColors.lampLit
    val boxColor = MaterialTheme.colorScheme.tertiary

    // A position up the neck has to come into view by itself: on a phone
    // only about seven frets fit, and Box 1 of A minor (frets 5–8) was cut
    // off at the edge, with higher boxes entirely out of sight. The scroll
    // brings the highlighted frets in, a fret of neck to their left for
    // context; with no position chosen it stays where the player left it.
    val scroll = rememberScrollState()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val reduced = com.riffbuddy.ui.theme.LocalReducedMotion.current
    androidx.compose.runtime.LaunchedEffect(highlight, fitsWithoutScrolling, fretWidth) {
        val range = highlight ?: return@LaunchedEffect
        if (fitsWithoutScrolling) return@LaunchedEffect
        val target = with(density) { (34.dp + fretWidth * (range.first - 2).coerceAtLeast(0)).roundToPx() }
        if (reduced) scroll.scrollTo(target) else scroll.animateScrollTo(target)
    }

    Box(
        Modifier
            .then(if (fitsWithoutScrolling) Modifier else Modifier.horizontalScroll(scroll))
            .padding(vertical = 8.dp)
    ) {
        Canvas(
            Modifier
                .width(fretWidth * (maxFret + 1) + 40.dp)
                .height(stringGap * stringCount + 28.dp)
                .then(
                    if (onTapNote == null && onTapCell == null) Modifier
                    else Modifier.pointerInput(positions, stringCount, maxFret, fretWidth, stringGap) {
                        // The same geometry the drawing uses, run backwards:
                        // nearest string, then the fret space the tap is in
                        // (left of the nut is the open string).
                        fun cellAt(at: Offset): Pair<Int, Int> {
                            val fretPx = fretWidth.toPx()
                            val stringPx = stringGap.toPx()
                            val left = 34.dp.toPx()
                            val top = 14.dp.toPx()
                            val fromTop = ((at.y - top) / stringPx).roundToInt().coerceIn(0, stringCount - 1)
                            val string = stringCount - 1 - fromTop
                            val fret = if (at.x < left) 0 else ceil((at.x - left) / fretPx).toInt().coerceIn(1, maxFret)
                            return string to fret
                        }
                        detectTapGestures(
                            onLongPress = { at -> holdCell?.let { hold -> cellAt(at).let { (s, f) -> hold(s, f) } } },
                        ) { tap ->
                            val (string, fret) = cellAt(tap)
                            val cell = tapCell
                            if (cell != null) cell(string, fret)
                            else positions.firstOrNull { it.string == string && it.fret == fret }?.let { tapNote?.invoke(it) }
                        }
                    }
                )
                .then(if (description == null) Modifier else Modifier.semantics { contentDescription = description })
        ) {
            val fretPx = fretWidth.toPx()
            val stringPx = stringGap.toPx()
            val left = 34.dp.toPx()
            val top = 14.dp.toPx()

            fun stringY(index: Int): Float {
                // Flip: string 0 is the lowest and belongs at the bottom.
                val fromTop = stringCount - 1 - index
                return top + fromTop * stringPx
            }

            drawRect(
                color = wood,
                topLeft = Offset(left, top - stringPx * 0.4f),
                size = Size(fretPx * maxFret, stringPx * (stringCount - 1) + stringPx * 0.8f),
            )

            highlight?.let { range ->
                // Fret n's space is between wires n-1 and n. Fret 0 is the open
                // string, left of the nut where its dots are drawn; counting it
                // as a fret space shifted an open position one fret right.
                val start = if (range.first == 0) left - 28f else left + (range.first - 1) * fretPx
                val end = left + range.last.coerceAtLeast(0) * fretPx
                drawRect(
                    color = boxColor.copy(alpha = 0.18f),
                    topLeft = Offset(start, top - stringPx * 0.4f),
                    size = Size(end - start, stringPx * (stringCount - 1) + stringPx * 0.8f),
                )
            }

            // The nut is thick; the rest are wires.
            for (fret in 0..maxFret) {
                val x = left + fret * fretPx
                drawLine(
                    color = if (fret == 0) onSurface else muted.copy(alpha = 0.5f),
                    start = Offset(x, top - stringPx * 0.4f),
                    end = Offset(x, top + stringPx * (stringCount - 1) + stringPx * 0.4f),
                    strokeWidth = if (fret == 0) 6f else 2f,
                )
            }

            for (string in 0 until stringCount) {
                val y = stringY(string)
                drawLine(
                    color = muted,
                    start = Offset(left, y),
                    end = Offset(left + maxFret * fretPx, y),
                    // Low strings are visibly thicker, as they are on the guitar.
                    strokeWidth = 1.2f + (stringCount - 1 - string) * 0.5f,
                )
            }

            // Position markers, where a guitar actually has them.
            listOf(3, 5, 7, 9, 15, 17, 19, 21).filter { it <= maxFret }.forEach { fret ->
                markerDot(left, fretPx, fret, top, stringPx, stringCount, muted)
            }
            listOf(12, 24).filter { it <= maxFret }.forEach { fret ->
                markerDot(left, fretPx, fret, top, stringPx, stringCount, muted, double = true)
            }

            openNotes.forEachIndexed { index, note ->
                drawText(
                    textMeasurer = measurer,
                    text = note,
                    topLeft = Offset(4f, stringY(index) - 16f),
                    style = TextStyle(color = muted, fontSize = 10.sp),
                )
            }

            positions.filter { it.fret <= maxFret }.forEach { position ->
                val x = if (position.fret == 0) left - 14f
                else left + (position.fret - 0.5f) * fretPx
                val y = stringY(position.string)
                val faded = (emphasis != null && position.pitchClass !in emphasis) ||
                    (cells != null && (position.string to position.fret) !in cells)
                val radius = stringPx * if (faded) 0.24f else 0.38f

                drawCircle(
                    color = (if (position.isRoot) rootColor else dotColor).let { if (faded) it.copy(alpha = 0.4f) else it },
                    radius = radius,
                    center = Offset(x, y),
                )
                if ((position.string to position.fret) in sounding) {
                    // A thick ring, and the dot drawn larger: the note being
                    // heard stands out by size and outline, not color alone.
                    drawCircle(
                        color = boxColor,
                        radius = stringPx * 0.5f,
                        center = Offset(x, y),
                        style = Stroke(width = 5f),
                    )
                }
                if (faded) return@forEach
                // Roots get a ring as well as a color, so the distinction
                // survives for anyone who cannot see the difference.
                if (position.isRoot) {
                    drawCircle(
                        color = onSurface,
                        radius = radius + 2.5f,
                        center = Offset(x, y),
                        style = Stroke(width = 2f),
                    )
                }

                val text = when (label) {
                    DotLabel.DEGREE -> position.degree
                    DotLabel.FRET -> position.fret.toString()
                    DotLabel.NOTE -> noteNameOf(position, preferFlats)
                }
                val layout = measurer.measure(text, TextStyle(fontSize = 10.sp))
                drawText(
                    textLayoutResult = layout,
                    color = Color.Black,
                    topLeft = Offset(x - layout.size.width / 2f, y - layout.size.height / 2f),
                )
            }
        }
    }
}

private fun DrawScope.markerDot(
    left: Float,
    fretPx: Float,
    fret: Int,
    top: Float,
    stringPx: Float,
    stringCount: Int,
    color: Color,
    double: Boolean = false,
) {
    val x = left + (fret - 0.5f) * fretPx
    val middle = top + stringPx * (stringCount - 1) / 2f
    if (double) {
        drawCircle(color.copy(alpha = 0.35f), 5f, Offset(x, middle - stringPx))
        drawCircle(color.copy(alpha = 0.35f), 5f, Offset(x, middle + stringPx))
    } else {
        drawCircle(color.copy(alpha = 0.35f), 5f, Offset(x, middle))
    }
}

/** Below this a dot with a note name in it stops being readable. */
private val MIN_READABLE_FRET = 30.dp

/** What a fret gets when the neck has to scroll anyway. */
private val COMFORTABLE_FRET = 46.dp

private fun noteNameOf(position: FretPosition, preferFlats: Boolean): String =
    com.riffbuddy.domain.music.Notes.name(position.midi, preferFlats).dropLast(1)
