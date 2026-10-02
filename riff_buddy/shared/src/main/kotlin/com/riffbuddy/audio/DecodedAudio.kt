package com.riffbuddy.audio

/** A song ready for the looper: the samples, the outline to draw, and its length. */
class DecodedAudio(val pcm: Pcm, val peaks: FloatArray, val truncated: Boolean) {
    val durationMs: Long get() = pcm.frameToMs(pcm.frames.toDouble())
}

/** A file the platform could not turn into sound. The message is written for the player to read. */
class AudioFileException(message: String, cause: Throwable? = null) : Exception(message, cause)
