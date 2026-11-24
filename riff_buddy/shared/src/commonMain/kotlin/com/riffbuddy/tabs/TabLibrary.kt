package com.riffbuddy.tabs

import com.riffbuddy.platform.delete
import com.riffbuddy.platform.isFile
import com.riffbuddy.platform.list
import com.riffbuddy.platform.mkdirs
import com.riffbuddy.platform.readBytes
import com.riffbuddy.platform.renameTo
import com.riffbuddy.platform.writeBytes
import kotlin.uuid.Uuid
import okio.ByteString.Companion.toByteString
import okio.IOException
import okio.Path

/**
 * A tab file that has been read and judged, but not yet stored anywhere.
 *
 * The import is split in two on purpose. [TabLibrary.prepare] decides
 * everything — is it a supported name, is it too big, does it parse, what is it
 * called — while touching nothing. Only a [Prepared] can then be written. That
 * ordering is what makes "a corrupt file shows a clear error and changes
 * nothing" true: nothing can change, because the only step that writes is
 * unreachable from a failure.
 */
class Prepared(
    val bytes: ByteArray,
    val originalName: String,
    val displayName: String,
    val storedName: String,
    val sha256: String,
    val summary: TabSummary,
)

/**
 * The file half of the tab library: judging files, and keeping their bytes in
 * app storage. Pure apart from the file system, so tested.
 *
 * Files are copied in rather than referenced because a picked file is a loan,
 * not a gift — Android can revoke a content:// grant on reboot, and a
 * Downloads file tidied away would take its library entry with it.
 */
class TabLibrary(private val dir: Path) {

    fun prepare(bytes: ByteArray, originalName: String): Result<Prepared> {
        val name = originalName.trim().ifBlank { "untitled" }
        if (!TabParser.isSupportedName(name)) {
            val ext = name.substringAfterLast('.', "").ifBlank { null }
            return Result.failure(
                TabFileException(
                    (if (ext != null) "A .$ext file is not a tab. " else "That file has no extension. ") +
                        "Riff Buddy opens Guitar Pro (.gp, .gp3–.gp5, .gpx), MusicXML (.musicxml, " +
                        ".xml, .mxl) and alphaTex (.alphatex) files."
                )
            )
        }
        if (bytes.size > MAX_BYTES) {
            return Result.failure(
                TabFileException(
                    "$name is ${bytes.size / 1_000_000} MB. Tab files are almost always under 1 MB, " +
                        "so this is probably not one — or it has audio inside it."
                )
            )
        }
        return TabParser.parse(bytes, name).map { summary ->
            val ext = name.substringAfterLast('.').lowercase()
            Prepared(
                bytes = bytes,
                originalName = name,
                displayName = displayName(summary, name),
                // A random stored name, never the original: two files called
                // "solo.gp5" from two different songs must not collide, and an
                // original name can contain anything a filesystem dislikes.
                storedName = "${Uuid.random()}.$ext",
                sha256 = sha256(bytes),
                summary = summary,
            )
        }
    }

    /**
     * Writes a prepared file, atomically: to a temporary name, then renamed. A
     * crash halfway leaves a stray `.part` file, never a half-written tab that
     * the library believes is whole.
     */
    fun write(prepared: Prepared): Path {
        dir.mkdirs()
        val target = dir / prepared.storedName
        val partial = dir / "${prepared.storedName}.part"
        partial.writeBytes(prepared.bytes)
        if (!partial.renameTo(target)) {
            partial.delete()
            throw IOException("Could not store ${prepared.originalName}")
        }
        return target
    }

    fun read(storedName: String): ByteArray = (dir / storedName).readBytes()

    fun exists(storedName: String): Boolean = (dir / storedName).isFile

    fun delete(storedName: String) {
        (dir / storedName).delete()
    }

    /** Leftovers from an import that died between write and rename. */
    fun sweepPartials() {
        dir.list().filter { it.name.endsWith(".part") }.forEach { it.delete() }
    }

    companion object {
        /**
         * Generous. A dense six-track Guitar Pro file is a few hundred KB; the
         * cap exists so a mis-picked video fails with a sentence instead of an
         * out-of-memory crash on a 2 GB tablet.
         */
        const val MAX_BYTES = 20 * 1024 * 1024

        /** The song's own title when the file has one, else the file name. */
        fun displayName(summary: TabSummary, originalName: String): String =
            summary.title.ifBlank { originalName.substringBeforeLast('.') }.trim().take(120)

        fun sha256(bytes: ByteArray): String =
            bytes.toByteString().sha256().hex()
    }
}
