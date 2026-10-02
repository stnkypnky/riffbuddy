package com.riffbuddy.audio

import com.riffbuddy.domain.music.Phrase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.concurrent.thread

/**
 * Plays a [Phrase]: a scale position, a chord, a progression.
 *
 * One phrase at a time. Starting another stops the first, so tapping Play on
 * a second chord moves on instead of piling the two up.
 *
 * The phrase is rendered in full before it plays (a few hundred ms of work on
 * a slow tablet for a 12-bar blues) and then streamed. The screen asks
 * [positionMs] where the speaker has got to, which is the playback head
 * rather than a timer, so the dot that lights is the note being heard.
 */
class PhrasePlayer(private val outputs: AudioOutputs, private val sampleRate: Int = 48_000) {

    /** What is playing, by the key the screen gave it; null when nothing is. */
    data class Playing(val key: String, val phrase: Phrase)

    private val _playing = MutableStateFlow<Playing?>(null)
    val playing: StateFlow<Playing?> = _playing

    @Volatile private var track: PcmOutput? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var generation = 0

    fun play(key: String, phrase: Phrase, a4Hz: Double) {
        stop()
        val mine = ++generation
        _playing.value = Playing(key, phrase)
        worker = thread(name = "phrase-player") {
            runCatching { stream(PluckSynth.render(phrase, sampleRate, a4Hz), mine) }
            // Only the phrase that is still current clears the state; a stopped
            // one finishing late must not wipe out the one that replaced it.
            if (generation == mine) _playing.value = null
        }
    }

    fun stop() {
        generation++
        runCatching { track?.pause(); track?.flush() }
        worker?.join(300)
        worker = null
        _playing.value = null
    }

    /** Where playback has reached, in ms from the phrase's start. */
    fun positionMs(): Long {
        val t = track ?: return 0
        return runCatching { t.playbackHeadPosition * 1000 / sampleRate }.getOrDefault(0)
    }

    private fun stream(pcm: ShortArray, mine: Int) {
        if (generation != mine) return
        val t = outputs.open(sampleRate, StreamKind.MUSIC) { maxOf(it, 8192) }
        track = t
        try {
            t.play()
            var written = 0
            while (written < pcm.size && generation == mine) {
                val n = t.write(pcm, written, minOf(CHUNK, pcm.size - written))
                if (n <= 0) break
                written += n
            }
            // Let the buffer play out: write() returns as soon as the last
            // chunk is queued, a buffer's length before it is heard.
            while (generation == mine && t.playbackHeadPosition < written) Thread.sleep(20)
            runCatching { t.stop() }
        } finally {
            if (track === t) track = null
            t.release()
        }
    }

    private companion object {
        const val CHUNK = 2048
    }
}
