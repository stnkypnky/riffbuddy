package com.riffbuddy.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import com.riffbuddy.data.ChatRepo
import com.riffbuddy.data.MilestoneRepo
import com.riffbuddy.data.PartRepo
import com.riffbuddy.domain.SongPart
import com.riffbuddy.domain.SongStatus
import com.riffbuddy.domain.minutesAndSeconds
import com.riffbuddy.domain.tally
import com.riffbuddy.platform.Blocking
import com.riffbuddy.platform.mkdirs
import com.riffbuddy.platform.readBytes
import com.riffbuddy.platform.size
import com.riffbuddy.ui.help.HelpActions
import com.riffbuddy.ui.help.LocalHelpActions
import com.riffbuddy.ui.theme.Entrance
import com.riffbuddy.ui.theme.screenEntrance
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.use

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.ripple
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.riffbuddy.ui.theme.Elevation
import com.riffbuddy.ui.theme.Radius
import com.riffbuddy.ui.theme.Space
import com.riffbuddy.ui.theme.Widths
import com.riffbuddy.ui.theme.pressScale
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.indication
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import com.riffbuddy.ui.tactile.FilterChip
import androidx.compose.material3.Icon
import com.riffbuddy.ui.tactile.FilterChip
import com.riffbuddy.ui.tactile.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.riffbuddy.ui.tactile.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riffbuddy.ai.AiCoach
import com.riffbuddy.ai.AiResult
import com.riffbuddy.ai.ModelCatalog
import com.riffbuddy.ai.ProviderConfig
import com.riffbuddy.ai.GeneratedCard
import com.riffbuddy.ai.ToneCardGenerator
import com.riffbuddy.ui.tone.CoachTab
import com.riffbuddy.ai.ProviderId
import com.riffbuddy.audio.MetronomeEngine
import com.riffbuddy.audio.PhrasePlayer
import com.riffbuddy.audio.ReferenceTone
import com.riffbuddy.audio.TunerEngine
import com.riffbuddy.ui.tuner.Tool
import com.riffbuddy.ui.tuner.TunerHost
import com.riffbuddy.data.GearRepo
import com.riffbuddy.data.Rig
import com.riffbuddy.data.BackupRepo
import com.riffbuddy.data.SavedToneCard
import com.riffbuddy.data.SecureStore
import com.riffbuddy.data.ToneCardRepo
import com.riffbuddy.ai.SimilarSong
import com.riffbuddy.ui.settings.BackupController
import com.riffbuddy.ui.log.SessionEdits
import com.riffbuddy.ui.gear.GearHost
import com.riffbuddy.ui.onboarding.AboutYouCard
import com.riffbuddy.ui.onboarding.Onboarding
import com.riffbuddy.domain.PlayerProfile
import com.riffbuddy.ui.tuner.TunerPreset
import com.riffbuddy.ui.settings.MeScreen
import com.riffbuddy.ui.settings.AiSettingsSection
import com.riffbuddy.audio.TrainerRun
import com.riffbuddy.audio.TimeSignature
import com.riffbuddy.audio.MetronomeSettings
import com.riffbuddy.ui.metronome.MetronomeSongHeader
import com.riffbuddy.ui.settings.PracticeSection
import com.riffbuddy.domain.PracticeSettings
import com.riffbuddy.ui.settings.rememberBackupCallbacks
import com.riffbuddy.ui.songs.Assist
import com.riffbuddy.ui.songs.AssistState
import com.riffbuddy.ui.settings.ProviderState
import com.riffbuddy.data.MetronomePreset
import com.riffbuddy.data.PracticeController
import com.riffbuddy.data.PresetRepo
import com.riffbuddy.data.Repo
import com.riffbuddy.data.SessionRepo
import com.riffbuddy.data.SongRepo
import com.riffbuddy.domain.Session
import com.riffbuddy.domain.SessionItem
import com.riffbuddy.domain.SessionMode
import com.riffbuddy.domain.Song
import com.riffbuddy.domain.Songsterr
import com.riffbuddy.domain.Stats
import com.riffbuddy.domain.music.Tuning
import com.riffbuddy.domain.music.Notes
import com.riffbuddy.domain.music.ChordFavorites
import com.riffbuddy.ui.theory.TheoryScreen
import com.riffbuddy.ui.tabs.TabLibraryScreen
import com.riffbuddy.ui.tabs.TabPlayerScreen
import com.riffbuddy.data.TabRepo
import com.riffbuddy.data.AudioRepo
import com.riffbuddy.data.RecordingRepo
import com.riffbuddy.data.BackingRepo
import com.riffbuddy.data.CustomShapeRepo
import com.riffbuddy.domain.music.CustomShape
import com.riffbuddy.data.TuningRepo
import com.riffbuddy.data.PlanRepo
import com.riffbuddy.ui.settings.ReminderSection
import com.riffbuddy.data.RoutineController
import com.riffbuddy.domain.Homework
import com.riffbuddy.domain.Lesson
import com.riffbuddy.domain.Routine
import com.riffbuddy.ui.plan.LessonsScreen
import com.riffbuddy.ui.plan.RoutinesScreen
import com.riffbuddy.ui.plan.TodayPlanCard
import com.riffbuddy.data.SavedBacking
import com.riffbuddy.domain.backing.BackingSpec
import com.riffbuddy.domain.backing.BackingStyle
import com.riffbuddy.domain.backing.Chart
import com.riffbuddy.ui.backing.BackingScreen
import com.riffbuddy.audio.RecorderEngine
import com.riffbuddy.audio.TakePlayer
import com.riffbuddy.audio.CalibrationRun
import com.riffbuddy.domain.Recording
import com.riffbuddy.ui.recorder.LatencyInfo
import com.riffbuddy.ui.recorder.PlayAlong
import com.riffbuddy.ui.recorder.RecorderScreen
import com.riffbuddy.ui.recorder.TakesPlayerScreen
import com.riffbuddy.audio.LooperEngine
import com.riffbuddy.audio.LoopTrainer
import com.riffbuddy.domain.AudioFile
import com.riffbuddy.domain.SavedLoop
import com.riffbuddy.ui.looper.LooperLibraryScreen
import com.riffbuddy.ui.looper.LooperScreen
import com.riffbuddy.domain.TabFile
import kotlinx.coroutines.NonCancellable
import com.riffbuddy.ui.metronome.MetronomeScreen
import com.riffbuddy.ui.songs.SongPage
import com.riffbuddy.ui.songs.SongsScreen
import com.riffbuddy.ui.theme.RiffColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Instant
import com.riffbuddy.domain.localZone
import kotlin.time.Clock

private enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Filled.Home),
    SONGS("Songs", Icons.Filled.LibraryMusic),
    TOOLS("Tools", Icons.Filled.Tune),
    COACH("Coach", Icons.Filled.AutoAwesome),
    ME("Me", Icons.Filled.Person),
}

/** The tabs whose top level can use a wide window: cards in a grid, charts in two columns, chats beside the chat. */
private val WIDE_TABS = setOf(Tab.HOME, Tab.COACH, Tab.ME)

/**
 * What the app keeps clear of: the status and navigation bars, and a camera
 * cutout. Not the keyboard, which each text field's screen deals with.
 */
private val APP_INSETS: WindowInsets
    @Composable get() = WindowInsets.systemBars.union(WindowInsets.displayCutout)

/** Recordings a dropped file may be; the looper says plainly if this computer can't read one. */
private val DROPPED_AUDIO = setOf("wav", "wave", "aif", "aiff", "mp3", "m4a", "aac", "ogg", "flac", "opus")

@Composable
fun RiffBuddyShell(
    backupRepo: BackupRepo,
    settingsRepo: Repo,
    toneCardRepo: ToneCardRepo,
    tunerEngine: TunerEngine,
    referenceTone: ReferenceTone,
    phrasePlayer: PhrasePlayer,
    engine: MetronomeEngine,
    presetRepo: PresetRepo,
    songRepo: SongRepo,
    sessionRepo: SessionRepo,
    practice: PracticeController,
    gearRepo: GearRepo,
    keys: SecureStore,
    coach: AiCoach,
    tabRepo: TabRepo,
    audioRepo: AudioRepo,
    looper: LooperEngine,
    recordingRepo: RecordingRepo,
    recorder: RecorderEngine,
    backingRepo: BackingRepo,
    planRepo: PlanRepo,
    tuningRepo: TuningRepo,
    chatRepo: ChatRepo,
    routineRunner: RoutineController,
    partRepo: PartRepo,
    milestoneRepo: MilestoneRepo,
    customShapeRepo: CustomShapeRepo,
    syncSection: @Composable () -> Unit,
    /** Goes up each time another device changes this one's data. */
    dataChanges: () -> Int,
) {
    // Saveable, not just remembered: a rotation, or a lock screen that forces
    // its own orientation, recreates the screen, and plain remember would put
    // the player back on the metronome with whatever they had open closed.
    // Some tablets' lock screens do exactly that on every wake.
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var openSongId by rememberSaveable { mutableStateOf<Long?>(null) }
    var tool by rememberSaveable { mutableStateOf(Tool.METRONOME) }
    /** The song the metronome was opened from, until it is let go of. */
    var metronomeSongId by rememberSaveable { mutableStateOf<Long?>(null) }
    /** A song's tuning, waiting for the tuner to open. */
    var tunerPreset by remember { mutableStateOf<TunerPreset?>(null) }
    var theoryTuning by remember { mutableStateOf(Tuning.STANDARD) }
    var endingSession by remember { mutableStateOf<Session?>(null) }

    val metronome by engine.state.collectAsStateWithLifecycle()
    val practiceState by practice.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var toolsFolded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { toolsFolded = settingsRepo.setting(TOOLS_FOLDED) == "1" }

    var songs by remember { mutableStateOf(emptyList<Song>()) }
    /** Built-ins and the player's own, as stored (so each has its id). */
    var tunings by remember { mutableStateOf(Tuning.BUILT_IN) }
    /** Favorite chord voicings. */
    var chordFavorites by remember { mutableStateOf(emptySet<String>()) }
    /** Practice and audio settings, read once at launch. */
    var practiceSettings by remember { mutableStateOf(PracticeSettings()) }
    /** "About you", which every AI prompt reads. */
    var player by remember { mutableStateOf(PlayerProfile()) }
    var showOnboarding by rememberSaveable { mutableStateOf(false) }
    var sessions by remember { mutableStateOf(emptyList<Session>()) }
    var presets by remember { mutableStateOf(emptyList<MetronomePreset>()) }
    var rig by remember { mutableStateOf<Rig?>(null) }
    var toneCards by remember { mutableStateOf(emptyList<SavedToneCard>()) }
    var tabFiles by remember { mutableStateOf(emptyList<TabFile>()) }
    /** The file the player has open, with its bytes already read. */
    var openTab by remember { mutableStateOf<Pair<TabFile, ByteArray>?>(null) }
    var missingTab by remember { mutableStateOf<TabFile?>(null) }
    var audioFiles by remember { mutableStateOf(emptyList<AudioFile>()) }
    var tabLoops by remember { mutableStateOf(emptyList<SavedLoop>()) }
    var takes by remember { mutableStateOf(emptyList<Recording>()) }
    /** Every song's parts, for progress bars and stats. */
    var parts by remember { mutableStateOf(emptyList<SongPart>()) }
    /** When each milestone was earned, for the Stats tab's badges. */
    var milestoneDates by remember { mutableStateOf(emptyMap<String, Instant>()) }
    /** Bumped when Home's "This week" card asks for Me › Stats. */
    var openStats by remember { mutableStateOf(0) }
    /** Where Me opens when another screen sends the player there; cleared by the tab bar. */
    var meStart by remember { mutableStateOf<com.riffbuddy.ui.settings.MeSection?>(null) }
    /** One take to play, or two to compare. */
    var openTakes by remember { mutableStateOf<List<Recording>?>(null) }
    var latency by remember { mutableStateOf(LatencyInfo(0, 0, null)) }
    var recorderGain by remember { mutableStateOf(mapOf(false to 0f, true to 0f)) }
    var recorderCountdown by remember { mutableStateOf(0) }
    var coachLength by remember { mutableStateOf(com.riffbuddy.ai.prompts.AskPrompt.Length.STANDARD) }
    var coachWeb by remember { mutableStateOf(false) }
    var savedBackings by remember { mutableStateOf(emptyList<SavedBacking>()) }
    var customShapes by remember { mutableStateOf(emptyList<CustomShape>()) }
    var lessons by remember { mutableStateOf(emptyList<Lesson>()) }
    var homework by remember { mutableStateOf(emptyList<Homework>()) }
    var routines by remember { mutableStateOf(emptyList<Routine>()) }
    var selectedRoutineId by remember { mutableStateOf<Long?>(null) }
    /** "lessons" or "routines" when one of the plan screens is open. */
    var planScreen by rememberSaveable { mutableStateOf<String?>(null) }
    /** A question from the helper, waiting for the Coach tab to take it. */
    var coachQuestion by remember { mutableStateOf<String?>(null) }
    val routineRun by routineRunner.run.collectAsStateWithLifecycle()
    val routineRemaining by routineRunner.remaining.collectAsStateWithLifecycle()
    /** What the backing builder opens with, and which saved track it is editing. */
    var backingStart by remember { mutableStateOf(BackingSpec()) }
    var backingId by remember { mutableStateOf<Long?>(null) }

    fun openBacking(spec: BackingSpec, id: Long?) {
        backingStart = spec
        backingId = id
        openSongId = null
        tab = Tab.TOOLS
        tool = Tool.BACKING
    }
    val platform = com.riffbuddy.platform.LocalPlatform.current
    val takePlayer = remember { TakePlayer(platform.audioOutputs) }
    val playAlong = remember(engine, looper) {
        PlayAlong(engine, looper) { looper.loadedId?.let { id -> audioFiles.firstOrNull { it.id == id }?.name } }
    }
    var openAudio by remember { mutableStateOf<AudioFile?>(null) }
    var lastSavedCardId by remember { mutableStateOf<Long?>(null) }
    var providers by remember { mutableStateOf(emptyList<ProviderState>()) }
    var primaryProvider by remember { mutableStateOf(ProviderId.GROQ) }
    /** Paid providers are tried after the free ones only when the player turns this on. */
    var paidFallback by remember { mutableStateOf(false) }
    var chosenModels by remember {
        mutableStateOf(ProviderId.entries.associateWith { ProviderConfig.defaultFor(it).model })
    }

    // The chosen model has to reach the coach, not just the settings screen —
    // otherwise picking one changes a chip and nothing else.
    val configFor: (ProviderId) -> ProviderConfig = { id ->
        ProviderConfig.forModel(id, chosenModels[id])
    }
    LaunchedEffect(chosenModels) { coach.useConfig(configFor) }
    // Same reason, for the other half of the choice: without this the radio
    // button on Settings moves and every request still starts at Gemini.
    LaunchedEffect(primaryProvider) { coach.usePrimary { primaryProvider } }
    LaunchedEffect(paidFallback) { coach.usePaidFallback { paidFallback } }
    val generator = remember(coach) { ToneCardGenerator(coach) }
    LaunchedEffect(generator, player) { generator.player = player }
    val backup = remember(backupRepo) { BackupController(backupRepo, settingsRepo) }
    val backupFiles = rememberBackupCallbacks(backup)

    suspend fun reloadProviders() {
        val usage = coach.usage().associateBy { it.provider }
        providers = ProviderId.entries.map { id ->
            val existing = providers.firstOrNull { it.id == id }
            ProviderState(
                id = id,
                hint = keys.hint(id),
                model = chosenModels[id] ?: ProviderConfig.defaultFor(id).model,
                models = existing?.models.orEmpty(),
                loadingModels = existing?.loadingModels ?: false,
                modelsError = existing?.modelsError,
                usage = usage.getValue(id),
                testing = existing?.testing ?: false,
                testResult = existing?.testResult,
                testPassed = existing?.testPassed ?: false,
            )
        }
    }

    /**
     * Records newly earned milestones and celebrates them. The first
     * time it runs, everything already earned is recorded silently, so months
     * of history don't all go off at once. A milestone recorded but never shown
     * (the app closed first) is shown next time.
     */
    suspend fun checkMilestones() {
        val recorded = milestoneRepo.earned()
        val all = withContext(Dispatchers.Default) {
            com.riffbuddy.domain.StatsSnapshot.of(
                com.riffbuddy.domain.StatsRange.ALL, localZone(), Clock.System.now(),
                sessions, songs, parts, tunings,
                takeTimes = takes.map { it.createdAt }, toneCardTimes = toneCards.map { it.createdAt },
                streakMinutes = practiceSettings.streakMinutes,
            )
        }
        val fresh = com.riffbuddy.domain.Milestones.newlyEarned(all, recorded.keys)
        val seeded = settingsRepo.setting(MILESTONES_SEEDED) == "1"
        if (!seeded) {
            milestoneRepo.earn(fresh.map { it.key }, celebrated = true)
            settingsRepo.putSetting(MILESTONES_SEEDED, "1")
        } else {
            // Milestones added to the catalog since this install last
            // looked are recorded quietly if history already earned them.
            val known = settingsRepo.setting(MILESTONES_KNOWN)?.split(',')?.toSet()
                ?: com.riffbuddy.domain.Milestones.FIRST_CATALOG
            val celebrate = com.riffbuddy.domain.Milestones.toCelebrate(fresh, known)
            milestoneRepo.earn((fresh - celebrate.toSet()).map { it.key }, celebrated = true)
            milestoneRepo.earn(celebrate.map { it.key }, celebrated = false)
            val toShow = recorded.filterValues { !it }.keys + celebrate.map { it.key }
            com.riffbuddy.domain.Milestones.ALL.filter { it.key in toShow }.forEach { m ->
                val alreadyQueued = com.riffbuddy.ui.Celebrations.queue.any {
                    it is com.riffbuddy.ui.Celebration.MilestoneEarned && it.milestone.key == m.key
                }
                if (!alreadyQueued) {
                    com.riffbuddy.ui.Celebrations.show(
                        com.riffbuddy.ui.Celebration.MilestoneEarned(m) { scope.launch { milestoneRepo.markCelebrated(m.key) } }
                    )
                }
            }
        }
        settingsRepo.putSetting(MILESTONES_KNOWN, com.riffbuddy.domain.Milestones.ALL.joinToString(",") { it.key })
        milestoneDates = milestoneRepo.earnedAt()
    }

    suspend fun reload() {
        songs = songRepo.all()
        sessions = sessionRepo.all()
        presets = presetRepo.all()
        rig = gearRepo.defaultRig()
        backup.refresh()
        toneCards = songs.flatMap { toneCardRepo.forSong(it.id) }
        tabFiles = tabRepo.all()
        audioFiles = audioRepo.all()
        takes = recordingRepo.all()
        parts = partRepo.all()
        savedBackings = backingRepo.all()
        customShapes = customShapeRepo.all()
        lessons = planRepo.lessons()
        tunings = tuningRepo.all()
        homework = planRepo.homework()
        routines = planRepo.routines()
        selectedRoutineId = settingsRepo.setting(PlanRepo.SELECTED_ROUTINE)?.toLongOrNull()?.takeIf { id -> routines.any { it.id == id } }
        reloadProviders()
        checkMilestones()
    }


    /** A take into the looper ("Loop it"), from the recorder or a song page. */
    fun loopTake(take: Recording) {
        scope.launch {
            val f = recordingRepo.fileFor(take)
            val id = audioRepo.add(platform.refOf(f), take.name + ".wav", f.size(), take.songId)
            audioRepo.rename(id, take.name)
            reload()
            openAudio = audioRepo.byId(id)
        }
    }

    fun openTabFile(file: TabFile) {
        scope.launch {
            val bytes = tabRepo.bytes(file)
            if (bytes != null) {
                tabLoops = audioRepo.loopsForTab(file.id)
                openTab = file to bytes
            } else {
                missingTab = file
            }
        }
    }

    var dropProblem by remember { mutableStateOf<String?>(null) }

    /**
     * A file dropped on the window goes where its kind belongs: a tab opens in
     * the player, a recording in the looper, a backup in the import dialog.
     * Whatever was open closes first, so what arrives is what shows.
     */
    fun openDropped(file: com.riffbuddy.platform.PickedFile) {
        val name = file.name ?: file.ref.substringAfterLast('/')
        val ext = name.substringAfterLast('.', "").lowercase()
        fun closeAll() {
            planScreen = null; openTakes = null; openTab = null; openAudio = null; openSongId = null
        }
        scope.launch {
            when (ext) {
                com.riffbuddy.data.BackupFormat.FILE_EXTENSION -> {
                    closeAll()
                    tab = Tab.ME
                    runCatching { platform.openInput(file.ref) }.getOrNull()?.use { backup.inspect(it) }
                        ?: run { dropProblem = "\"$name\" could not be opened." }
                }
                in com.riffbuddy.tabs.TabParser.EXTENSIONS -> {
                    val bytes = withContext(Dispatchers.Blocking) {
                        runCatching { platform.openInput(file.ref).readBytes() }
                    }
                    bytes.fold({ tabRepo.import(it, name) }, { Result.failure(it) }).fold(
                        onSuccess = { imported ->
                            closeAll()
                            tab = Tab.TOOLS; tool = Tool.TABS
                            reload()
                            openTabFile(imported.file)
                        },
                        onFailure = { dropProblem = it.message ?: "\"$name\" could not be added." },
                    )
                }
                in DROPPED_AUDIO -> {
                    val id = audioRepo.add(file.ref, name, file.size ?: 0)
                    closeAll()
                    tab = Tab.TOOLS; tool = Tool.LOOPER
                    reload()
                    openAudio = audioRepo.byId(id)
                }
                else -> dropProblem = "Riff Buddy opens tab files, recordings and its own backups, " +
                    "and \"$name\" isn't one of those."
            }
        }
    }
    LaunchedEffect(platform) { platform.droppedFiles.collect { openDropped(it) } }
    /**
     * The AI choices are read back before anything else uses them.
     *
     * Held in `remember` alone, a chosen model (say gemma-4-26b) would be
     * forgotten on the next launch, which would silently go back to the built-in
     * defaults — a different model answering, at a different daily limit, with
     * nothing on screen to say so. They belong in the settings table with
     * everything else that has to outlive the process.
     */
    // Another device synced with this one: show what it brought.
    val changes = dataChanges()
    LaunchedEffect(changes) {
        if (changes > 0) { reload(); backup.refresh() }
    }

    LaunchedEffect(Unit) {
        // The one reminder alarm can be lost to a force-stop; opening the app
        // puts it back from the saved settings.
        platform.rearmReminders()
        // A take cut off by the app being killed is mended before anything
        // lists it: its WAV header gets its real length back.
        recordingRepo.repairAll()
        latency = LatencyInfo(
            offsetMs = settingsRepo.setting(Repo.LATENCY_OFFSET_MS)?.toIntOrNull() ?: 0,
            spreadMs = settingsRepo.setting(Repo.LATENCY_SPREAD_MS)?.toIntOrNull() ?: 0,
            calibratedAt = settingsRepo.setting(Repo.LATENCY_CALIBRATED_AT)?.toLongOrNull()?.let(Instant::fromEpochMilliseconds),
        )
        recorderCountdown = settingsRepo.setting(Repo.RECORDER_COUNTDOWN)?.toIntOrNull() ?: 0
        coachLength = com.riffbuddy.ai.prompts.AskPrompt.Length.fromName(settingsRepo.setting(Repo.COACH_LENGTH))
        coachWeb = settingsRepo.setting(Repo.COACH_WEB) == "true"
        recorderGain = mapOf(
            false to (settingsRepo.setting(Repo.RECORDER_GAIN_MIC)?.toFloatOrNull() ?: 0f),
            true to (settingsRepo.setting(Repo.RECORDER_GAIN_LINE)?.toFloatOrNull() ?: 0f),
        )
        chosenModels = ProviderId.entries.associateWith { id ->
            settingsRepo.setting(Repo.aiModelKey(id.name))
                ?: ProviderConfig.defaultFor(id).model
        }
        primaryProvider = settingsRepo.setting(Repo.AI_PRIMARY_PROVIDER)
            ?.let { saved -> ProviderId.entries.firstOrNull { it.name == saved } }
            // With no choice saved, the first free provider that has a key:
            // a paid key alone is never put first without the player choosing it.
            ?: com.riffbuddy.ai.AiPolicy.defaultPrimary(keys.providersWithKeys())
        paidFallback = settingsRepo.setting(Repo.AI_PAID_FALLBACK) == "true"
        tuningRepo.seed()
        chordFavorites = ChordFavorites.parse(settingsRepo.setting(ChordFavorites.SETTING))
        val stored = PracticeSettings.KEYS.associateWith { settingsRepo.setting(it) }
        practiceSettings = PracticeSettings.fromStored { stored[it] }
        val storedPlayer = PlayerProfile.KEYS.associateWith { settingsRepo.setting(it) }
        player = PlayerProfile.fromStored { storedPlayer[it] }
        reload()
        // Setup is for a first run: an install that already has songs or practice is
        // not a first run, and should not meet a setup screen after an update.
        if (settingsRepo.setting(PlayerProfile.ONBOARDED) == null) {
            if (songs.isNotEmpty() || sessions.isNotEmpty()) settingsRepo.putSetting(PlayerProfile.ONBOARDED, "true")
            else showOnboarding = true
        }
        practice.checkForRecoverable()
    }

    /** Tab only or notation too — one setting for every player, saved at once. */
    fun setTabOnly(on: Boolean) {
        practiceSettings = practiceSettings.copy(tabOnly = on)
        scope.launch { settingsRepo.putSetting(PracticeSettings.TAB_ONLY, on.toString()) }
    }

    // Changing the A4 reference updates the tuner immediately — the engine
    // reads this on its next frame, so no restart is involved.
    LaunchedEffect(practiceSettings.a4Hz) { tunerEngine.a4 = practiceSettings.a4Hz.toDouble() }

    // Back closes the song page first, then goes up a level, and asks before
    // abandoning a running session.
    // A finished routine has just written its session: show it.
    LaunchedEffect(routineRun?.finished) { if (routineRun?.finished == true) { kotlinx.coroutines.delay(500); reload() } }

    // The AI settings' actions, named so that Me › Settings and the first-run
    // AI step run the same code rather than two copies of it.
    val onSaveKeyAction: (ProviderId, String) -> Unit = { id, key ->
        scope.launch { keys.put(id, key); reloadProviders() }
    }
    val onRemoveKeyAction: (ProviderId) -> Unit = { id ->
        scope.launch { keys.remove(id); reloadProviders() }
    }
    val onSetPrimaryAction: (ProviderId) -> Unit = { picked ->
        primaryProvider = picked
        scope.launch {
            settingsRepo.putSetting(Repo.AI_PRIMARY_PROVIDER, picked.name)
        }
    }
    val onPickModelAction: (ProviderId, String) -> Unit = { id, model ->
        chosenModels = chosenModels + (id to model)
        scope.launch {
            settingsRepo.putSetting(Repo.aiModelKey(id.name), model)
            reloadProviders()
        }
    }
    val onPaidFallbackAction: (Boolean) -> Unit = { on ->
        paidFallback = on
        scope.launch { settingsRepo.putSetting(Repo.AI_PAID_FALLBACK, on.toString()) }
    }
    val onRefreshModelsAction: (ProviderId) -> Unit = { id ->
        scope.launch {
            providers = providers.map {
                if (it.id == id) it.copy(loadingModels = true, modelsError = null) else it
            }
            val key = keys.get(id)
            val outcome = if (key == null) {
                Result.failure(IllegalStateException("No key stored"))
            } else {
                ModelCatalog.list(configFor(id), key)
            }
            providers = providers.map {
                if (it.id != id) it else it.copy(
                    loadingModels = false,
                    models = outcome.getOrDefault(emptyList()),
                    modelsError = outcome.exceptionOrNull()?.message,
                )
            }
        }
    }
    val onTestAction: (ProviderId) -> Unit = { id ->
        scope.launch {
            providers = providers.map {
                if (it.id == id) it.copy(testing = true, testResult = null) else it
            }
            val test = coach.testKey(id)
            val result = test.result
            // Says what actually happened rather than a
            // bare pass/fail: "rate limited" means the key
            // is fine and the tier is busy, which is a
            // different thing to do about than a bad key.
            val (message, ok) = when (result) {
                is AiResult.Success ->
                    // Says which of the two things was proven. A model
                    // that answers but ignores the schema is usable —
                    // its shape just gets checked on this end — and
                    // that is worth knowing before a tone card, not after.
                    (if (test.honorsSchema)
                        "Answered in ${result.elapsedMs} ms, and honors the schema"
                    else
                        "Answered in ${result.elapsedMs} ms, but ignored the schema — " +
                            "usable, shape checked here") to true
                is AiResult.RateLimited ->
                    ("Key works, but the free tier is busy" +
                        (result.retryAfterSeconds?.let { s -> " — try in ${s}s" } ?: "")) to true
                is AiResult.Malformed -> "Answered, but oddly — usable" to true
                is AiResult.Failed -> (result.message) to false
                AiResult.NoKey -> "No key stored" to false
                AiResult.Offline -> "No network" to false
            }
            providers = providers.map {
                if (it.id == id) it.copy(
                    testing = false, testResult = message, testPassed = ok,
                ) else it
            }
        }
    }

    /** Key setup and Test connection, as one piece both places show. */
    val aiSettings: @Composable () -> Unit = {
        AiSettingsSection(
            providers, primaryProvider, onSaveKeyAction, onRemoveKeyAction, onTestAction,
            onSetPrimaryAction, onPickModelAction, onRefreshModelsAction,
        )
    }

    fun setPlayer(changed: PlayerProfile) {
        player = changed
        scope.launch { changed.toStored().forEach { (k, v) -> settingsRepo.putSetting(k, v) } }
    }

    var confirmLeave by remember { mutableStateOf(false) }
    platform.BackHandler(
        enabled = planScreen != null || openTab != null || openAudio != null || openTakes != null ||
            openSongId != null || tab != Tab.HOME || practiceState.isRunning,
    ) {
        when {
            planScreen != null -> planScreen = null
            openTakes != null -> openTakes = null
            openTab != null -> openTab = null
            openAudio != null -> openAudio = null
            openSongId != null -> openSongId = null
            tab != Tab.HOME -> tab = Tab.HOME
            // At the top with a session running: back would leave the app, and
            // the player may well have meant "end the session". Ask.
            else -> confirmLeave = true
        }
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("A session is running") },
            text = {
                Text(
                    "Leaving keeps it timing in the background, and it will be here when you come " +
                        "back. Or end it now and log it.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmLeave = false; endingSession = practiceState.session }) { Text("End session") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmLeave = false }) { Text("Stay") }
                    // Backgrounded, not finished: the task keeps its state, so
                    // the running session and any open screen are as they were.
                    TextButton(onClick = { confirmLeave = false; platform.leaveApp() }) { Text("Leave") }
                }
            },
        )
    }

    // On stop, the start BPM, reached BPM and duration are offered to the
    // session log. A run is watched from here rather than inside the
    // engine: it ends three ways (Stop trainer, stopping the metronome, or the
    // ramp stopping itself at the target) and all three look the same from
    // the state — the trainer is no longer playing.
    var trainerRun by remember { mutableStateOf<TrainerRun?>(null) }
    var offeredRun by remember { mutableStateOf<Pair<TrainerRun, Instant>?>(null) }
    LaunchedEffect(metronome.trainer?.currentBpm, metronome.trainer != null, metronome.isRunning) {
        val t = metronome.trainer
        val run = trainerRun
        if (t != null && metronome.isRunning) {
            trainerRun = run?.seen(t.currentBpm)
                ?: TrainerRun(t.config.startBpm, t.currentBpm, Clock.System.now(), metronomeSongId)
        } else if (run != null) {
            trainerRun = null
            val now = Clock.System.now()
            if (run.worthOffering(now)) offeredRun = run to now
        }
    }
    offeredRun?.let { (run, endedAt) ->
        val songTitle = songs.firstOrNull { it.id == run.songId }?.title
        AlertDialog(
            onDismissRequest = { offeredRun = null },
            title = { Text("Log this run?") },
            text = {
                Text(
                    "Speed trainer${songTitle?.let { " on $it" } ?: ""}: ${run.startBpm} → ${run.reachedBpm} BPM, " +
                        "${run.seconds(endedAt) / 60} min ${run.seconds(endedAt) % 60} s.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    offeredRun = null
                    scope.launch {
                        // As the loop trainer does: addItem needs a session,
                        // so one is started rather than the run being lost.
                        // Started when the run started, so the session is at
                        // least as long as the run it holds.
                        if (!practice.state.value.isRunning) {
                            practice.start(if (run.songId != null) SessionMode.SONG else SessionMode.FREE, run.startedAt)
                        }
                        practice.addItem(run.toItem(endedAt))
                        reload()
                    }
                }) { Text("Add to today's log") }
            },
            dismissButton = { TextButton(onClick = { offeredRun = null }) { Text("No thanks") } },
        )
    }

    KeepScreenOn(practiceSettings.keepAwake && (metronome.isRunning || practiceState.isRunning))

    // The microphone is released the moment the tuner is not on screen. A tuner
    // left listening in the background would block every other app that wants
    // the mic, and would keep the radio warm for nothing.
    LaunchedEffect(tab, tool, openSongId) {
        if (tab != Tab.TOOLS || tool != Tool.TUNER || openSongId != null) {
            tunerEngine.stop()
            referenceTone.stop()
        }
        // A scale run or progression belongs to the Theory screen; leaving
        // it mid-phrase shouldn't leave it playing over the next screen.
        if (tab != Tab.TOOLS || tool != Tool.THEORY || openSongId != null) phrasePlayer.stop()
    }

    LaunchedEffect(metronome.isRunning) {
        while (metronome.isRunning) {
            engine.syncToPlayback()
            delay(16)
        }
    }

    // Every "?" below reads this, so the helper can hand a question
    // to the Coach without a callback threaded through every screen.
    // The tab bar, the rail and the quick bar all go to the top of a tab, so
    // they close whatever page is open over it. They used to set `tab` alone,
    // and the tab player, looper, takes and plan pages outrank `tab` in the
    // screen choice below — so from inside a tab the menu did nothing.
    // Closing a player is safe: each saves its place when it leaves.
    fun goToTab(t: Tab) {
        planScreen = null; openTakes = null; openTab = null; openAudio = null; openSongId = null
        meStart = null
        tab = t
    }
    // Space belongs to whichever screen is showing, so the metronome has a
    // key of its own that works everywhere: playing along to the looper or a
    // tab with a click is common, and M reaches it without leaving either.
    val shortcuts = LocalShortcuts.current
    KeyShortcuts(
        "Anywhere",
        Shortcut("${ShortcutRegistry.primaryLabel}1 – 5", "Home, Songs, Tools, Coach, Me",
            listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five), primary = true) {
            goToTab(Tab.entries[listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five).indexOf(key)])
        },
        Shortcut("M", "Start or stop the metronome", listOf(Key.M)) {
            if (metronome.isRunning) engine.stop() else engine.start(metronome.settings)
        },
        Shortcut("?", "Show these shortcuts", listOf(Key.Slash, Key.F1)) { shortcuts?.showingList = true },
        appWide = true,
    )
    val helpActions = remember {
        HelpActions(onAskCoach = { question ->
            coachQuestion = question
            goToTab(Tab.COACH)
        })
    }
    val width = widthClass()
    val compact = width == WidthClass.COMPACT
    val openSong = songs.firstOrNull { it.id == openSongId }
    // The song page and the songs list each have one call, shared by the
    // single-pane layout and the side-by-side one, so the two never drift apart.
    val songPage: @Composable (Song) -> Unit = { openSong ->
        SongPageHost(
            generator = generator,
            songs = songs,
            song = openSong,
            sessions = sessions,
            toneCards = toneCards.filter { it.songId == openSong.id },
            onStarCard = { id ->
                scope.launch { toneCardRepo.star(id); reload() }
            },
            practice = practice,
            songRepo = songRepo,
            parts = parts.filter { it.songId == openSong.id },
            partRepo = partRepo,
            onBack = { openSongId = null },
            onReload = { scope.launch { reload() } },
            onOpenMetronome = { bpm, timeSignature ->
                // The song's tempo and time signature. A
                // new signature brings its own accents; an odd
                // or blank one leaves the metronome's alone.
                val current = metronome.settings
                val ts = TimeSignature.parse(timeSignature)
                engine.update(
                    current.copy(
                        bpm = bpm.coerceIn(MetronomeSettings.BPM_RANGE),
                        timeSignature = ts ?: current.timeSignature,
                        accents = if (ts != null && ts != current.timeSignature) emptyList() else current.accents,
                    )
                )
                metronomeSongId = openSong.id
                tab = Tab.TOOLS
                tool = Tool.METRONOME
                openSongId = null
            },
            tunings = tunings,
            onOpenTuner = { tuningId, capo ->
                tunerPreset = TunerPreset(tuningId, capo)
                tab = Tab.TOOLS
                tool = Tool.TUNER
                openSongId = null
            },
            tabFiles = tabFiles.filter { it.songId == openSong.id },
            onOpenTab = ::openTabFile,
            audioFiles = audioFiles.filter { it.songId == openSong.id },
            onOpenAudio = { openAudio = it },
            backings = savedBackings.filter { it.songId == openSong.id },
            onOpenBacking = { openBacking(it.spec, it.id) },
            takes = takes.filter { it.songId == openSong.id },
            onPlayTake = { openTakes = listOf(it) },
            onLoopTake = ::loopTake,
        )
    }
    val songsList: @Composable () -> Unit = {
        SongsScreen(
            songs = songs,
            // The same sharing rule the Stats tab uses, so a
            // tagged session counts here too and the two agree.
            practiceSeconds = run {
                val now = Clock.System.now()
                val out = mutableMapOf<Long, Long>()
                sessions.filter { !it.isOpen }.forEach { s ->
                    com.riffbuddy.domain.StatsSnapshot.attribute(s, now).forEach { (id, secs) -> out.tally(id, secs) }
                }
                out
            },
            progress = parts.groupBy { it.songId }.mapNotNull { (id, ps) ->
                com.riffbuddy.domain.Mastery.progress(ps)?.let { id to it }
            }.toMap(),
            lastPracticed = Stats.songTotals(sessions)
                .mapNotNull { total -> total.lastPracticed?.let { total.songId to it } }
                .toMap(),
            onOpenSong = { openSongId = it.id },
            onAddSong = { title, artist ->
                scope.launch {
                    songRepo.save(Song(title = title, artist = artist))
                    reload()
                }
            },
            tunings = tunings,
        )
    }
    // On a wide screen the Songs tab shows the list and the open song side by
    // side, so going from one song to the next is one tap rather than back and
    // forward. Pages that cover the whole tab (a tab file, a recording) still
    // cover it; with no songs yet there is nothing to put beside the list.
    // It waits for a little more than the expanded width: beside the wide
    // rail and the list, an 840 dp window would leave the song page a strip.
    val twoPane = windowWidth() >= TWO_PANE_FROM && tab == Tab.SONGS && songs.isNotEmpty() &&
        planScreen == null && openTakes == null && openTab == null && openAudio == null
    val quickBarShowing = metronome.isRunning || practiceState.session != null
    val quickBar: @Composable (Boolean) -> Unit = { floating ->
        QuickBar(
            metronomeRunning = metronome.isRunning,
            bpm = metronome.displayBpm,
            beatInBar = metronome.beatInBar,
            session = practiceState.session,
            onOpenMetronome = { goToTab(Tab.TOOLS); tool = Tool.METRONOME },
            onOpenTuner = { goToTab(Tab.TOOLS); tool = Tool.TUNER },
            onStopMetronome = engine::stop,
            onEndSession = { endingSession = practiceState.session },
            onPauseSession = {
                scope.launch {
                    if (practiceState.session?.isPaused == true) practice.resume() else practice.pause()
                }
            },
            floating = floating,
        )
    }
    CompositionLocalProvider(LocalHelpActions provides helpActions) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Row(Modifier.fillMaxSize()) {
                if (!compact) BrandRail(selected = tab, expanded = width == WidthClass.EXPANDED, onPick = ::goToTab)

                // Most screens are text and cap their width for readability. The
                // fretboard is the exception: it is a picture of a neck, and on a
                // tablet in landscape the whole point of the big screen is seeing
                // the shape at once rather than dragging it sideways.
                val wantsFullWidth = openTab != null || openAudio != null || openTakes != null ||
                    (tab == Tab.TOOLS && (tool == Tool.THEORY || tool == Tool.TABS || tool == Tool.BACKING) && openSongId == null)
                // The tools' chip row belongs to the Tools tab, not to each tool:
                // drawn here, it stays put while the tool below it changes.
                val toolsShowing = tab == Tab.TOOLS && planScreen == null && openTakes == null &&
                    openTab == null && openAudio == null && openSong == null
                // A tab's top level gets the wide width on a big screen, where its
                // cards can sit in a grid or two columns. A page opened from a
                // list is reading, and stays one comfortable column.
                val tabTop = planScreen == null && openTakes == null && openTab == null &&
                    openAudio == null && openSong == null
                val maxWidth = when {
                    wantsFullWidth -> Dp.Unspecified
                    twoPane -> Widths.wide
                    width == WidthClass.EXPANDED && tabTop && tab in WIDE_TABS -> Widths.wide
                    // The metronome lays its controls out in two columns when given room.
                    width == WidthClass.EXPANDED && tabTop && tab == Tab.TOOLS && tool == Tool.METRONOME -> Widths.wide
                    else -> Widths.reading
                }
                Column(
                    Modifier.weight(1f).fillMaxHeight()
                        .windowInsetsPadding(
                            APP_INSETS.only(
                                if (compact) WindowInsetsSides.Top + WindowInsetsSides.Horizontal
                                else WindowInsetsSides.Top + WindowInsetsSides.End
                            )
                        ),
                ) {
                if (toolsShowing) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        // A desktop-wide window has room for all seven chips on one
                        // line; anything narrower keeps the reading width and wraps.
                        val chipsWidth = if (width == WidthClass.EXPANDED) Widths.wide else Widths.reading
                        Box(Modifier.widthIn(max = chipsWidth)) {
                            // Only a phone-width screen folds them: wider ones fit
                            // the chips on a line or two without crowding the tool.
                            com.riffbuddy.ui.ToolChips(
                                current = tool,
                                onPick = { tool = it },
                                collapsible = compact,
                                collapsed = toolsFolded,
                                onCollapsedChange = { folded ->
                                    toolsFolded = folded
                                    scope.launch { settingsRepo.putSetting(TOOLS_FOLDED, if (folded) "1" else "0") }
                                },
                            )
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
                    Box(
                        Modifier
                            .then(if (maxWidth == Dp.Unspecified) Modifier else Modifier.widthIn(max = maxWidth))
                            .fillMaxSize()
                    ) {
                        val playing = openTab
                        val listening = openTakes

                        val screenKey = when {
                            // The list stays where it is as songs open beside it;
                            // only the song pane moves.
                            twoPane -> "tab:SONGS"
                            planScreen != null -> "plan:$planScreen"
                            listening != null -> "takes"
                            playing != null -> "tab-player"
                            openAudio != null -> "looper-file"
                            openSong != null -> "song"
                            tab == Tab.TOOLS -> "tools:${tool.name}"
                            else -> "tab:${tab.name}"
                        }
                        val depth = if (screenKey.startsWith("tab:") || screenKey.startsWith("tools:")) 0 else 1
                        val last = remember { object { var key = screenKey; var depth = depth } }
                        val entrance = remember(screenKey) {
                            val bothTools = last.key.startsWith("tools:") && screenKey.startsWith("tools:")
                            val e = when {
                                depth > last.depth -> Entrance.FORWARD
                                depth < last.depth -> Entrance.BACK
                                bothTools -> Entrance.SOFT
                                else -> Entrance.SWITCH
                            }
                            last.key = screenKey; last.depth = depth
                            e
                        }
                        Box(Modifier.fillMaxSize().screenEntrance(screenKey, entrance)) {
                        when {
                            twoPane -> SongsTwoPane(openSong, songsList, songPage)

                            planScreen == "lessons" -> LessonsScreen(
                                lessons = lessons, homework = homework, songs = songs,
                                onBack = { planScreen = null },
                                onSaveLesson = { lesson, items -> scope.launch { planRepo.saveLesson(lesson, items); reload() } },
                                onDeleteLesson = { lesson -> scope.launch { planRepo.deleteLesson(lesson.id); reload() } },
                                onToggleDone = { item, done -> scope.launch { planRepo.setDone(item.id, done); reload() } },
                                onDeleteHomework = { item -> scope.launch { planRepo.deleteHomework(item.id); reload() } },
                            )

                            planScreen == "routines" -> RoutinesScreen(
                                routines = routines, selectedId = selectedRoutineId, songs = songs,
                                onBack = { planScreen = null },
                                onSelect = { id ->
                                    scope.launch {
                                        settingsRepo.putSetting(PlanRepo.SELECTED_ROUTINE, id?.toString() ?: "")
                                        reload()
                                    }
                                },
                                onSave = { r ->
                                    scope.launch {
                                        val id = planRepo.saveRoutine(r)
                                        // The first routine made is the one Today's plan uses.
                                        if (selectedRoutineId == null) settingsRepo.putSetting(PlanRepo.SELECTED_ROUTINE, id.toString())
                                        reload()
                                    }
                                },
                                onDelete = { r -> scope.launch { planRepo.deleteRoutine(r.id); reload() } },
                            )

                            listening != null -> TakesPlayerScreen(
                                takes = listening,
                                repo = recordingRepo,
                                player = takePlayer,
                                onBack = { openTakes = null },
                                onClipSaved = { scope.launch { reload() } },
                            )

                            playing != null -> TabPlayerScreen(
                                tabOnly = practiceSettings.tabOnly,
                                onTabOnly = ::setTabOnly,
                                file = playing.first,
                                bytes = playing.second,
                                onBack = { openTab = null },
                                onLeave = { memory ->
                                    // NonCancellable: this runs as the player is torn
                                    // down, sometimes as the whole activity is, and a
                                    // save canceled halfway is a position lost.
                                    scope.launch(NonCancellable) {
                                        tabRepo.savePlayback(
                                            playing.first.id, memory.positionMs, memory.speedPercent,
                                            memory.loopStartBar, memory.loopEndBar, memory.prefs,
                                        )
                                        tabFiles = tabRepo.all()
                                    }
                                },
                                savedLoops = tabLoops,
                                onSaveLoop = { name, start, end, speed ->
                                    scope.launch {
                                        audioRepo.saveLoop(
                                            SavedLoop(
                                                tabId = playing.first.id, name = name,
                                                start = start.toLong(), end = end.toLong(), speedPercent = speed,
                                            )
                                        )
                                        tabLoops = audioRepo.loopsForTab(playing.first.id)
                                    }
                                },
                                onDeleteLoop = { loop ->
                                    scope.launch {
                                        audioRepo.deleteLoop(loop.id)
                                        tabLoops = audioRepo.loopsForTab(playing.first.id)
                                    }
                                },
                            )

                            openAudio != null -> {
                                val audio = openAudio!!
                                LooperScreen(
                                    file = audio,
                                    engine = looper,
                                    repo = audioRepo,
                                    onBack = { openAudio = null },
                                    onLeave = { m ->
                                        scope.launch(NonCancellable) {
                                            audioRepo.savePlayback(
                                                audio.id, m.positionMs, m.speedPercent,
                                                m.loopStartMs, m.loopEndMs, m.volumePercent,
                                            )
                                            audioFiles = audioRepo.all()
                                        }
                                    },
                                    onLogTrainer = { name, seconds, startPct, endPct, loops ->
                                        scope.launch {
                                            // addItem does nothing with no session
                                            // running, so start one rather than let
                                            // "Add to today's log" quietly lose it.
                                            if (!practice.state.value.isRunning) {
                                                practice.start(if (audio.songId != null) SessionMode.SONG else SessionMode.FREE)
                                            }
                                            practice.addItem(
                                                LoopTrainer.logItem(
                                                    name, audio.songId, seconds, startPct, endPct, loops,
                                                    looper.state.value.bpm,
                                                )
                                            )
                                            reload()
                                        }
                                    },
                                    onChanged = { scope.launch { reload() } },
                                )
                            }

                            openSong != null -> songPage(openSong)

                            tab == Tab.HOME -> HomeScreen(
                                onOpenStats = { goToTab(Tab.ME); meStart = com.riffbuddy.ui.settings.MeSection.STATS },
                                onOpenTool = { goToTab(Tab.TOOLS); tool = it },
                                onOpenSongs = { goToTab(Tab.SONGS) },
                                onOpenCoach = { goToTab(Tab.COACH) },
                                sessions = sessions,
                                songs = songs,
                                running = practiceState.session,
                                weeklyGoalMinutes = practiceSettings.weeklyGoalMinutes,
                                streakMinutes = practiceSettings.streakMinutes,
                                onStart = {
                                    scope.launch { practice.start(SessionMode.FREE); reload() }
                                },
                                onEnd = { endingSession = practiceState.session },
                                onOpenSong = { openSongId = it.id; tab = Tab.SONGS },
                                todayPlan = {
                                    TodayPlanCard(
                                        homework = homework,
                                        songs = songs,
                                        routine = routines.firstOrNull { it.id == selectedRoutineId },
                                        run = routineRun,
                                        remainingSeconds = routineRemaining,
                                        onToggleDone = { item, done -> scope.launch { planRepo.setDone(item.id, done); reload() } },
                                        onStartRoutine = { routineRunner.start(it); scope.launch { kotlinx.coroutines.delay(300); reload() } },
                                        onNext = routineRunner::next,
                                        onSkip = routineRunner::skip,
                                        onPause = routineRunner::pause,
                                        onResume = routineRunner::resume,
                                        onStop = routineRunner::stop,
                                        onDismiss = routineRunner::dismiss,
                                        onOpenStep = { step ->
                                            when {
                                                step.songId != null -> { openSongId = step.songId; tab = Tab.SONGS }
                                                step.link.startsWith("scale:") -> { tab = Tab.TOOLS; tool = Tool.THEORY }
                                                step.link.startsWith("preset:") -> { tab = Tab.TOOLS; tool = Tool.METRONOME }
                                            }
                                        },
                                        onOpenLessons = { planScreen = "lessons" },
                                        onOpenRoutines = { planScreen = "routines" },
                                    )
                                },
                            )

                            tab == Tab.SONGS -> songsList()

                            tab == Tab.TOOLS && tool == Tool.THEORY -> Column(Modifier.fillMaxSize()) {
                                TheoryScreen(
                                    tuning = theoryTuning,
                                    onPickTuning = { theoryTuning = it },
                                    tunings = tunings,
                                    onPlayNote = { midi ->
                                        referenceTone.play(Notes.frequencyOf(midi, practiceSettings.a4Hz.toDouble()), 1_200)
                                    },
                                    phrasePlayer = phrasePlayer,
                                    a4Hz = practiceSettings.a4Hz.toDouble(),
                                    favorites = chordFavorites,
                                    customShapes = customShapes,
                                    // Saving hands back the id, so the shape on screen
                                    // becomes the saved one and a second save overwrites it.
                                    onSaveShape = { shape ->
                                        customShapeRepo.save(shape).also { customShapes = customShapeRepo.all() }
                                    },
                                    onDeleteShape = { shape ->
                                        scope.launch { customShapeRepo.delete(shape.id); customShapes = customShapeRepo.all() }
                                    },
                                    onToggleFavorite = { key ->
                                        chordFavorites = if (key in chordFavorites) chordFavorites - key else chordFavorites + key
                                        scope.launch { settingsRepo.putSetting(ChordFavorites.SETTING, ChordFavorites.format(chordFavorites)) }
                                    },
                                    // The Key helper offers "Jam in this key".
                                    onJam = { key ->
                                        val preset = if (key.isMinor) Chart.PRESETS.first { it.name == "i–VII–VI" }
                                        else Chart.PRESETS.first { it.name == "I–V–vi–IV" }
                                        openBacking(
                                            BackingSpec(
                                                keyTonic = key.tonic, minor = key.isMinor, chart = preset.chart,
                                                barsPerChord = preset.barsPerChord, style = BackingStyle.ROCK,
                                                tempo = BackingStyle.ROCK.defaultTempo,
                                            ),
                                            null,
                                        )
                                    },
                                )
                            }

                            tab == Tab.TOOLS && tool == Tool.TABS -> TabLibraryScreen(
                                header = {},
                                files = tabFiles,
                                songs = songs,
                                repo = tabRepo,
                                onOpen = ::openTabFile,
                                onChanged = { scope.launch { reload() } },
                            )

                            tab == Tab.TOOLS && tool == Tool.LOOPER -> LooperLibraryScreen(
                                header = {},
                                files = audioFiles,
                                songs = songs,
                                repo = audioRepo,
                                onOpen = { openAudio = it },
                                onChanged = { scope.launch { reload() } },
                            )

                            tab == Tab.TOOLS && tool == Tool.BACKING -> BackingScreen(
                                tabOnly = practiceSettings.tabOnly,
                                onTabOnly = ::setTabOnly,
                                header = {},
                                initial = backingStart,
                                savedId = backingId,
                                saved = savedBackings,
                                songs = songs,
                                tuning = theoryTuning,
                                onSave = { spec, songId, id ->
                                    scope.launch {
                                        backingId = backingRepo.save(spec, songId, id)
                                        reload()
                                    }
                                },
                                onOpenSaved = { openBacking(it.spec, it.id) },
                                onDelete = { track ->
                                    scope.launch {
                                        backingRepo.delete(track.id)
                                        if (backingId == track.id) backingId = null
                                        reload()
                                    }
                                },
                            )

                            tab == Tab.TOOLS && tool == Tool.RECORDER -> RecorderScreen(
                                header = {},
                                engine = recorder,
                                repo = recordingRepo,
                                takes = takes,
                                songs = songs,
                                sessionId = practiceState.session?.id,
                                latency = latency,
                                calibration = {
                                    // Nothing else may be playing: the calibration
                                    // listens for its own clicks and nothing else.
                                    engine.stop(); looper.pause()
                                    CalibrationRun(recorder, platform.cacheDir.mkdirs() / "calibration.wav", platform.audioOutputs)
                                },
                                onCalibrated = { result ->
                                    scope.launch {
                                        val now = Clock.System.now()
                                        settingsRepo.putSetting(Repo.LATENCY_OFFSET_MS, result.offsetMs.toString())
                                        settingsRepo.putSetting(Repo.LATENCY_SPREAD_MS, result.spreadMs.toString())
                                        settingsRepo.putSetting(Repo.LATENCY_CALIBRATED_AT, now.toEpochMilliseconds().toString())
                                        latency = LatencyInfo(result.offsetMs, result.spreadMs, now)
                                    }
                                },
                                playAlong = playAlong,
                                savedGain = recorderGain,
                                countdownSeconds = recorderCountdown,
                                onCountdown = { seconds ->
                                    recorderCountdown = seconds
                                    scope.launch { settingsRepo.putSetting(Repo.RECORDER_COUNTDOWN, seconds.toString()) }
                                },
                                onSaveGain = { line, db ->
                                    recorderGain = recorderGain + (line to db)
                                    scope.launch {
                                        settingsRepo.putSetting(if (line) Repo.RECORDER_GAIN_LINE else Repo.RECORDER_GAIN_MIC, db.toString())
                                    }
                                },
                                onPlay = { openTakes = listOf(it) },
                                onCompare = { a, b -> openTakes = listOf(a, b) },
                                onOpenInLooper = ::loopTake,
                                onChanged = { scope.launch { reload() } },
                            )

                            tab == Tab.TOOLS && tool == Tool.TUNER -> TunerHost(
                                engine = tunerEngine,
                                referenceTone = referenceTone,
                                tunings = tunings,
                                preset = tunerPreset,
                                onSaveCustom = { t -> tuningRepo.saveCustom(t).also { tunings = tuningRepo.all() } },
                                onDeleteCustom = { t -> scope.launch { tuningRepo.deleteCustom(t.id); reload() } },
                            )

                            tab == Tab.TOOLS -> Column(Modifier.fillMaxSize()) {
                                MetronomeScreen(
                                state = metronome,
                                onStart = { engine.start(metronome.settings) },
                                onStop = engine::stop,
                                onSettings = engine::update,
                                onStartTrainer = engine::startTrainer,
                                onStopTrainer = engine::stopTrainer,
                                onNailedIt = engine::nailedIt,
                                presets = presets,
                                onSavePreset = { name ->
                                    scope.launch { presetRepo.save(name, metronome.settings); reload() }
                                },
                                onDeletePreset = { preset ->
                                    scope.launch { presetRepo.delete(preset.id); reload() }
                                },
                                songHeader = {
                                    songs.firstOrNull { it.id == metronomeSongId }?.let { song ->
                                        MetronomeSongHeader(
                                            song = song,
                                            bpm = metronome.settings.bpm,
                                            onUpdateTarget = { bpm ->
                                                scope.launch { songRepo.save(song.copy(targetBpm = bpm)); reload() }
                                            },
                                            onRelease = { metronomeSongId = null },
                                        )
                                    }
                                },
                                )
                            }

                            tab == Tab.COACH -> CoachTab(
                                chatRepo = chatRepo,
                                songs = songs,
                                rig = rig,
                                generator = generator,
                                scope = scope,
                                pendingQuestion = coachQuestion,
                                onQuestionTaken = { coachQuestion = null },
                                sessions = sessions,
                                player = player,
                                isOnline = coach::online,
                                onOpenSettings = { goToTab(Tab.ME); meStart = com.riffbuddy.ui.settings.MeSection.SETTINGS },
                                onOpenGear = { goToTab(Tab.ME); meStart = com.riffbuddy.ui.settings.MeSection.GEAR },
                                onOpenSongs = { goToTab(Tab.SONGS) },
                                askSetup = run {
                                    // Who answers: the preferred provider with a key, then
                                    // the rest in order, as AiCoach tries them.
                                    val keyed = ProviderId.entries.filter { id -> providers.firstOrNull { it.id == id }?.hint != null }
                                    val order = com.riffbuddy.ai.AiPolicy.providerOrder(primaryProvider, keyed.toSet(), paidFallback)
                                    fun name(id: ProviderId) = "${id.label} · ${(chosenModels[id] ?: ProviderConfig.defaultFor(id).model).substringAfterLast('/')}"
                                    val searcher = com.riffbuddy.ai.WebSearch.PREFERENCE.firstOrNull { it in keyed && com.riffbuddy.ai.WebSearch.supports(it, chosenModels[it] ?: ProviderConfig.defaultFor(it).model) }
                                    com.riffbuddy.ui.tone.AskSetup(
                                        answering = order.firstOrNull()?.let(::name),
                                        searching = searcher?.let(::name),
                                        searchCost = searcher?.let(com.riffbuddy.ai.WebSearch::costNote).orEmpty(),
                                        switch = com.riffbuddy.ui.tone.ModelSwitch(
                                            choices = keyed.map { id ->
                                                val st = providers.first { it.id == id }
                                                com.riffbuddy.ui.tone.ModelChoice(
                                                    id = id,
                                                    model = chosenModels[id] ?: ProviderConfig.defaultFor(id).model,
                                                    models = st.models.map { it.id },
                                                    loading = st.loadingModels,
                                                    error = st.modelsError,
                                                )
                                            },
                                            current = order.firstOrNull() ?: primaryProvider,
                                            paidFallback = paidFallback,
                                            onPick = { id, model ->
                                                if (id != primaryProvider) onSetPrimaryAction(id)
                                                if (model != chosenModels[id]) onPickModelAction(id, model)
                                            },
                                            onRefresh = onRefreshModelsAction,
                                        ),
                                    )
                                },
                                length = coachLength,
                                onLength = { l ->
                                    coachLength = l
                                    scope.launch { settingsRepo.putSetting(Repo.COACH_LENGTH, l.name) }
                                },
                                webSearch = coachWeb,
                                onWebSearch = { on ->
                                    coachWeb = on
                                    scope.launch { settingsRepo.putSetting(Repo.COACH_WEB, on.toString()) }
                                },
                                onSaveCard = { song, part, generated ->
                                    scope.launch {
                                        lastSavedCardId = toneCardRepo.save(
                                            songId = song.id,
                                            rigId = rig?.id,
                                            part = part,
                                            generated = generated,
                                        )
                                        reload()
                                    }
                                },
                                onRateCard = { useful ->
                                    scope.launch {
                                        lastSavedCardId?.let { toneCardRepo.rate(it, useful) }
                                    }
                                },
                            )

                            tab == Tab.ME -> MeScreen(
                                openStats = openStats,
                                start = meStart,
                                paidFallback = paidFallback,
                                onPaidFallback = onPaidFallbackAction,
                                parts = parts,
                                stats = {
                                    com.riffbuddy.ui.stats.StatsScreen(
                                        sessions = sessions, songs = songs, parts = parts, tunings = tunings,
                                        takes = takes, toneCards = toneCards,
                                        streakMinutes = practiceSettings.streakMinutes,
                                        earnedAt = milestoneDates,
                                    )
                                },
                                sessions = sessions,
                                songs = songs,
                                rig = rig,
                                providers = providers,
                                primary = primaryProvider,
                                onSaveKey = onSaveKeyAction,
                                onRemoveKey = onRemoveKeyAction,
                                dataState = backup.state,
                                onExport = backupFiles.onExport,
                                onPickBackupFile = backupFiles.onImport,
                                onConfirmImport = { mode ->
                                    scope.launch { backup.applyImport(mode); reload() }
                                },
                                onCancelImport = backup::cancelImport,
                                reminders = { ReminderSection(settingsRepo) },
                                practice = practiceSettings,
                                gear = {
                                    GearHost(gearRepo, coach, tunings, onChanged = { scope.launch { reload() } })
                                },
                                practiceSection = {
                                    AboutYouCard(player, ::setPlayer, onRunSetup = { showOnboarding = true })
                                    PracticeSection(practiceSettings) { changed ->
                                        practiceSettings = changed
                                        scope.launch { changed.toStored().forEach { (k, v) -> settingsRepo.putSetting(k, v) } }
                                    }
                                },
                                sessionEdits = SessionEdits(
                                    save = { existing, start, seconds, rating, notes, tags ->
                                        scope.launch {
                                            val id = if (existing == null) sessionRepo.addManual(start, seconds, rating, notes)
                                            else { sessionRepo.update(existing.id, start, seconds, rating, notes); existing.id }
                                            if (tags != null) sessionRepo.replaceTags(id, tags)
                                            reload()
                                        }
                                    },
                                    delete = { s -> scope.launch { sessionRepo.discard(s.id); reload() } },
                                ),
                                onClearAll = {
                                    scope.launch {
                                        // A cleared install looks like a fresh one: no
                                        // gear until the player adds their own.
                                        backup.clearAll()
                                        reload()
                                    }
                                },
                                sync = syncSection,
                                onSetPrimary = onSetPrimaryAction,
                                onPickModel = onPickModelAction,
                                onRefreshModels = onRefreshModelsAction,
                                onTest = onTestAction,
                            )
                        }
                        }
                    }
                }
                // The quick bar belongs to the content, not under the rail: on a
                // phone it sits on the tab bar; wider, it floats as a card at the
                // foot of the content column. A tablet on a music stand is exactly
                // where a running session most needs to stay reachable.
                if (compact) {
                    if (quickBarShowing) quickBar(false)
                    else HorizontalHairline()
                    BottomTabs(selected = tab, onPick = ::goToTab)
                } else {
                    Box(
                        Modifier.fillMaxWidth().windowInsetsPadding(APP_INSETS.only(WindowInsetsSides.Bottom)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (quickBarShowing) quickBar(true)
                    }
                }
                }
            }
        }
    }

    if (showOnboarding) {
        Onboarding(
            player = player,
            onPlayer = ::setPlayer,
            gear = { GearHost(gearRepo, coach, tunings, onChanged = { scope.launch { reload() } }) },
            ai = aiSettings,
            onFinish = {
                showOnboarding = false
                tab = Tab.HOME
                scope.launch { settingsRepo.putSetting(PlayerProfile.ONBOARDED, "true"); reload() }
            },
        )
    }

    practiceState.recoverable?.let { open ->
        RecoverSessionDialog(
            session = open,
            onResume = { scope.launch { practice.resumeRecovered() } },
            onKeep = { scope.launch { practice.keepRecoveredAsIs(Clock.System.now()); reload() } },
            onDiscard = { scope.launch { practice.discardRecovered(); reload() } },
        )
    }

    // A library row whose bytes are gone — the one case the import's ordering
    // cannot rule out, since a restore could bring rows from a backup made
    // before files were carried in backups.
    dropProblem?.let { problem ->
        AlertDialog(
            onDismissRequest = { dropProblem = null },
            title = { Text("That file can't be opened") },
            text = { Text(problem) },
            confirmButton = { TextButton(onClick = { dropProblem = null }) { Text("OK") } },
        )
    }

    missingTab?.let { file ->
        AlertDialog(
            onDismissRequest = { missingTab = null },
            title = { Text("\"${file.name}\" is missing") },
            text = {
                Text(
                    "The library remembers this file, but its contents are no longer in " +
                        "Riff Buddy's storage. Remove the entry and import the file again.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    missingTab = null
                    scope.launch { tabRepo.delete(file); reload() }
                }) { Text("Remove entry") }
            },
            dismissButton = { TextButton(onClick = { missingTab = null }) { Text("Keep") } },
        )
    }

    endingSession?.let { session ->
        EndSessionSheet(
            session = session,
            onDismiss = { endingSession = null },
            onSave = { rating, notes, tags ->
                scope.launch {
                    val ended = practice.end(rating, notes)
                    if (tags.isNotEmpty() && ended != null) sessionRepo.replaceTags(ended.id, tags)
                    reload()
                }
                endingSession = null
            },
            tagSongs = com.riffbuddy.ui.log.tagCandidates(
                songs,
                recentSongIds = sessions.filter { it.startedAt > Clock.System.now() - 30.days }
                    .sortedByDescending { it.startedAt }.flatMap { s -> s.items.mapNotNull { it.songId } },
                alreadyTagged = session.items.mapNotNull { it.songId },
            ),
            tagParts = parts.groupBy { it.songId },
            onDiscard = {
                scope.launch { practice.discard(); reload() }
                endingSession = null
            },
        )
    }
}

/** Keeps the song page's callbacks in one place rather than inline in the shell. */
@Composable
private fun SongPageHost(
    parts: List<SongPart>,
    partRepo: PartRepo,
    generator: ToneCardGenerator,
    songs: List<Song>,
    song: Song,
    sessions: List<Session>,
    toneCards: List<SavedToneCard>,
    onStarCard: (Long) -> Unit,
    practice: PracticeController,
    songRepo: SongRepo,
    onBack: () -> Unit,
    onReload: () -> Unit,
    onOpenMetronome: (bpm: Int, timeSignature: String?) -> Unit,
    tunings: List<Tuning>,
    onOpenTuner: (tuningId: Long?, capo: Int) -> Unit,
    tabFiles: List<TabFile>,
    onOpenTab: (TabFile) -> Unit,
    audioFiles: List<AudioFile>,
    onOpenAudio: (AudioFile) -> Unit,
    backings: List<SavedBacking>,
    onOpenBacking: (SavedBacking) -> Unit,
    takes: List<Recording>,
    onPlayTake: (Recording) -> Unit,
    onLoopTake: (Recording) -> Unit,
) {
    val platform = com.riffbuddy.platform.LocalPlatform.current
    val scope = rememberCoroutineScope()

    var assistState by remember(song.id) { mutableStateOf<AssistState>(AssistState.Idle) }
    var activeAssist by remember(song.id) { mutableStateOf<Assist?>(null) }
    var difficultyRating by remember(song.id) { mutableStateOf<Int?>(null) }

    fun openUrl(url: String) = platform.openLink(url)

    /** A one-line sense of what the player already plays, for calibrating difficulty. */
    fun songbookSummary(): String = songs
        .filter { it.id != song.id && it.selfDifficulty != null }
        .sortedByDescending { it.selfDifficulty }
        .take(6)
        .joinToString(", ") { "${it.title} (${it.selfDifficulty}/5)" }
        .ifBlank { "nothing rated yet" }

    /**
     * [refresh] is "Ask again": drop whatever is cached for this question and
     * really ask. Without it a cached answer is permanent, which is fine until
     * the answer is useless — and one of them was.
     */
    fun runAssist(assist: Assist, feedback: String? = null, refresh: Boolean = false) {
        activeAssist = assist
        assistState = AssistState.Working
        scope.launch {
            val outcome: Result<AssistState> = when (assist) {
                Assist.SIMILAR -> generator.similarSongs(song, refresh = refresh).map {
                    AssistState.Similar(it.value, source(it.provider.label, it.model, it.fromCache))
                }
                Assist.DIFFICULTY -> generator.difficulty(
                    song = song,
                    songbookSummary = songbookSummary(),
                    previousRating = difficultyRating,
                    feedback = feedback,
                    refresh = refresh,
                ).map {
                    difficultyRating = it.value.rating
                    AssistState.Difficulty(it.value, source(it.provider.label, it.model, it.fromCache))
                }
                Assist.HOW_TO -> generator.howToPlay(song, refresh = refresh).map {
                    AssistState.HowTo(it.value, source(it.provider.label, it.model, it.fromCache))
                }
            }
            assistState = outcome.getOrElse { AssistState.Failed(it.message ?: "Something went wrong") }
            // Asking again spends a free-tier request, so the usage figures on
            // the Me tab need to catch up; onReload is what refreshes them.
            if (refresh) onReload()
        }
    }

    SongPage(
        song = song,
        sessions = sessions,
        onBack = onBack,
        // NonCancellable: "Tune to this" and "Open in the metronome" save and
        // then leave this page, which cancels this scope. The row was written
        // but the reload after it was not, so the song list kept the old song.
        onSave = { updated ->
            scope.launch(NonCancellable) {
                songRepo.save(updated)
                onReload()
                // Becoming Mastered is the big moment, however it happens.
                if (updated.status == SongStatus.MASTERED && song.status != SongStatus.MASTERED) {
                    com.riffbuddy.ui.Celebrations.show(
                        com.riffbuddy.ui.Celebration.SongMastered(updated.title, com.riffbuddy.domain.Journey.of(updated, sessions))
                    )
                }
            }
        },
        parts = parts,
        onAddPart = { name -> scope.launch { partRepo.add(song.id, name); onReload() } },
        onPartStage = { part, stage -> scope.launch { partRepo.setStage(part.id, stage); onReload() } },
        onRenamePart = { part, name -> scope.launch { partRepo.rename(part.id, name); onReload() } },
        onMovePart = { part, by ->
            scope.launch {
                val ids = parts.map { it.id }.toMutableList()
                val i = ids.indexOf(part.id)
                val j = (i + by).coerceIn(0, ids.lastIndex)
                if (i >= 0 && i != j) { ids.removeAt(i); ids.add(j, part.id); partRepo.reorder(ids); onReload() }
            }
        },
        onDeletePart = { part -> scope.launch { partRepo.delete(part.id); onReload() } },
        onDelete = { scope.launch { songRepo.delete(song.id); onReload(); onBack() } },
        // Still link-out only: handed to the platform, never shown in-app.
        onOpenSongsterr = { url -> platform.openLink(url) },
        onPractice = {
            scope.launch {
                practice.start(SessionMode.SONG)
                practice.addItem(SessionItem(songId = song.id))
                onReload()
            }
        },
        onOpenMetronome = onOpenMetronome,
        tunings = tunings,
        onOpenTuner = onOpenTuner,
        toneCards = toneCards,
        onStarCard = onStarCard,
        assistState = assistState,
        activeAssist = activeAssist,
        onRunAssist = { runAssist(it) },
        onAskAssistAgain = { runAssist(it, refresh = true) },
        onClearAssist = {
            activeAssist = null
            assistState = AssistState.Idle
        },
        onAddSuggestion = { suggestion ->
            scope.launch {
                songRepo.save(Song(title = suggestion.title, artist = suggestion.artist))
                onReload()
            }
        },
        onFindSuggestion = { suggestion ->
            openUrl(Songsterr.searchUrl(suggestion.title, suggestion.artist))
        },
        onDifficultyFeedback = { feedback -> runAssist(Assist.DIFFICULTY, feedback) },
        tabFiles = tabFiles,
        onOpenTab = onOpenTab,
        audioFiles = audioFiles,
        onOpenAudio = onOpenAudio,
        backings = backings,
        onOpenBacking = onOpenBacking,
        takes = takes,
        onPlayTake = onPlayTake,
        onLoopTake = onLoopTake,
    )
}

/** Where an assist's answer came from, cache included. */
private fun source(provider: String, model: String, fromCache: Boolean): String =
    "$provider · $model" + if (fromCache) " · cached" else ""

/**
 * The quick bar: the session timer and the metronome, from anywhere in the app.
 *
 * The timer recomputes from the start timestamp once a second rather than
 * counting up, so it stays right through a locked screen or a backgrounded app.
 */
@Composable
private fun QuickBar(
    metronomeRunning: Boolean,
    bpm: Int,
    beatInBar: Int,
    session: Session?,
    onOpenMetronome: () -> Unit,
    onOpenTuner: () -> Unit,
    onStopMetronome: () -> Unit,
    onEndSession: () -> Unit,
    onPauseSession: () -> Unit,
    /** A card floating at the foot of the content (wide), rather than a sheet on the tab bar (phone). */
    floating: Boolean,
) {
    if (!metronomeRunning && session == null) return

    var elapsed by remember { mutableLongStateOf(session?.durationSeconds() ?: 0L) }
    // Keyed on the whole session, not its id: pausing hands over a new copy,
    // and a loop still holding the old one would keep counting through the pause.
    LaunchedEffect(session) {
        while (session != null) {
            elapsed = session.durationSeconds()
            delay(1_000)
        }
    }

    val scheme = MaterialTheme.colorScheme
    val edge = screenPadding()
    val shape = if (floating) MaterialTheme.shapes.large
    else RoundedCornerShape(topStart = Radius.lg, topEnd = Radius.lg)
    Surface(
        color = scheme.surfaceContainerHigh,
        shape = shape,
        shadowElevation = if (floating) Elevation.raised else Elevation.none,
        border = BorderStroke(1.dp, scheme.outlineVariant),
        modifier = if (floating) Modifier.padding(horizontal = edge, vertical = Space.md).widthIn(max = Widths.reading)
        else Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = Space.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (session != null) {
                Icon(
                    Icons.Filled.Timer,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    minutesAndSeconds(elapsed),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                // Words beside the time, not just a dimmed number.
                if (session.isPaused) {
                    Text("paused", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                }
                IconButton(onClick = onPauseSession, modifier = Modifier.size(48.dp)) {
                    Icon(
                        if (session.isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription = if (session.isPaused) "Resume session" else "Pause session",
                    )
                }
                IconButton(onClick = onEndSession, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Stop, contentDescription = "End session")
                }
            }

            Spacer(Modifier.weight(1f))

            // The tuner one tap away mid-practice — a string drifts
            // most often exactly when the bar is showing.
            IconButton(onClick = onOpenTuner, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Tune, contentDescription = "Open tuner")
            }

            if (metronomeRunning) {
                Box(
                    Modifier.size(10.dp).background(
                        if (beatInBar == 0) RiffColors.lampLit else RiffColors.lampDark,
                        CircleShape,
                    )
                )
                TextButton(onClick = onOpenMetronome) {
                    Text("$bpm BPM", fontWeight = FontWeight.SemiBold)
                }
                IconButton(onClick = onStopMetronome, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.GraphicEq, contentDescription = "Stop metronome")
                }
            }
        }
    }
}

/**
 * Recovering a session. Deliberately three choices, not two: a session left open
 * might be five minutes old because the app died, or fourteen hours old because
 * the phone was put down on Tuesday, and silently logging the second as a
 * fourteen-hour practice would be worse than losing it.
 */
@Composable
private fun RecoverSessionDialog(
    session: Session,
    onResume: () -> Unit,
    onKeep: () -> Unit,
    onDiscard: () -> Unit,
) {
    val minutes = session.durationSeconds() / 60
    AlertDialog(
        onDismissRequest = onDiscard,
        title = { Text("A session was left running") },
        text = {
            Text(
                "It started ${plural(minutes, "minute", "hour")} ago and never ended. " +
                    "Still practicing, or should it be logged as done?",
            )
        },
        confirmButton = { TextButton(onClick = onResume) { Text("Still practicing") } },
        dismissButton = {
            Row {
                TextButton(onClick = onDiscard) { Text("Bin it") }
                TextButton(onClick = onKeep) { Text("Log it") }
            }
        },
    )
}

/** The end-of-session sheet: sensible defaults, quick to dismiss. */
@Composable
private fun EndSessionSheet(
    session: Session,
    onDismiss: () -> Unit,
    onSave: (Int?, String, List<com.riffbuddy.domain.SessionTag>) -> Unit,
    onDiscard: () -> Unit,
    tagSongs: List<Song> = emptyList(),
    tagParts: Map<Long, List<SongPart>> = emptyMap(),
) {
    var rating by remember { mutableStateOf<Int?>(null) }
    var notes by remember { mutableStateOf("") }
    var tagging by remember { mutableStateOf(false) }
    var tags by remember { mutableStateOf(emptyList<com.riffbuddy.domain.SessionTag>()) }
    val seconds = session.durationSeconds()
    val short = seconds < PracticeController.SHORT_SESSION_SECONDS

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (short) "That was a short one" else "${seconds / 60} minutes practiced") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (short) {
                    Text("Under a minute. Log it anyway, or bin it?")
                } else {
                    Text("How did it go?")
                    // Five buttons at Material's 58 dp minimum are wider than a
                    // dialog on a phone; 48 dp each, touching, fits in 240.
                    Row {
                        (1..5).forEach { star ->
                            TextButton(
                                onClick = { rating = if (rating == star) null else star },
                                modifier = Modifier.defaultMinSize(minWidth = 48.dp),
                                contentPadding = PaddingValues(0.dp),
                            ) {
                                Text(
                                    "$star",
                                    color = if (rating != null && star <= rating!!)
                                        MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = if (rating == star) FontWeight.Bold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text("Anything worth remembering?") },
                    )
                    // Optional, and closed until asked for, so ending a
                    // session is still one tap.
                    if (tagSongs.isNotEmpty()) {
                        if (!tagging) {
                            TextButton(onClick = { tagging = true }) { Text("What did you work on? (optional)") }
                        } else {
                            Text("What did you work on?", style = MaterialTheme.typography.titleSmall)
                            val unassigned = (seconds - session.items.sumOf { it.seconds.toLong() }).coerceAtLeast(0)
                            com.riffbuddy.ui.log.SessionTagPicker(
                                candidates = tagSongs,
                                parts = tagParts,
                                totalMinutes = (unassigned / 60).toInt(),
                                onChange = { tags = it },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(rating, notes.trim(), tags) }) { Text("Log it") } },
        dismissButton = { TextButton(onClick = onDiscard) { Text("Bin it") } },
    )
}

/** "1 minute", "5 minutes", "2 hours" — never "1 minutes". */
private fun plural(minutes: Long, minuteWord: String, hourWord: String): String {
    if (minutes < 60) return if (minutes == 1L) "1 $minuteWord" else "$minutes ${minuteWord}s"
    val hours = minutes / 60
    return if (hours == 1L) "1 $hourWord" else "$hours ${hourWord}s"
}

@Composable
private fun KeepScreenOn(on: Boolean) {
    com.riffbuddy.platform.LocalPlatform.current.KeepScreenOn(on)
}

/**
 * The navigation rail for a tablet or a computer: the app's mark at the top,
 * then the five tabs. Tinted a step off the background with a hairline down
 * its edge, so it reads as the frame around the content rather than part of
 * it. [expanded] is the wide form, with each tab's name beside its icon; the
 * narrow one puts the name underneath.
 */
@Composable
private fun BrandRail(selected: Tab, expanded: Boolean, onPick: (Tab) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxHeight()) {
        Column(
            Modifier
                .fillMaxHeight()
                .width(if (expanded) 232.dp else 88.dp)
                .background(scheme.surfaceContainerLow)
                .windowInsetsPadding(APP_INSETS.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start))
                .padding(horizontal = if (expanded) Space.md else Space.sm, vertical = Space.lg)
                .selectableGroup(),
            horizontalAlignment = if (expanded) Alignment.Start else Alignment.CenterHorizontally,
        ) {
            RailBrand(expanded)
            // The tabs hang under the mark in both forms. Centered on a tall
            // window, the narrow rail's tabs left a long empty stretch under
            // the mark that read as a gap in the layout rather than a choice.
            Spacer(Modifier.height(if (expanded) Space.xxl else Space.xl))
            Column(verticalArrangement = Arrangement.spacedBy(if (expanded) Space.xs else Space.sm)) {
                Tab.entries.forEach { t -> RailItem(t, selected == t, expanded) { onPick(t) } }
            }
            Spacer(Modifier.weight(1f))
        }
        Box(Modifier.fillMaxHeight().width(1.dp).background(scheme.outlineVariant))
    }
}

private val RAIL_MARK = 44.dp

/** The app's icon, and on the wide rail its name in the condensed face beside it. */
@Composable
private fun RailBrand(expanded: Boolean) {
    val icon = com.riffbuddy.platform.LocalPlatform.current.appIcon()
    Row(
        Modifier
            .then(if (expanded) Modifier.padding(start = Space.sm) else Modifier)
            .semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tile = RoundedCornerShape(Radius.md)
        Box(
            Modifier.size(RAIL_MARK).clip(tile).background(MaterialTheme.colorScheme.primaryContainer, tile),
            contentAlignment = Alignment.Center,
        ) {
            // The icon is drawn for a launcher's mask, with a wide safe margin
            // round the guitar; drawn larger and clipped to the tile, the guitar
            // fills it the way it fills a home-screen icon.
            Image(
                icon,
                contentDescription = if (expanded) null else "Riff Buddy",
                modifier = Modifier.requiredSize(RAIL_MARK * 1.5f),
            )
        }
        if (expanded) {
            Spacer(Modifier.width(Space.md))
            Text(
                "Riff Buddy",
                style = MaterialTheme.typography.headlineMedium.copy(fontSize = 26.sp, lineHeight = 30.sp),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
    }
}

/**
 * One tab on the rail. The chosen one gets the accent pill, a bolder name and
 * (wide) a bar at its leading edge, so it shows by shape and weight as well
 * as by color. Each is a selectable tab to a screen reader.
 */
@Composable
private fun RailItem(tab: Tab, selected: Boolean, expanded: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val pill = RoundedCornerShape(Radius.pill)
    val iconTint = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant
    val weight = if (selected) FontWeight.Bold else FontWeight.Medium
    // Wide, the whole row is the pill, so the ripple fills it. Narrow, the
    // touch area is the full 72 dp item but the pill is only the icon's
    // 56 by 32: the ripple is drawn on that pill, clipped to it, so hover and
    // press never spill past the shape the selection shows.
    val press = Modifier
        .pressScale(source, 0.96f)
        .clip(pill)
        .selectable(
            selected = selected, interactionSource = source,
            indication = if (expanded) ripple() else null,
            role = Role.Tab, onClick = onClick,
        )
    if (expanded) {
        Row(
            press
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .background(if (selected) scheme.primaryContainer else Color.Transparent)
                .padding(end = Space.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(width = 4.dp, height = 22.dp)
                    .background(if (selected) scheme.primary else Color.Transparent, RoundedCornerShape(Radius.xs))
            )
            Spacer(Modifier.width(Space.md))
            Icon(tab.icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(Space.md))
            Text(
                tab.label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = weight,
                color = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
            )
        }
    } else {
        Column(
            press.width(72.dp).heightIn(min = 60.dp).padding(vertical = Space.xs),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier.size(width = 56.dp, height = 32.dp)
                    .clip(pill)
                    .background(if (selected) scheme.primaryContainer else Color.Transparent)
                    .indication(source, ripple()),
                contentAlignment = Alignment.Center,
            ) {
                Icon(tab.icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.height(Space.xs))
            Text(
                tab.label,
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp),
                fontWeight = weight,
                color = if (selected) scheme.onSurface else scheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** The phone's tab bar: a step up from the background, names always shown, the chosen tab in the accent pill. */
@Composable
private fun BottomTabs(selected: Tab, onPick: (Tab) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    NavigationBar(containerColor = scheme.surfaceContainer, tonalElevation = 0.dp) {
        Tab.entries.forEach { t ->
            NavigationBarItem(
                selected = selected == t,
                onClick = { onPick(t) },
                icon = { Icon(t.icon, contentDescription = null) },
                label = {
                    Text(t.label, fontWeight = if (selected == t) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
                },
                alwaysShowLabel = true,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = scheme.onPrimaryContainer,
                    selectedTextColor = scheme.onSurface,
                    indicatorColor = scheme.primaryContainer,
                    unselectedIconColor = scheme.onSurfaceVariant,
                    unselectedTextColor = scheme.onSurfaceVariant,
                ),
            )
        }
    }
}

/** A one-pixel line in the quiet outline color, between the content and a bar. */
@Composable
private fun HorizontalHairline() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

/**
 * Songs on a wide screen: the list down the left and the open song beside it.
 * With nothing open yet, the right side says what will appear there. Each song
 * arrives with a soft fade of its own while the list stays still, so the
 * search and filters above it keep their place.
 */
@Composable
private fun SongsTwoPane(
    openSong: Song?,
    list: @Composable () -> Unit,
    page: @Composable (Song) -> Unit,
) {
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.width(SONG_LIST_WIDTH).fillMaxHeight()) { list() }
        Box(Modifier.fillMaxHeight().width(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
            if (openSong != null) {
                Box(
                    Modifier.widthIn(max = Widths.reading).fillMaxSize()
                        .screenEntrance("song:${openSong.id}", Entrance.SOFT)
                ) { page(openSong) }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(
                        icon = Icons.Filled.LibraryMusic,
                        title = "Pick a song",
                        body = "Its parts, tone cards, tabs and takes open here, beside the list.",
                        modifier = Modifier.widthIn(max = 420.dp),
                    )
                }
            }
        }
    }
}

private val SONG_LIST_WIDTH = 380.dp

/** The wide rail, the list and a song page of at least 480 dp. */
private val TWO_PANE_FROM = 1100.dp

/** Set once the first milestone check has recorded history silently. */
private const val MILESTONES_SEEDED = "milestones_seeded"
/** "1" when the player has folded the tool chips on a phone. */
private const val TOOLS_FOLDED = "tools_folded"

/** The milestone keys this install has seen, so catalog additions arrive quietly. */
private const val MILESTONES_KNOWN = "milestones_known"
