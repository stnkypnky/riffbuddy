package com.riffbuddy.ui.theme

import androidx.compose.ui.unit.dp

/*
 * Spacing, corner and elevation steps. A screen that picks its own numbers ends
 * up with ten slightly different gaps and reads as assembled rather than
 * designed; a short scale used everywhere is most of what makes a layout look
 * deliberate. New code uses these; old literals move over as screens are
 * touched.
 */

/** Gaps and padding, on a 4 dp grid. */
object Space {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 48.dp

    /** Padding around a screen's content on a phone, and from medium width up. */
    val screen = 16.dp
    val screenWide = 24.dp

    /** Padding inside a Panel. */
    val panel = 16.dp
}

/** Corner radii. Matches MaterialTheme.shapes, for shapes drawn by hand. */
object Radius {
    val xs = 6.dp
    val sm = 10.dp
    val md = 14.dp
    val lg = 18.dp
    val xl = 24.dp
    val pill = 999.dp
}

/**
 * Shadow depths. On a dark finish a shadow barely shows, so depth there comes
 * from the surface ramp and the top-lit edge; on a light finish the shadow
 * does the work. Both are applied, so each finish gets the cue it can show.
 */
object Elevation {
    val none = 0.dp
    val low = 1.dp
    val card = 2.dp
    val raised = 6.dp
    val overlay = 12.dp
}

/** Content widths, so text never runs the width of a desktop window. */
object Widths {
    /** One column of reading and form controls. */
    val reading = 720.dp
    /** Two panes side by side, or a grid of cards. */
    val wide = 1200.dp
    /** A dialog or a centered card. */
    val dialog = 520.dp
}
