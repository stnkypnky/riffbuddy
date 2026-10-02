package com.riffbuddy

import com.riffbuddy.ai.AiCoach
import com.riffbuddy.ai.OnlineCheck
import com.riffbuddy.audio.AudioInputs
import com.riffbuddy.audio.AudioOutputs
import com.riffbuddy.audio.LooperEngine
import com.riffbuddy.audio.MetronomeEngine
import com.riffbuddy.audio.PhrasePlayer
import com.riffbuddy.audio.RecorderEngine
import com.riffbuddy.audio.ReferenceTone
import com.riffbuddy.audio.TunerEngine
import com.riffbuddy.data.AiCacheRepo
import com.riffbuddy.data.AudioRepo
import com.riffbuddy.data.BackingRepo
import com.riffbuddy.data.BackupFormat
import com.riffbuddy.data.BackupRepo
import com.riffbuddy.data.ChatRepo
import com.riffbuddy.data.Db
import com.riffbuddy.data.GearRepo
import com.riffbuddy.data.MilestoneRepo
import com.riffbuddy.data.PartRepo
import com.riffbuddy.data.PlanRepo
import com.riffbuddy.data.PracticeController
import com.riffbuddy.data.PresetRepo
import com.riffbuddy.data.RecordingRepo
import com.riffbuddy.data.Repo
import com.riffbuddy.data.RoutineController
import com.riffbuddy.data.SecureStore
import com.riffbuddy.data.SessionRepo
import com.riffbuddy.data.SongRepo
import com.riffbuddy.data.TabRepo
import com.riffbuddy.data.ToneCardRepo
import com.riffbuddy.data.TuningRepo
import com.riffbuddy.tabs.TabLibrary
import java.io.File

/**
 * Every repository and engine the app runs on, wired once.
 *
 * Hand-rolled rather than a DI framework: a handful of singletons doesn't
 * justify the machinery. Each platform hands over the few things only it can
 * make — the database, sound in and out, the key store, a connectivity check
 * and two folders — and everything else is the same on every platform.
 *
 * Everything is `by lazy`, so the database is opened when something first
 * reads from it rather than during startup, where it would count against a
 * cold start.
 *
 * The engines are single instances for the whole app: each holds an audio
 * stream or the microphone, and a second one started by a recomposition would
 * play over the first or fight it for the input.
 */
class AppServices(
    private val db: Db,
    val audioOutputs: AudioOutputs,
    private val audioInputs: AudioInputs,
    val keys: SecureStore,
    isOnline: OnlineCheck,
    filesDir: File,
    cacheDir: File,
    appVersion: String,
) {
    val repo by lazy { Repo(db) }
    val presets by lazy { PresetRepo(db) }
    val songs by lazy { SongRepo(db) }
    val sessions by lazy { SessionRepo(db) }
    val parts by lazy { PartRepo(db) }
    val milestones by lazy { MilestoneRepo(db) }

    /** One running session for the whole app, so the quick bar can show it from any tab. */
    val practice by lazy { PracticeController(sessions) }

    val gear by lazy { GearRepo(db) }
    val toneCards by lazy { ToneCardRepo(db) }

    /** One folder, shared by the library and the backup, so they cannot disagree. */
    private val tabsDir = File(filesDir, "tabs")
    private val recordingsDir = File(filesDir, "recordings")

    /**
     * Imported tab files. The bytes live in the app's own storage, so they need
     * no permission and go with the app — which is why the backup carries them.
     */
    val tabs by lazy { TabRepo(db, TabLibrary(tabsDir).also { it.sweepPartials() }) }

    /** Looper recordings (references) and saved loops for both players. */
    val audio by lazy { AudioRepo(db) }
    val looper by lazy { LooperEngine(audioOutputs) }

    /** Lessons, homework and routines. */
    val plan by lazy { PlanRepo(db) }
    val tunings by lazy { TuningRepo(db) }
    val chat by lazy { ChatRepo(db) }

    /** A running routine, app-wide like the practice session it logs into. */
    val routineRunner by lazy { RoutineController(practice, audioOutputs) }

    /** Saved backing-track recipes. */
    val backing by lazy { BackingRepo(db) }

    /** Takes, the same arrangement as tab files. */
    val recordings by lazy { RecordingRepo(db, recordingsDir) }
    val recorder by lazy { RecorderEngine(audioInputs) }

    val backup by lazy {
        BackupRepo(
            db,
            appVersion = appVersion,
            fileDirs = mapOf(
                BackupFormat.TABS_FOLDER to tabsDir,
                BackupFormat.RECORDINGS_FOLDER to recordingsDir,
            ),
            cacheDir = cacheDir,
        )
    }

    private val aiCache by lazy { AiCacheRepo(db) }
    val coach by lazy { AiCoach(keys = keys, cache = aiCache, isOnline = isOnline) }

    val metronome by lazy { MetronomeEngine(audioOutputs) }
    val tuner by lazy { TunerEngine(audioInputs) }
    val referenceTone by lazy { ReferenceTone(audioOutputs) }
    val phrasePlayer by lazy { PhrasePlayer(audioOutputs) }

    /** Silences everything that might still be sounding, as the app goes away. */
    fun stopAll() {
        metronome.stop()
        tuner.stop()
        referenceTone.stop()
        phrasePlayer.stop()
    }
}
