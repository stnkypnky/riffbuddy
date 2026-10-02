package com.riffbuddy.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor

/**
 * The microphone as a guitarist needs it: raw, with Android's speech
 * processing switched off. Shared by the tuner (M5) and the recorder (M10),
 * which need exactly the same thing for the same reason.
 *
 * **Why the processing must be off.** Automatic gain control, noise suppression
 * and echo cancellation are all built for speech. A sustained guitar note looks
 * to them like noise to be attenuated — AGC will fade a held note while it is
 * still ringing. For the tuner that makes readings wrong; for the recorder it
 * would make every take a lie about how the player played (names all three).
 *
 * One instance per opening; [release] gives the microphone back, and must be
 * called — a recorder left holding it blocks every other app (AC).
 */
class RawMicrophone(private val context: Context) {

    private val effects = mutableListOf<AudioEffect>()
    var record: AudioRecord? = null
        private set

    /**
     * Caller must already hold RECORD_AUDIO. [preferred] routes capture to a
     * particular input — the amp's headphone output, say — instead of the phone's
     * own microphone.
     */
    fun open(sampleRate: Int, encoding: Int, bufferBytes: Int, preferred: AudioDeviceInfo? = null): AudioRecord {
        val min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, encoding)
        check(min > 0) { "This device will not record $sampleRate Hz mono" }
        /*
         * UNPROCESSED is the one that means what it says — raw from the mic.
         * Not every device offers it, so VOICE_RECOGNITION is the fallback:
         * the speech source least likely to have AGC applied, and what every
         * other Android tuner ends up using.
         */
        val source = if (unprocessedSupported()) MediaRecorder.AudioSource.UNPROCESSED
        else MediaRecorder.AudioSource.VOICE_RECOGNITION

        @Suppress("MissingPermission")
        val r = AudioRecord(source, sampleRate, AudioFormat.CHANNEL_IN_MONO, encoding, maxOf(min, bufferBytes))
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            error("The microphone could not be opened")
        }
        if (preferred != null) r.setPreferredDevice(preferred)
        record = r
        disableVoiceProcessing(r.audioSessionId)
        return r
    }

    fun release() {
        effects.forEach { runCatching { it.release() } }
        effects.clear()
        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null
    }

    /**
     * Turns off the three speech processors where the device exposes them.
     * An unprocessed source usually has none attached, but some manufacturers
     * attach them anyway; asking directly closes that gap.
     */
    private fun disableVoiceProcessing(sessionId: Int) {
        runCatching {
            if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(sessionId)?.also { effects += it }?.enabled = false
        }
        runCatching {
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(sessionId)?.also { effects += it }?.enabled = false
        }
        runCatching {
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(sessionId)?.also { effects += it }?.enabled = false
        }
    }

    private fun unprocessedSupported(): Boolean = runCatching {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
    }.getOrDefault(false)
}
