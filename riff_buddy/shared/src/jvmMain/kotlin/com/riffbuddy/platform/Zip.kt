package com.riffbuddy.platform

import okio.BufferedSink
import okio.Closeable
import okio.Path
import okio.Source
import okio.buffer
import okio.sink
import okio.source
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/*
 * Zip files, which backups and sync are. Java's zip classes do the work here;
 * the browser build has its own versions of these two classes.
 */

/** Writes a zip to [out], one entry after another, never holding more than one in memory. */
class ZipWriter(out: BufferedSink) : Closeable {
    private val zip = ZipOutputStream(out.outputStream())

    fun entry(name: String, write: (BufferedSink) -> Unit) {
        zip.putNextEntry(ZipEntry(name))
        // Flushed, not closed: closing it would close the whole zip.
        zip.sink().buffer().also(write).flush()
        zip.closeEntry()
    }

    override fun close() = zip.close()
}

/** Reads a zip file the app holds, entry by entry. */
class ZipReader(path: Path) : Closeable {
    private val zip = ZipFile(path.toFile())

    val names: List<String> get() = zip.entries().asSequence().map { it.name }.toList()

    /** The entry's bytes as a stream, or null if there is no such entry. */
    fun open(name: String): Source? = zip.getEntry(name)?.let { zip.getInputStream(it).source() }

    override fun close() = zip.close()
}
