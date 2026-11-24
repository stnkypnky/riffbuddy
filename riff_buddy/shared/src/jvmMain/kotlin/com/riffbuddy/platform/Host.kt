package com.riffbuddy.platform

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okio.FileSystem

/*
 * The few facts about the machine that shared code needs and the browser
 * answers differently. The web build defines the same names for itself.
 */

/**
 * Where work that blocks a thread goes: the database, files, the network.
 * On the JVM that is the IO pool; shared code names this rather than
 * Dispatchers.IO because the browser has no such pool.
 */
val Dispatchers.Blocking: CoroutineDispatcher get() = IO

/** Whether this is a Mac, whose shortcuts use ⌘ rather than Ctrl. */
val hostIsMac: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

/** The HTTP engine under the shared client: OkHttp, as on any JVM. */
val httpEngine: HttpClientEngineFactory<*> = OkHttp

/**
 * A field read and written from more than one thread. Shared code takes it
 * from here: the common Volatile is refused by the browser build, which has
 * one thread and needs none.
 */
typealias Volatile = kotlin.jvm.Volatile

/** Where the app's own files live: the device's disk. */
val appFileSystem: FileSystem = FileSystem.SYSTEM

/** A monotonic clock in nanoseconds: the one audio timestamps are given in. */
fun nanoTime(): Long = System.nanoTime()

/** An audio loop started by [startAudioLoop]. */
fun interface AudioLoop {
    /** Waits up to [timeoutMs] for the loop to finish, once it has been told to stop. */
    fun join(timeoutMs: Long)
}

/**
 * Runs an engine's render or capture loop. Here it gets a thread of its own,
 * at high priority when [urgent], so a busy screen can't starve the speaker.
 * The loop suspends only in name: the writes and reads under it block this
 * thread, which is what paces it.
 */
fun startAudioLoop(name: String, urgent: Boolean = true, body: suspend () -> Unit): AudioLoop {
    val thread = Thread({ runBlocking { body() } }, name)
    if (urgent) thread.priority = Thread.MAX_PRIORITY
    thread.start()
    return AudioLoop { thread.join(it) }
}

/** [block] while holding [lock], against the audio thread. */
inline fun <T> locked(lock: Any, block: () -> T): T = synchronized(lock, block)
