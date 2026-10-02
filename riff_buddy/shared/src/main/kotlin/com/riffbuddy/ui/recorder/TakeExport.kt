package com.riffbuddy.ui.recorder

import com.riffbuddy.audio.Wav
import com.riffbuddy.data.RecordingRepo
import com.riffbuddy.domain.Recording
import com.riffbuddy.platform.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** How a take leaves the app, whether shared or saved. */
enum class ExportFormat(val label: String, val detail: String, val extension: String, val mime: String) {
    M4A("M4A", "Small — about 1 MB a minute. Plays almost anywhere.", "m4a", "audio/mp4"),
    WAV("WAV", "The original, uncompressed — about 6 MB a minute.", "wav", "audio/wav"),
}

/**
 * A copy of [take] in [format], named after the take, ready to share or save.
 *
 * Always a copy, even for WAV: the stored file is named by a UUID, and a
 * friend receiving "3f2c…wav" has no idea what it is. The copies live in the
 * cache under `exports/`, the only cache folder Android's file sharing exposes, and
 * the folder is emptied first so exports never pile up.
 */
suspend fun exportTake(platform: Platform, repo: RecordingRepo, take: Recording, format: ExportFormat): File =
    withContext(Dispatchers.IO) {
        val dir = File(platform.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "${safeName(take.name)}.${format.extension}")
        when (format) {
            ExportFormat.WAV -> repo.fileFor(take).copyTo(out, overwrite = true)
            ExportFormat.M4A -> platform.encodeM4a(Wav.read(repo.fileFor(take)), out)
        }
        out
    }

/** A take's name as a file name: letters, digits, spaces and a little punctuation. */
fun safeName(name: String): String =
    name.replace(Regex("""[^\p{L}\p{N} ._()-]"""), "_").trim().take(80).ifBlank { "Take" }
