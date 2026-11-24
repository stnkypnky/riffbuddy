package com.riffbuddy.ui.tone

import androidx.compose.foundation.ScrollState
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.riffbuddy.ai.AskAnswer
import com.riffbuddy.ai.GeneratedCard
import com.riffbuddy.ai.ToneCardGenerator
import com.riffbuddy.ai.prompts.AskPrompt
import com.riffbuddy.data.ChatMessage
import com.riffbuddy.data.ChatRepo
import com.riffbuddy.data.Conversation
import com.riffbuddy.data.Rig
import com.riffbuddy.domain.HelpTopic
import com.riffbuddy.domain.PlayerProfile
import com.riffbuddy.domain.Session
import com.riffbuddy.domain.Song
import com.riffbuddy.domain.formEncode
import com.riffbuddy.domain.local
import com.riffbuddy.domain.localZone
import com.riffbuddy.ui.ConfirmDelete
import com.riffbuddy.ui.help.HelpButton
import com.riffbuddy.ui.tactile.FilledIconButton
import com.riffbuddy.ui.tactile.FilledTonalIconButton
import com.riffbuddy.ui.tactile.FilterChip
import com.riffbuddy.ui.tactile.IconButton
import com.riffbuddy.ui.tactile.OutlinedButton
import com.riffbuddy.ui.tactile.TextButton
import com.riffbuddy.ui.theme.Radius
import com.riffbuddy.ui.theme.Space
import com.riffbuddy.ui.theme.Widths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * Parts of a song a tone might be for.
 *
 * The usual five plus "other"; the rest are here because songs have them
 * and a coach that cannot be asked about an outro is missing the part people
 * most often get stuck on — the bit after the last chorus that nobody rehearses.
 */
private val PARTS = listOf(
    "rhythm", "lead", "clean", "intro", "verse", "chorus", "bridge", "solo", "outro", "riff",
)

/** From this width of the tab the past chats get a column of their own. */
private val EXPANDED = 840.dp

/**
 * The Coach tab: ask anything, or get a tone card for a song.
 *
 * Ask opens on a welcome rather than an empty box: who the coach is, what it
 * already knows about the player, whether it can answer right now, and
 * questions to start from. A first question is the hardest one to write.
 */
@Composable
fun CoachTab(
    songs: List<Song>,
    rig: Rig?,
    generator: ToneCardGenerator,
    scope: CoroutineScope,
    onSaveCard: (Song, String, GeneratedCard) -> Unit = { _, _, _ -> },
    onRateCard: (Boolean) -> Unit = {},
    chatRepo: ChatRepo? = null,
    /** A question handed over from the helper's Ask the Coach. */
    pendingQuestion: String? = null,
    onQuestionTaken: () -> Unit = {},
    /** Which AI answers, and which (if any) can search the web. */
    askSetup: AskSetup = AskSetup(),
    length: AskPrompt.Length = AskPrompt.Length.STANDARD,
    onLength: (AskPrompt.Length) -> Unit = {},
    webSearch: Boolean = false,
    onWebSearch: (Boolean) -> Unit = {},
    /** Practice sessions, for "your last session was…" and a plan after a break. */
    sessions: List<Session> = emptyList(),
    /** Level, styles and goals; null reads what the generator was given. */
    player: PlayerProfile? = null,
    /** Asked every few seconds while the tab is open, so being offline shows before a question fails. */
    isOnline: () -> Boolean = { true },
    /** Where an AI key is added. */
    onOpenSettings: () -> Unit = {},
    /** Where the amp is added, for a tone card with no rig; no button without it. */
    onOpenGear: (() -> Unit)? = null,
    /** Where songs are added; no button without it. */
    onOpenSongs: (() -> Unit)? = null,
) {
    var mode by remember { mutableStateOf(CoachMode.ASK) }
    // Held here until the Ask section is on screen to take it. The Shell's
    // copy is cleared at once, so coming back to this tab later doesn't type
    // the same question in again.
    var seedQuestion by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingQuestion) {
        if (pendingQuestion != null) {
            mode = CoachMode.ASK
            seedQuestion = pendingQuestion
            onQuestionTaken()
        }
    }
    var song by remember(songs) { mutableStateOf(songs.firstOrNull()) }

    // Polled rather than waited on: the check is cheap and platform-neutral,
    // and a few seconds' lag is fine for a note that is only advice.
    var online by remember { mutableStateOf(true) }
    LaunchedEffect(isOnline) {
        while (true) {
            online = runCatching { isOnline() }.getOrDefault(true)
            delay(5_000)
        }
    }
    val readiness = when {
        askSetup.answering == null && askSetup.switch?.choices?.isNotEmpty() == true -> Readiness.PAID_UNCHOSEN
        askSetup.answering == null -> Readiness.NO_KEY
        !online -> Readiness.OFFLINE
        else -> Readiness.READY
    }

    val header: @Composable () -> Unit = {
        com.riffbuddy.ui.PageHeader(
            title = "Coach",
            subtitle = if (mode == CoachMode.ASK) "Ask anything about your playing." else "Amp settings for a song, on your rig.",
            actions = { HelpButton(HelpTopic.COACH) },
        )
        ModeSwitch(mode, { mode = it }, Modifier.fillMaxWidth().widthIn(max = 460.dp))
    }

    // The width this tab is given, not the window's: the app can hold a page
    // to a reading width inside a wide window, and a chats column carved out
    // of that would squeeze the conversation.
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
    val wide = maxWidth >= EXPANDED
    when (mode) {
        CoachMode.ASK -> AskSection(
            generator = generator,
            rig = rig,
            song = song,
            songs = songs,
            onPickSong = { song = it },
            scope = scope,
            chatRepo = chatRepo,
            seedQuestion = seedQuestion,
            onSeedTaken = { seedQuestion = null },
            setup = askSetup,
            length = length,
            onLength = onLength,
            webSearch = webSearch,
            onWebSearch = onWebSearch,
            sessions = sessions,
            player = player ?: generator.player,
            readiness = readiness,
            onOpenSettings = onOpenSettings,
            wide = wide,
            header = header,
        )
        CoachMode.TONE -> ToneSection(
            songs = songs,
            rig = rig,
            song = song,
            onPickSong = { song = it },
            generator = generator,
            scope = scope,
            onSaveCard = onSaveCard,
            onRateCard = onRateCard,
            readiness = readiness,
            onOpenSettings = onOpenSettings,
            onOpenGear = onOpenGear,
            onOpenSongs = onOpenSongs,
            wide = wide,
            header = header,
        )
    }
    }
}

/** A page column no wider than reads well, centered in a wide window. */
@Composable
private fun PageColumn(scroll: ScrollState, wide: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().verticalScroll(scroll)
            .padding(
                horizontal = if (wide) Space.screenWide else Space.screen,
                vertical = Space.lg,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.widthIn(max = Widths.reading).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Space.lg),
        ) { content() }
    }
}

/**
 * Tone cards: pick a song and a part, get the amp set up for it.
 *
 * The song list comes from the songbook rather than a free-text box — the coach
 * needs a song that exists so the card can be saved back to it, and typing a
 * title with a guitar on is something to avoid.
 */
@Composable
private fun ToneSection(
    songs: List<Song>,
    rig: Rig?,
    song: Song?,
    onPickSong: (Song) -> Unit,
    generator: ToneCardGenerator,
    scope: CoroutineScope,
    onSaveCard: (Song, String, GeneratedCard) -> Unit,
    onRateCard: (Boolean) -> Unit,
    readiness: Readiness,
    onOpenSettings: () -> Unit,
    onOpenGear: (() -> Unit)?,
    onOpenSongs: (() -> Unit)?,
    wide: Boolean,
    header: @Composable () -> Unit,
) {
    var part by remember { mutableStateOf(PARTS.first()) }
    var state by remember { mutableStateOf<ToneCardState>(ToneCardState.Idle) }
    var last by remember { mutableStateOf<GeneratedCard?>(null) }
    var savedThis by remember { mutableStateOf(false) }

    fun run(block: suspend () -> Result<GeneratedCard>, working: String) {
        // One request in flight per feature. A second tap on Generate
        // or a feedback chip while one is out is ignored, not queued.
        if (state is ToneCardState.Working) return
        state = ToneCardState.Working(working)
        scope.launch {
            block().fold(
                onSuccess = { generated ->
                    last = generated
                    savedThis = false
                    state = ToneCardState.Ready(
                        card = generated.card,
                        provider = generated.provider.label + if (generated.fromCache) " (cached)" else "",
                        model = generated.model,
                        elapsedMs = generated.elapsedMs,
                    )
                },
                onFailure = { state = ToneCardState.Problem(it.message ?: "Something went wrong", true) },
            )
        }
    }

    // One scrolling page, header and all: on a phone in landscape, a header
    // and pickers fixed above the card filled the screen on their own.
    PageColumn(rememberScrollState(), wide, Modifier.fillMaxSize()) {
        header()
        ReadinessCard(
            readiness,
            onOpenSettings,
            offlineNote = "A new card needs a connection. Cards you've saved still open from each song, and the rest of the app works offline.",
        )

        val chosen = song ?: songs.firstOrNull()
        when {
            rig?.amp == null -> com.riffbuddy.ui.Panel(Modifier.fillMaxWidth()) {
                com.riffbuddy.ui.EmptyState(
                    icon = Icons.Filled.Speaker,
                    title = "Add your amp first",
                    body = "The coach sets up a tone for your own amp, not amps in general. Add it in Me › Gear.",
                    action = onOpenGear?.let { open ->
                        { com.riffbuddy.ui.tactile.Button(onClick = open, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open Gear") } }
                    },
                )
            }
            chosen == null -> com.riffbuddy.ui.Panel(Modifier.fillMaxWidth()) {
                com.riffbuddy.ui.EmptyState(
                    icon = Icons.Filled.LibraryMusic,
                    title = "Add a song to dial in",
                    body = "Add a song in the Songs tab, then come back and the coach will set up your amp for it.",
                    action = onOpenSongs?.let { open ->
                        { com.riffbuddy.ui.tactile.Button(onClick = open, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open Songs") } }
                    },
                )
            }
            else -> {
                com.riffbuddy.ui.HeroCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(Space.lg + Space.xs)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CoachAvatar(size = 44.dp)
                        Spacer(Modifier.width(Space.md))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "DIALING IN FOR ${rig.name.uppercase()}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                chosen.displayName,
                                style = MaterialTheme.typography.headlineSmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Spacer(Modifier.height(Space.md))
                    // Song and part as two menus on one row: chip rows ran
                    // off the side of a phone.
                    com.riffbuddy.ui.ChipFlow {
                        com.riffbuddy.ui.MenuChip(
                            text = chosen.title,
                            options = songs,
                            selected = chosen,
                            label = { it.title },
                            onPick = { onPickSong(it); state = ToneCardState.Idle },
                            description = "Song",
                            icon = Icons.Filled.LibraryMusic,
                        )
                        com.riffbuddy.ui.MenuChip(
                            text = part.replaceFirstChar { it.uppercase() },
                            options = PARTS,
                            selected = part,
                            label = { it.replaceFirstChar { c -> c.uppercase() } },
                            onPick = { part = it; state = ToneCardState.Idle },
                            description = "Part",
                        )
                    }
                    Spacer(Modifier.height(Space.md))
                    // What the card is dialed for, drawn as the Gear tab draws it.
                    com.riffbuddy.ui.gear.RigChain(rig)
                }

                ToneCardView(
                    heading = "${chosen.displayName} — ${part.lowercase()} tone, on ${rig.name}",
                    state = state,
                    onGenerate = {
                        run({ generator.generate(chosen, rig, part) }, "Dialing in your amp…")
                    },
                    onRegenerate = {
                        run({ generator.generate(chosen, rig, part, regenerate = true) }, "Having another go…")
                    },
                    onRefine = { feedback ->
                        val previous = last
                        if (previous != null) {
                            run({ generator.refine(previous, rig, feedback) }, "Adjusting: $feedback…")
                        }
                    },
                    onSave = {
                        last?.let { onSaveCard(chosen, part, it); savedThis = true }
                    },
                    onRate = onRateCard,
                    saved = savedThis,
                )
            }
        }
    }
}

/**
 * What Ask needs to say about the AI: who answers, and who would search.
 * Worked out in the Shell, which holds the keys and the model choices.
 */
data class AskSetup(
    /** "Groq · gpt-oss-120b", or null with no key at all. */
    val answering: String? = null,
    /** The provider and model a web search would go to, or null if none can. */
    val searching: String? = null,
    /** What a search costs there, in words. */
    val searchCost: String = "",
    /** Changing who answers from here; null hides the switch. */
    val switch: ModelSwitch? = null,
)

/**
 * The coach chat: conversations rather than single questions.
 *
 * A follow-up goes out with the turns before it in the same chat, so the
 * coach knows what "it" and "that" mean; New chat starts over, and a past
 * chat can be reopened. The context chips still decide what else is sent,
 * and "What I sent" still shows all of it, earlier turns included.
 *
 * The box stays at the bottom of the screen while the conversation scrolls
 * above it, as in any chat: a box that scrolled away with the answers had to
 * be scrolled back to for every follow-up.
 */
@Composable
private fun AskSection(
    generator: ToneCardGenerator,
    rig: Rig?,
    song: Song?,
    songs: List<Song>,
    onPickSong: (Song) -> Unit,
    scope: CoroutineScope,
    chatRepo: ChatRepo?,
    seedQuestion: String?,
    onSeedTaken: () -> Unit,
    setup: AskSetup,
    length: AskPrompt.Length,
    onLength: (AskPrompt.Length) -> Unit,
    webSearch: Boolean,
    onWebSearch: (Boolean) -> Unit,
    sessions: List<Session>,
    player: PlayerProfile,
    readiness: Readiness,
    onOpenSettings: () -> Unit,
    wide: Boolean,
    header: @Composable () -> Unit,
) {
    val pageScroll = rememberScrollState()
    // Null until the first answer arrives: a chat is only stored once it has one.
    var conversation by remember { mutableStateOf<String?>(null) }
    var thread by remember { mutableStateOf(emptyList<ChatMessage>()) }
    var chats by remember { mutableStateOf(emptyList<Conversation>()) }
    var deleting by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var showAllChats by remember { mutableStateOf(false) }
    // Sources and "what I sent" for answers given in this sitting, by message id.
    val details = remember { mutableStateMapOf<Long, AskAnswer>() }
    val box = remember { FocusRequester() }
    var switching by remember { mutableStateOf(false) }
    val onSwitch: (() -> Unit)? = setup.switch?.takeIf { it.choices.isNotEmpty() }?.let { { switching = true } }

    suspend fun reload() {
        val repo = chatRepo ?: return
        thread = conversation?.let { repo.history(it) } ?: emptyList()
        chats = repo.conversations()
    }
    LaunchedEffect(chatRepo) { reload() }

    fun open(id: String?) {
        conversation = id
        if (id == null) thread = emptyList()
        scope.launch { reload() }
    }

    var question by remember { mutableStateOf("") }
    // Typed in, never sent: the player reads it over and taps Send, so a
    // half-formed search never spends a free request.
    LaunchedEffect(seedQuestion) {
        if (seedQuestion != null) {
            if (seedQuestion.isNotBlank()) question = seedQuestion
            onSeedTaken()
        }
    }
    var includeRig by remember { mutableStateOf(true) }
    var includeSong by remember { mutableStateOf(false) }
    /** True just after "Song" is turned on, so the song list opens by itself. */
    var choosingSong by remember { mutableStateOf(false) }
    var includeLevel by remember { mutableStateOf(true) }
    // The answer as it is being written. Written from the network
    // thread, which Compose state is built to allow.
    var partial by remember { mutableStateOf("") }
    var pendingQuestion by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    /** The last failure was a web search: offer to ask without it. */
    var searchFailed by remember { mutableStateOf(false) }
    val canSearch = setup.searching != null
    val searching = webSearch && canSearch

    // The newest message in view as it arrives, as a chat does.
    LaunchedEffect(thread.size, partial.length / 200, pendingQuestion) {
        if (thread.isNotEmpty() || pendingQuestion != null) pageScroll.animateScrollTo(pageScroll.maxValue)
    }

    val asking = song ?: songs.firstOrNull()

    fun send(withWeb: Boolean = searching) {
        val repo = chatRepo
        val asked = question.trim()
        if (asked.isEmpty() || working) return
        working = true
        problem = null
        searchFailed = false
        partial = ""
        pendingQuestion = asked
        question = ""
        val earlier = thread.map { com.riffbuddy.ai.ChatTurn(it.fromPlayer, it.content) }
        job = scope.launch {
            generator.ask(
                question = asked,
                rig = rig,
                context = AskPrompt.Context(includeRig, includeSong, includeLevel),
                song = asking,
                onPartial = { partial = it },
                history = earlier,
                length = length,
                webSearch = withWeb,
            ).fold(
                onSuccess = { answer ->
                    val id = conversation ?: repo?.newConversation().orEmpty().also { conversation = it }
                    if (repo != null) {
                        repo.add(id, fromPlayer = true, content = asked)
                        // Sources are kept with the answer as Markdown links,
                        // so they're still there when the chat is reopened.
                        val stored = answer.answer + sourcesMarkdown(answer)
                        val msgId = repo.add(id, fromPlayer = false, content = stored, provider = answer.provider.label, model = answer.model)
                        details[msgId] = answer
                        reload()
                    }
                    partial = ""
                    pendingQuestion = null
                },
                onFailure = {
                    problem = it.message
                    searchFailed = withWeb
                    // Give the question back rather than lose it.
                    question = asked
                    pendingQuestion = null
                },
            )
            working = false
            job = null
        }
    }

    fun cancel() {
        // Cancel really cancels. The reply, if it still arrives, is dropped.
        job?.cancel(); job = null
        working = false
        partial = ""
        pendingQuestion?.let { question = it }
        pendingQuestion = null
        problem = "Canceled."
    }

    fun newChat() {
        problem = null
        open(null)
    }

    val home = thread.isEmpty() && pendingQuestion == null
    val zone = remember { localZone() }
    val now = remember(chats, home) { Clock.System.now() }

    Row(Modifier.fillMaxSize()) {
        // Only once there is a chat to list: empty, the column was a wide
        // blank strip with a New chat button that had nothing to do.
        if (wide && chats.isNotEmpty()) {
            PastChatsColumn(
                chats = chats,
                current = conversation,
                now = now,
                zone = zone,
                showNew = !home,
                newEnabled = !working,
                onNew = ::newChat,
                onOpen = { if (!working) { problem = null; open(it.id) } },
                modifier = Modifier.width(280.dp).fillMaxHeight(),
            )
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            PageColumn(pageScroll, wide, Modifier.weight(1f)) {
                header()
                // On a new chat the welcome comes first and what's missing
                // second; in a conversation, what's missing is all there is.
                val readinessCard = @Composable {
                    ReadinessCard(
                        readiness,
                        onOpenSettings,
                        offlineNote = "The coach needs a connection to answer. Your past chats still open, and the rest of the app works offline.",
                        onSwitch = onSwitch,
                    )
                }
                if (!home) readinessCard()

                if (home) {
                    WelcomeHero(
                        greeting = CoachHome.greeting(now.local(zone).hour),
                        knows = CoachHome.knows(rig, songs, sessions, now, zone),
                        answering = if (searching) setup.searching else setup.answering,
                        readiness = readiness,
                        onSwitch = onSwitch,
                    )
                    readinessCard()
                    com.riffbuddy.ui.SectionHeader("Start with a question")
                    StarterGrid(
                        CoachHome.starters(rig, songs, sessions, player, now, zone, limit = 6),
                    ) { picked ->
                        question = picked
                        runCatching { box.requestFocus() }
                    }
                    // Beside the chat on a wide window; here on a narrow one.
                    if (!wide && chats.isNotEmpty()) {
                        val shown = if (showAllChats) chats else chats.take(3)
                        com.riffbuddy.ui.SectionHeader("Continue a chat") {
                            if (chats.size > 3) TextButton(onClick = { showAllChats = !showAllChats }) {
                                Text(if (showAllChats) "Show fewer" else "All ${chats.size}")
                            }
                        }
                        ContinueChats(shown, now, zone) { if (!working) open(it.id) }
                    }
                } else {
                    ChatBar(
                        chats = chats,
                        current = conversation,
                        showPicker = !wide,
                        working = working,
                        onOpen = { problem = null; open(it.id) },
                        onNew = ::newChat,
                        onDelete = { deleting = true },
                    )

                    // The conversation, oldest first.
                    Column(verticalArrangement = Arrangement.spacedBy(Space.lg)) {
                        thread.forEachIndexed { i, m ->
                            if (m.fromPlayer) PlayerBubble(m.content)
                            else CoachBubble(
                                text = m.content,
                                meta = listOf(m.provider, m.model).filter { it.isNotBlank() }.joinToString(" · "),
                                details = details[m.id],
                                isLast = i == thread.lastIndex,
                            )
                        }
                        pendingQuestion?.let { q ->
                            PlayerBubble(q)
                            if (working) CoachBubble(
                                text = partial,
                                meta = if (partial.isBlank()) null else "Still writing…",
                                details = null,
                                isLast = false,
                                pending = true,
                                waitingLabel = if (searching) "Searching the web…" else "Thinking…",
                                showWaitingLabel = searching,
                            )
                        }
                    }
                }

                problem?.let {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(Space.sm))
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    }
                    // The question is back in the box; one tap sends it without the web.
                    if (searchFailed && question.isNotBlank()) {
                        OutlinedButton(onClick = { send(withWeb = false) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("Ask without the web")
                        }
                    }
                }
            }

            Composer(
                question = question,
                onQuestion = { question = it },
                box = box,
                firstQuestion = thread.isEmpty(),
                working = working,
                searching = searching,
                onSend = { send() },
                onCancel = ::cancel,
                includeRig = includeRig,
                onIncludeRig = { includeRig = it },
                includeSong = includeSong,
                onIncludeSong = { includeSong = it; choosingSong = it },
                includeLevel = includeLevel,
                onIncludeLevel = { includeLevel = it },
                asking = asking,
                songPicked = song != null,
                songs = songs,
                choosingSong = choosingSong,
                onPickSong = { onPickSong(it); choosingSong = false },
                setup = setup,
                length = length,
                onLength = onLength,
                webSearch = webSearch,
                onWebSearch = onWebSearch,
                wide = wide,
                onSwitch = onSwitch,
            )
        }
    }

    if (switching) setup.switch?.let { ModelSwitchDialog(it) { switching = false } }

    if (deleting) {
        ConfirmDelete(
            "this chat",
            "Its questions and answers are removed from this device. Other chats and tone cards stay.",
            onConfirm = {
                val id = conversation
                scope.launch {
                    if (id != null) chatRepo?.delete(id)
                    conversation = null
                    reload()
                }
            },
            onDismiss = { deleting = false },
        )
    }
}

/** The open chat's name, a way to another one, a fresh one, and Delete. */
@Composable
private fun ChatBar(
    chats: List<Conversation>,
    current: String?,
    showPicker: Boolean,
    working: Boolean,
    onOpen: (Conversation) -> Unit,
    onNew: () -> Unit,
    onDelete: () -> Unit,
) {
    val open = chats.firstOrNull { it.id == current }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
        Box(Modifier.weight(1f)) {
            if (showPicker && chats.isNotEmpty()) com.riffbuddy.ui.MenuChip(
                text = open?.title ?: "New chat",
                options = chats,
                selected = open ?: chats.first(),
                label = { it.title },
                onPick = { if (!working) onOpen(it) },
                description = "Past chats",
                icon = Icons.Filled.History,
            ) else Text(
                open?.title ?: "New chat",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onNew, enabled = !working) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Space.xs))
            Text("New chat")
        }
        if (open != null) IconButton(onClick = onDelete, enabled = !working) {
            Icon(Icons.Outlined.Delete, contentDescription = "Delete this chat")
        }
    }
}

/**
 * Keeps the box above an on-screen keyboard. The keyboard's height is
 * measured from the bottom of the window, but whatever sits under this
 * screen (a bottom bar, the gesture strip) is already in the way of it, so
 * only the part of the keyboard that reaches past that is padded.
 */
@Composable
private fun Modifier.clearOfKeyboard(): Modifier {
    val keyboard = WindowInsets.ime.getBottom(LocalDensity.current)
    val windowHeight = LocalWindowInfo.current.containerSize.height
    var below by remember { mutableStateOf(0) }
    val pad = with(LocalDensity.current) { (keyboard - below).coerceAtLeast(0).toDp() }
    return this
        .onGloballyPositioned { below = (windowHeight - it.boundsInWindow().bottom.toInt()).coerceAtLeast(0) }
        .padding(bottom = pad)
}

/**
 * The box, pinned under the conversation: what goes with the question in one
 * row of chips, how it's answered in one menu, and a Send button.
 */
@Composable
private fun Composer(
    question: String,
    onQuestion: (String) -> Unit,
    box: FocusRequester,
    firstQuestion: Boolean,
    working: Boolean,
    searching: Boolean,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    includeRig: Boolean,
    onIncludeRig: (Boolean) -> Unit,
    includeSong: Boolean,
    onIncludeSong: (Boolean) -> Unit,
    includeLevel: Boolean,
    onIncludeLevel: (Boolean) -> Unit,
    asking: Song?,
    songPicked: Boolean,
    songs: List<Song>,
    choosingSong: Boolean,
    onPickSong: (Song) -> Unit,
    setup: AskSetup,
    length: AskPrompt.Length,
    onLength: (AskPrompt.Length) -> Unit,
    webSearch: Boolean,
    onWebSearch: (Boolean) -> Unit,
    wide: Boolean,
    onSwitch: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .clearOfKeyboard()
            .background(scheme.surfaceContainerLow),
    ) {
        HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.6f))
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = if (wide) Space.screenWide else Space.screen, vertical = Space.sm),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.widthIn(max = Widths.reading).fillMaxWidth()) {
            // On a phone the chips and the options button don't fit one row:
            // the options move up beside the "Context" label, so every chip
            // stays in view.
            val compact = maxWidth < COMPACT_COMPOSER
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                val contextLabel = @Composable {
                    Text("Context", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                }
                if (compact) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        contextLabel()
                        Spacer(Modifier.weight(1f))
                        AnswerOptions(setup, length, onLength, webSearch, onWebSearch, onSwitch)
                    }
                }
                // What goes with the question, in one row that scrolls
                // sideways rather than wrapping into three, faded at an edge
                // that has more past it.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val chipScroll = rememberScrollState()
                    Row(
                        Modifier.weight(1f).edgeFade(chipScroll).horizontalScroll(chipScroll),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.sm),
                    ) {
                        if (!compact) contextLabel()
                        ContextChip("Rig", includeRig) { onIncludeRig(!includeRig) }
                        // No songs, no chip: there is nothing it could send.
                        if (asking != null) {
                            ContextChip(
                                if (includeSong || songPicked) "Song" else "Pick a song",
                                includeSong,
                            ) { onIncludeSong(!includeSong) }
                        }
                        if (includeSong && asking != null) com.riffbuddy.ui.MenuChip(
                            text = asking.title,
                            options = songs,
                            selected = asking,
                            label = { it.title },
                            onPick = onPickSong,
                            description = "Song to ask about",
                            active = true,
                            icon = Icons.Filled.LibraryMusic,
                            startOpen = choosingSong,
                        )
                        ContextChip("Level", includeLevel) { onIncludeLevel(!includeLevel) }
                    }
                    if (!compact) AnswerOptions(setup, length, onLength, webSearch, onWebSearch, onSwitch)
                }
                if (searching) {
                    Text(
                        "Searches with ${setup.searching}. ${setup.searchCost}",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    OutlinedTextField(
                        value = question,
                        onValueChange = onQuestion,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(box)
                            // Enter sends and Shift+Enter starts a new line, as
                            // in any chat, on a keyboard that has the keys.
                            .onPreviewKeyEvent { e ->
                                if (e.key == Key.Enter && !e.isShiftPressed) {
                                    if (e.type == KeyEventType.KeyDown && question.isNotBlank() && !working) onSend()
                                    true
                                } else false
                            },
                        placeholder = { Text(if (firstQuestion) "Ask your coach anything" else "Ask a follow-up") },
                        minLines = 1,
                        maxLines = 5,
                        shape = RoundedCornerShape(Radius.xl),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = scheme.surface,
                            unfocusedContainerColor = scheme.surface,
                        ),
                    )
                    if (working) {
                        FilledTonalIconButton(onClick = onCancel, modifier = Modifier.size(56.dp)) {
                            Icon(Icons.Filled.Stop, contentDescription = "Cancel")
                        }
                    } else {
                        FilledIconButton(
                            onClick = onSend,
                            enabled = question.isNotBlank(),
                            modifier = Modifier.size(56.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                disabledContainerColor = scheme.surfaceContainerHighest,
                                disabledContentColor = scheme.onSurfaceVariant,
                            ),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                        }
                    }
                }
            }
            }
        }
    }
}

/** Below this width the composer's chips and its options button take two rows. */
private val COMPACT_COMPOSER = 600.dp

/**
 * Fades a sideways-scrolling row out at whichever end has more past it, so a
 * chip cut by the edge reads as "scroll for more" rather than as clipped.
 */
private fun Modifier.edgeFade(scroll: ScrollState): Modifier = this
    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fade = 24.dp.toPx().coerceAtMost(size.width / 2)
        if (scroll.canScrollForward) drawRect(
            androidx.compose.ui.graphics.Brush.horizontalGradient(
                listOf(Color.Black, Color.Transparent), startX = size.width - fade, endX = size.width,
            ),
            topLeft = Offset(size.width - fade, 0f),
            size = Size(fade, size.height),
            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
        )
        if (scroll.canScrollBackward) drawRect(
            androidx.compose.ui.graphics.Brush.horizontalGradient(
                listOf(Color.Transparent, Color.Black), startX = 0f, endX = fade,
            ),
            size = Size(fade, size.height),
            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
        )
    }

/** A context toggle: a tick as well as the fill, so it isn't told by color alone. */
@Composable
private fun ContextChip(text: String, on: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    FilterChip(
        selected = on,
        onClick = onClick,
        enabled = enabled,
        label = { Text(text) },
        leadingIcon = if (on) {
            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
        } else null,
        modifier = Modifier.semantics { contentDescription = "Send my ${text.lowercase()} with the question" },
    )
}

/**
 * How it's answered — length, and the web — folded into one menu. The
 * button says what's chosen, so the choice is visible without opening it.
 */
@Composable
private fun AnswerOptions(
    setup: AskSetup,
    length: AskPrompt.Length,
    onLength: (AskPrompt.Length) -> Unit,
    webSearch: Boolean,
    onWebSearch: (Boolean) -> Unit,
    onSwitch: (() -> Unit)?,
) {
    var open by remember { mutableStateOf(false) }
    val canSearch = setup.searching != null
    val summary = length.label + if (webSearch && canSearch) " · Web" else ""
    Box {
        TextButton(
            onClick = { open = true },
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Answer options: $summary" },
        ) {
            Icon(if (webSearch && canSearch) Icons.Filled.Public else Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Space.xs))
            Text(summary)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                "ANSWER LENGTH",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.sm),
            )
            AskPrompt.Length.entries.forEach { l ->
                val on = l == length
                DropdownMenuItem(
                    text = { Text(l.label, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal) },
                    leadingIcon = { if (on) Icon(Icons.Filled.Check, contentDescription = "Chosen") else Spacer(Modifier.size(24.dp)) },
                    onClick = { onLength(l); open = false },
                )
            }
            if (canSearch) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Search the web") },
                    leadingIcon = { Icon(Icons.Filled.Public, contentDescription = null) },
                    trailingIcon = {
                        androidx.compose.material3.Switch(checked = webSearch, onCheckedChange = null)
                    },
                    onClick = { onWebSearch(!webSearch) },
                    modifier = Modifier.semantics { contentDescription = "Search the web: ${if (webSearch) "on" else "off"}" },
                )
            }
            setup.answering?.let {
                HorizontalDivider()
                Text(
                    "Answering with ${if (webSearch && canSearch) setup.searching else it}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.sm),
                )
                if (onSwitch != null) {
                    DropdownMenuItem(
                        text = { Text("Change who answers…") },
                        leadingIcon = { Icon(Icons.Filled.SwapHoriz, contentDescription = null) },
                        onClick = { open = false; onSwitch() },
                    )
                }
            }
        }
    }
}

/** The sources an answer drew on, as a Markdown list stored with it. */
private fun sourcesMarkdown(answer: AskAnswer): String {
    if (answer.sources.isEmpty() && answer.searchQueries.isEmpty()) return ""
    return buildString {
        if (answer.searchQueries.isNotEmpty()) {
            append("\n\n**Searched:** ")
            append(answer.searchQueries.joinToString(" · ") { q ->
                "[$q](https://www.google.com/search?q=${formEncode(q)})"
            })
        }
        if (answer.sources.isNotEmpty()) {
            append("\n\n**Sources**\n")
            answer.sources.forEach { append("- [${it.title.replace("]", ")")}](${it.url})\n") }
        }
    }.trimEnd()
}

/**
 * One of the coach's answers: Markdown drawn properly, its sources, and a
 * Copy button. "What I sent" is offered for answers given in this sitting.
 * While the answer has not started, three dots stand in for it.
 */
@Composable
private fun CoachBubble(
    text: String,
    meta: String?,
    details: AskAnswer?,
    isLast: Boolean,
    pending: Boolean = false,
    waitingLabel: String = "Thinking…",
    showWaitingLabel: Boolean = false,
) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var showSent by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        CoachAvatar(size = 32.dp, modifier = Modifier.padding(top = Space.xxs))
        Spacer(Modifier.width(Space.sm))
        Column(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(Radius.xs, Radius.lg, Radius.lg, Radius.lg))
                .background(scheme.surfaceContainerHigh)
                .padding(horizontal = Space.lg, vertical = Space.md),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Text("Coach", style = MaterialTheme.typography.labelMedium, color = scheme.primary)
            if (pending && text.isBlank()) {
                TypingIndicator(waitingLabel, showLabel = showWaitingLabel)
            } else {
                Markdown(text, color = scheme.onSurface)
            }
            meta?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant) }
            if (!pending) Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(text))
                    copied = true
                }) { Text(if (copied) "Copied" else "Copy") }
                if (details != null) TextButton(onClick = { showSent = !showSent }) {
                    Text(if (showSent) "Hide what I sent" else "What I sent")
                }
            }
            if (showSent && details != null) {
                Text(
                    details.sent,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.clip(RoundedCornerShape(Radius.sm))
                        .background(scheme.surfaceContainerHighest).padding(Space.md),
                )
            }
            if (isLast && !pending) Text(
                "May be wrong; check anything that matters.",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The player's message, on the right in the accent's container. Who said it
 * is also in words ("You"), for a screen reader and for anyone who can't tell
 * the colors apart.
 */
@Composable
private fun PlayerBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(
            Modifier
                .fillMaxWidth(0.85f)
                .wrapContentWidth(Alignment.End)
                .clip(RoundedCornerShape(Radius.lg, Radius.xs, Radius.lg, Radius.lg))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = Space.lg, vertical = Space.md),
            verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            Text("You", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}
