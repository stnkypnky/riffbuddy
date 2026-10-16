package com.riffbuddy.desktop

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import com.riffbuddy.ui.Shortcut
import com.riffbuddy.ui.ShortcutRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which shortcut a key reaches. The keys arrive as the window delivers them:
 * pressed, then (for a key that types) its character, then released.
 */
@OptIn(InternalComposeUiApi::class)
class ShortcutsTest {

    private val heard = mutableListOf<String>()

    private fun shortcut(name: String, key: Key, primary: Boolean = false) =
        Shortcut(name, name, listOf(key), primary = primary) { heard += name + if (shift) "+shift" else "" }

    private fun registry(vararg screen: Shortcut, anywhere: List<Shortcut> = emptyList()) = ShortcutRegistry().apply {
        layers += ShortcutRegistry.Layer("Anywhere", anywhere, appWide = true)
        layers += ShortcutRegistry.Layer("Screen", screen.toList(), appWide = false)
    }

    private fun event(key: Key, type: KeyEventType, char: Char? = null, ctrl: Boolean = false, shift: Boolean = false) =
        KeyEvent(key = key, type = type, codePoint = char?.code ?: 0, isCtrlPressed = ctrl, isShiftPressed = shift)

    /** A whole key press; returns what each of its three events was told. */
    private fun ShortcutRegistry.type(key: Key, char: Char?, ctrl: Boolean = false, shift: Boolean = false, fieldTakesChar: Boolean = false): Boolean {
        val down = handle(event(key, KeyEventType.KeyDown, char, ctrl, shift))
        val typed = if (char != null && !fieldTakesChar) handle(event(Key.Unknown, KeyEventType.Unknown, char, ctrl, shift)) else false
        handle(event(key, KeyEventType.KeyUp, char, ctrl, shift))
        return down || typed
    }

    @Test
    fun theScreenOutranksTheAppWideKeys() {
        val r = registry(shortcut("record", Key.Spacebar), anywhere = listOf(shortcut("metronome", Key.Spacebar)))
        assertTrue(r.type(Key.Spacebar, ' '))
        assertEquals(listOf("record"), heard)
    }

    @Test
    fun aKeyTheScreenDoesntUseFallsThroughToTheAppWideOnes() {
        val r = registry(shortcut("record", Key.Spacebar), anywhere = listOf(shortcut("metronome", Key.M)))
        assertTrue(r.type(Key.M, 'm'))
        assertEquals(listOf("metronome"), heard)
    }

    @Test
    fun typingInATextFieldNeverTriggersAShortcut() {
        // The field takes the character and lets the press go by, as a real one does.
        val r = registry(shortcut("record", Key.Spacebar), anywhere = listOf(shortcut("metronome", Key.M)))
        assertFalse(r.type(Key.Spacebar, ' ', fieldTakesChar = true))
        assertFalse(r.type(Key.M, 'm', fieldTakesChar = true))
        assertEquals(emptyList<String>(), heard)
    }

    @Test
    fun aCharacterWhosePressWasTakenDoesNothing() {
        // A button reached with Tab takes Space's press; the screen's Space must not fire as well.
        val r = registry(shortcut("record", Key.Spacebar))
        assertFalse(r.handle(event(Key.Unknown, KeyEventType.Unknown, ' ')))
        assertEquals(emptyList<String>(), heard)
    }

    @Test
    fun keysThatTypeNothingActAsTheyGoDown() {
        val r = registry(shortcut("faster", Key.DirectionUp))
        assertTrue(r.handle(event(Key.DirectionUp, KeyEventType.KeyDown, shift = true)))
        assertEquals(listOf("faster+shift"), heard)
    }

    @Test
    fun theModifierHasToMatch() {
        val r = registry(anywhere = listOf(shortcut("songs", Key.Two, primary = true), shortcut("metronome", Key.M)))
        assertFalse("2 alone is not Ctrl+2", r.type(Key.Two, '2'))
        assertFalse("Ctrl+M is not M", r.type(Key.M, '\r', ctrl = true))
        assertTrue(r.type(Key.Two, null, ctrl = !ShortcutRegistry.onMac))
        assertEquals(if (ShortcutRegistry.onMac) emptyList<String>() else listOf("songs"), heard)
    }

    @Test
    fun shiftReachesTheShortcut() {
        val r = registry(anywhere = listOf(shortcut("list", Key.Slash)))
        assertTrue(r.type(Key.Slash, '?', shift = true))
        assertEquals(listOf("list+shift"), heard)
    }

    @Test
    fun theListPutsTheScreenFirst() {
        val r = registry(shortcut("record", Key.Spacebar), anywhere = listOf(shortcut("metronome", Key.M)))
        assertEquals(listOf("Screen", "Anywhere"), r.groups().map { it.first })
    }
}
