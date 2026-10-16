package com.riffbuddy.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.riffbuddy.ui.tactile.TextButton

/** The key that was pressed, for a shortcut bound to more than one, and whether Shift was held. */
class Press(val key: Key, val shift: Boolean)

/**
 * One keyboard shortcut: the [keys] that trigger it, how they are written in
 * the list of shortcuts ([label], such as "Space" or "← →"), and what it
 * [does], in the same words. [primary] means held with Ctrl, or ⌘ on a Mac.
 * Shift never stops a shortcut matching; [run] can read it to do more,
 * such as a bigger tempo step.
 */
class Shortcut(
    val label: String,
    val does: String,
    val keys: List<Key>,
    val primary: Boolean = false,
    val run: Press.() -> Unit,
)

/**
 * The keyboard shortcuts in force, for a computer with a keyboard.
 *
 * Each screen lists the keys it answers while it is showing, and a key goes to
 * the screen first, then to the keys that work anywhere. So Space means the
 * main control of whatever is on screen (record on the recorder, play on the
 * looper) rather than one thing everywhere, which is what people expect from
 * a player or a recording app. Typing in a text field never presses
 * anything, and nothing here fires behind an open dialog: see [handle] and
 * [shortcutRoot] for how.
 *
 * The list of shortcuts is drawn from these same entries, so it always shows
 * exactly the keys that work on the screen in front of you.
 */
class ShortcutRegistry(
    /** Keys the platform answers itself, such as Escape for Back; listed with the app-wide ones. */
    private val base: List<Shortcut> = emptyList(),
) {
    internal class Layer(title: String, shortcuts: List<Shortcut>, val appWide: Boolean) {
        var title by mutableStateOf(title)
        var shortcuts by mutableStateOf(shortcuts)
    }

    internal val layers = mutableStateListOf<Layer>()

    /** Whether the list of shortcuts is open. */
    var showingList by mutableStateOf(false)

    /** A key that types a character went down and nothing took it; it acts once its character does too. */
    private var waitingToType: Key? = null

    /**
     * Handles a key the focused control didn't use; true if a shortcut took it.
     *
     * A computer reports a key twice: pressed, then the character it typed.
     * A text field takes the character but lets the press go by, so a key
     * that types (Space, a letter) only acts once its character has also
     * come through unclaimed: typing "m" in a search box stays typing. Its
     * press must have come through too, or a button reached with Tab would
     * be pressed by Space and the screen's Space would fire as well. Keys
     * that type nothing (arrows, Escape, Ctrl+1) act as they go down.
     */
    fun handle(event: KeyEvent): Boolean {
        val primary = if (onMac) event.isMetaPressed else event.isCtrlPressed
        val other = event.isAltPressed || (if (onMac) event.isCtrlPressed else event.isMetaPressed)
        return when (event.type) {
            KeyEventType.KeyDown -> {
                val types = !primary && !other && event.utf16CodePoint >= ' '.code && event.utf16CodePoint != 0x7f
                if (types) { waitingToType = event.key; false }
                else handle(event.key, shift = event.isShiftPressed, primary = primary, otherModifier = other)
            }
            KeyEventType.KeyUp -> { waitingToType = null; false }
            else -> {
                val key = waitingToType ?: return false
                waitingToType = null
                handle(key, shift = event.isShiftPressed)
            }
        }
    }

    fun handle(key: Key, shift: Boolean = false, primary: Boolean = false, otherModifier: Boolean = false): Boolean {
        // Alt and the like are the system's and the window manager's, never ours.
        if (otherModifier) return false
        val match = inOrder().firstOrNull { s -> s.primary == primary && key in s.keys } ?: return false
        match.run(Press(key, shift))
        return true
    }

    /** The screen's keys before the app-wide ones, the most recently shown screen first. */
    private fun inOrder(): List<Shortcut> =
        layers.asReversed().filter { !it.appWide }.flatMap { it.shortcuts } +
            layers.filter { it.appWide }.flatMap { it.shortcuts } + base

    /** What the list shows: a group per screen layer, then one for everything that works anywhere. */
    fun groups(): List<Pair<String, List<Shortcut>>> {
        val screens = layers.asReversed().filter { !it.appWide && it.shortcuts.isNotEmpty() }.map { it.title to it.shortcuts }
        val anywhere = layers.filter { it.appWide }.flatMap { it.shortcuts } + base
        return screens + listOfNotNull(if (anywhere.isEmpty()) null else "Anywhere" to anywhere)
    }

    companion object {
        val onMac = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

        /** How the primary modifier is written in front of a key: "Ctrl+" or "⌘". */
        val primaryLabel = if (onMac) "⌘" else "Ctrl+"
    }
}

/** The registry, where the platform has a keyboard to drive it; null on a phone or tablet. */
val LocalShortcuts = staticCompositionLocalOf<ShortcutRegistry?> { null }

/**
 * The keys this screen answers while it is showing, listed under [title].
 * Null entries are skipped, so a shortcut that only applies sometimes can be
 * written inline. [appWide] is for the app's own keys, which a screen's
 * keys always outrank. Does nothing where there is no keyboard.
 */
@Composable
fun KeyShortcuts(title: String, vararg shortcuts: Shortcut?, appWide: Boolean = false) {
    val registry = LocalShortcuts.current ?: return
    val list = shortcuts.filterNotNull()
    val layer = remember { ShortcutRegistry.Layer(title, list, appWide) }
    // Refreshed on every recomposition, so each shortcut runs with the
    // screen's current state rather than what it was when first shown.
    SideEffect { layer.title = title; layer.shortcuts = list }
    DisposableEffect(registry) {
        registry.layers += layer
        onDispose { registry.layers -= layer }
    }
}

/**
 * Where the keys are caught: the root of the app's content, which keeps focus
 * so that every key the focused control leaves unused bubbles up to it.
 *
 * Caught here rather than on the window because an open dialog takes the
 * keyboard for itself and none of its keys reach this root, whereas the
 * window hears every key a dialog doesn't use, and Space would start a
 * recording behind a rename box. Escape in a text field first leaves the
 * field, so the next key is a shortcut again; only then does it mean Back.
 */
@Composable
fun shortcutRoot(): Modifier {
    val registry = LocalShortcuts.current ?: return Modifier
    val root = remember { FocusRequester() }
    var holdingFocus by remember { mutableStateOf(false) }
    val alive = remember { booleanArrayOf(true) }
    LaunchedEffect(Unit) { root.requestFocus() }
    DisposableEffect(Unit) { onDispose { alive[0] = false } }
    return Modifier
        .pointerInput(Unit) {
            // A click focuses what it lands on, and a focused button takes
            // Space for itself: after clicking Pause, Space would press Pause
            // again rather than stop the take. So focus comes straight back
            // once the press has been handled. A text field still gets it,
            // because a field takes focus as the click is released, after this.
            awaitPointerEventScope {
                while (true) {
                    val e = awaitPointerEvent(PointerEventPass.Final)
                    if (e.type == PointerEventType.Press && !holdingFocus) runCatching { root.requestFocus() }
                }
            }
        }
        .focusRequester(root)
        .onFocusChanged {
            holdingFocus = it.isFocused
            // A focused field that leaves the screen takes focus with it, and
            // with nothing focused no key reaches here. Opening a dialog
            // doesn't count: this root keeps its focus behind one. Not while
            // the app is closing, when the root itself is going.
            if (!it.hasFocus && alive[0]) runCatching { root.requestFocus() }
        }
        .focusable()
        .onKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape && !holdingFocus) {
                root.requestFocus()
                true
            } else {
                registry.handle(event)
            }
        }
}

/** The list of shortcuts for the screen on show, opened with ? or F1. */
@Composable
fun ShortcutList() {
    val registry = LocalShortcuts.current ?: return
    if (!registry.showingList) return
    AlertDialog(
        onDismissRequest = { registry.showingList = false },
        title = { Text("Keyboard shortcuts") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()).widthIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                registry.groups().forEach { (title, shortcuts) ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SectionHeader(title.uppercase())
                        shortcuts.forEach { s ->
                            Row(
                                Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                                    contentDescription = "${s.label}: ${s.does}"
                                },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                KeyCap(s.label, Modifier.widthIn(min = 96.dp))
                                Text(s.does, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { registry.showingList = false }) { Text("Done") } },
    )
}

@Composable
private fun KeyCap(text: String, modifier: Modifier = Modifier) {
    Row(modifier) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}
