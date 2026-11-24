package com.riffbuddy.platform

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.js.Js
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import okio.FileSystem

/**
 * The browser runs the app on one thread, and its database is in memory
 * on that thread, so "blocking" work simply runs there in turn.
 */
val Dispatchers.Blocking: CoroutineDispatcher get() = Default

/** Whether the browser is on a Mac (or an iPad with a keyboard), whose shortcuts use ⌘. */
val hostIsMac: Boolean = navigatorIsMac()

private fun navigatorIsMac(): Boolean = js("/Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent)")

/** The HTTP engine under the shared client: the browser's own fetch. */
val httpEngine: HttpClientEngineFactory<*> = Js

/** Marks a field shared between threads elsewhere; one thread here, so it does nothing. */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
annotation class Volatile

/** The app's own files: in memory, mirrored to the browser's storage (see WebFiles). */
val appFileSystem: FileSystem get() = com.riffbuddy.web.webFiles.fileSystem

/** A monotonic clock in nanoseconds, from the page's own clock. */
fun nanoTime(): Long = (performanceNow() * 1_000_000).toLong()

private fun performanceNow(): Double = js("performance.now()")

/** An audio loop started by [startAudioLoop]. */
fun interface AudioLoop {
    fun join(timeoutMs: Long)
}

private val audioScope = MainScope()

/**
 * Runs an engine's render or capture loop as a coroutine on the page's one
 * thread. It is paced by its writes and reads, which suspend until the audio
 * worklet has room or has samples.
 */
@Suppress("UNUSED_PARAMETER")
fun startAudioLoop(name: String, urgent: Boolean = true, body: suspend () -> Unit): AudioLoop {
    audioScope.launch { body() }
    // A loop told to stop ends at its next write or read; nothing can wait for it here.
    return AudioLoop {}
}

/** One thread, so nothing to lock against. */
inline fun <T> locked(@Suppress("UNUSED_PARAMETER") lock: Any, block: () -> T): T = block()
