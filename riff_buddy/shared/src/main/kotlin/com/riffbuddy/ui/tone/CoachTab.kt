package com.riffbuddy.ui.tone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import com.riffbuddy.domain.HelpTopic
import com.riffbuddy.ui.help.HelpButton
import androidx.compose.ui.text.font.FontWeight

import androidx.compose.ui.text.SpanStyle

import androidx.compose.ui.text.withStyle

import androidx.compose.ui.text.buildAnnotatedString

import androidx.compose.ui.text.AnnotatedString

import androidx.compose.ui.Alignment

import androidx.compose.foundation.layout.Spacer

import com.riffbuddy.ui.ConfirmDelete

import androidx.compose.runtime.LaunchedEffect

import kotlinx.coroutines.Job

import com.riffbuddy.data.ChatMessage

import com.riffbuddy.data.ChatRepo

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import com.riffbuddy.ui.tactile.AssistChip
import com.riffbuddy.ui.tactile.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.riffbuddy.ui.tactile.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import com.riffbuddy.ui.tactile.TextButton
import com.riffbuddy.ai.AskAnswer
import com.riffbuddy.ai.prompts.AskPrompt
import com.riffbuddy.ai.GeneratedCard
import com.riffbuddy.ai.ToneCardGenerator
import com.riffbuddy.data.Rig
import com.riffbuddy.domain.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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

/**
 * The Coach tab: pick a song and a part, get a tone card.
 *
 * The song list comes from the songbook rather than a free-text box — the coach
 * needs a song that exists so the card can be saved back to it, and typing a
 * title with a guitar on is something to avoid.
 */
private enum class CoachMode(val label: String) { TONE("Tone card"), ASK("Ask anything") }

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
) {
    val pageScroll = rememberScrollState()
    var mode by remember { mutableStateOf(CoachMode.TONE) }
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

    // One scrolling page, header and all. The header, mode chips and song and
    // part rows used to sit fixed above a scrolling card, and on a phone in
    // landscape they filled the screen on their own — nothing below them could
    // be reached. The Ask history had no scroll at all.
    Column(
        Modifier.fillMaxSize().verticalScroll(pageScroll)
            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Coach", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            HelpButton(HelpTopic.COACH)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CoachMode.entries.forEach { candidate ->
                FilterChip(
                    selected = mode == candidate,
                    onClick = { mode = candidate },
                    label = { Text(candidate.label) },
                    modifier = Modifier.heightIn(min = 40.dp),
                )
            }
        }

        if (mode == CoachMode.ASK) {
            AskSection(
                generator = generator,
                rig = rig,
                song = song,
                songs = songs,
                onPickSong = { song = it; state = ToneCardState.Idle },
                scope = scope,
                chatRepo = chatRepo,
                seedQuestion = seedQuestion,
                onSeedTaken = { seedQuestion = null },
                setup = askSetup,
                length = length,
                onLength = onLength,
                webSearch = webSearch,
                onWebSearch = onWebSearch,
                pageScroll = pageScroll,
            )
            return@Column
        }

        val chosen = song ?: songs.firstOrNull()
        when {
            rig?.amp == null -> Hint("Add your amp in Me > Gear first — the coach sets up a tone for a specific rig, not in general.")
            chosen == null -> Hint("Add a song in the Songs tab, then come back and the coach will dial it in.")
            else -> {
                // Song and part as two menus on one row (the Songs tab's
                // MenuChip): the chip rows before ran off the side of the
                // phone, and only offered the first twelve songs.
                com.riffbuddy.ui.ChipFlow {
                    com.riffbuddy.ui.MenuChip(
                        text = chosen.title,
                        options = songs,
                        selected = chosen,
                        label = { it.title },
                        onPick = { song = it; state = ToneCardState.Idle },
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

                // What the card is dialed for, drawn as the Gear tab draws it.
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "DIALING IN FOR ${rig.name.uppercase()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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

@Composable
private fun Hint(text: String) {
    com.riffbuddy.ui.Panel {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun Label(text: String) = com.riffbuddy.ui.SectionHeader(text)

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
)

/**
 * The coach chat: conversations rather than single questions.
 *
 * A follow-up goes out with the turns before it in the same chat, so the
 * coach knows what "it" and "that" mean; New chat starts over, and Past
 * chats reopens an old one. The context chips still decide what else is
 * sent, and "What I sent" still shows all of it, earlier turns included.
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
    seedQuestion: String? = null,
    onSeedTaken: () -> Unit = {},
    setup: AskSetup,
    length: AskPrompt.Length,
    onLength: (AskPrompt.Length) -> Unit,
    webSearch: Boolean,
    onWebSearch: (Boolean) -> Unit,
    pageScroll: androidx.compose.foundation.ScrollState,
) {
    var conversation by remember { mutableStateOf<String?>(null) }
    var thread by remember { mutableStateOf(emptyList<ChatMessage>()) }
    var chats by remember { mutableStateOf(emptyList<com.riffbuddy.data.Conversation>()) }
    var deleting by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    // Sources and "what I sent" for answers given in this sitting, by message id.
    val details = remember { androidx.compose.runtime.mutableStateMapOf<Long, AskAnswer>() }

    suspend fun reload() {
        val repo = chatRepo ?: return
        val id = conversation ?: repo.latest().also { conversation = it }
        thread = repo.history(id)
        chats = repo.conversations()
    }
    LaunchedEffect(chatRepo) { reload() }

    var question by remember { mutableStateOf("") }
    // Typed in, never sent: the player reads it over and taps Ask, so a
    // half-formed search never spends a free request.
    LaunchedEffect(seedQuestion) {
        if (seedQuestion != null) {
            if (seedQuestion.isNotBlank()) question = seedQuestion
            onSeedTaken()
        }
    }
    var includeRig by remember { mutableStateOf(true) }
    var includeSong by remember { mutableStateOf(false) }
    /** True just after "This song" is turned on, so the song list opens by itself. */
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

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Which chat, and a fresh one.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val current = chats.firstOrNull { it.id == conversation }
            Box(Modifier.weight(1f)) {
                if (chats.isNotEmpty()) com.riffbuddy.ui.MenuChip(
                    text = current?.title ?: "New chat",
                    options = chats,
                    selected = current ?: chats.first(),
                    label = { it.title },
                    onPick = { picked -> conversation = picked.id; scope.launch { reload() } },
                    description = "Past chats",
                    icon = Icons.Filled.History,
                ) else Text("New chat", style = MaterialTheme.typography.titleSmall)
            }
            TextButton(
                onClick = {
                    conversation = chatRepo?.newConversation()
                    thread = emptyList()
                    problem = null
                },
                enabled = !working && thread.isNotEmpty(),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("New chat")
            }
        }

        // The conversation, oldest first.
        thread.forEachIndexed { i, m ->
            if (m.fromPlayer) Bubble(m.content, fromPlayer = true)
            else CoachBubble(
                text = m.content,
                meta = listOf(m.provider, m.model).filter { it.isNotBlank() }.joinToString(" · "),
                details = details[m.id],
                isLast = i == thread.lastIndex,
            )
        }
        pendingQuestion?.let { q ->
            Bubble(q, fromPlayer = true)
            if (working) CoachBubble(
                text = partial.ifBlank { if (searching) "Searching the web…" else "Thinking…" },
                meta = if (partial.isBlank()) null else "Still writing…",
                details = null,
                isLast = false,
                pending = true,
            )
        }
        problem?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            // The question is back in the box; one tap sends it without the web.
            if (searchFailed && question.isNotBlank()) {
                OutlinedButton(onClick = { send(withWeb = false) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Ask without the web")
                }
            }
        }

        // A first question is the hardest one to write: some to start from,
        // made from the player's own songs and gear.
        if (question.isBlank() && thread.isEmpty() && !working) {
            com.riffbuddy.ui.ChipFlow {
                starters(rig, songs).forEach { starter ->
                    AssistChip(onClick = { question = starter }, label = { Text(starter) })
                }
            }
        }

        OutlinedTextField(
            value = question,
            onValueChange = { question = it },
            modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp),
            label = { Text(if (thread.isEmpty()) "Gear, scales, technique — anything" else "Ask a follow-up") },
        )

        // What goes with the question.
        com.riffbuddy.ui.ChipFlow {
            FilterChip(includeRig, { includeRig = !includeRig }, { Text("My rig") },
                modifier = Modifier.heightIn(min = 40.dp))
            FilterChip(
                includeSong,
                {
                    includeSong = !includeSong
                    choosingSong = includeSong
                },
                { Text("This song") },
                modifier = Modifier.heightIn(min = 40.dp),
                enabled = asking != null,
            )
            FilterChip(includeLevel, { includeLevel = !includeLevel }, { Text("My level") },
                modifier = Modifier.heightIn(min = 40.dp))
        }
        if (includeSong && asking != null) {
            com.riffbuddy.ui.MenuChip(
                text = asking.title,
                options = songs,
                selected = asking,
                label = { it.title },
                onPick = { onPickSong(it); choosingSong = false },
                description = "Song to ask about",
                active = true,
                icon = Icons.Filled.LibraryMusic,
                startOpen = choosingSong,
            )
        }

        // How it's answered: length, and the web.
        com.riffbuddy.ui.ChipFlow {
            com.riffbuddy.ui.MenuChip(
                text = "Answer: ${length.label}",
                options = AskPrompt.Length.entries.toList(),
                selected = length,
                label = { it.label },
                onPick = onLength,
                description = "Answer length",
                icon = Icons.AutoMirrored.Filled.Notes,
            )
            if (canSearch) FilterChip(
                selected = webSearch,
                onClick = { onWebSearch(!webSearch) },
                label = { Text("Search the web") },
                leadingIcon = { Icon(Icons.Filled.Public, contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.heightIn(min = 40.dp),
            )
        }
        if (searching) {
            Text(
                "Searches with ${setup.searching}. ${setup.searchCost}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            com.riffbuddy.ui.tactile.Button(
                onClick = { send() },
                enabled = question.isNotBlank() && !working,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(if (working) (if (searching) "Searching…" else "Thinking…") else "Ask") }
            // Cancel really cancels. The reply, if it still arrives, is dropped.
            if (working) {
                TextButton(onClick = {
                    job?.cancel(); job = null
                    working = false
                    partial = ""
                    pendingQuestion?.let { question = it }
                    pendingQuestion = null
                    problem = "Canceled."
                }) { Text("Cancel") }
            }
            Spacer(Modifier.weight(1f))
            setup.answering?.let {
                Text(
                    if (searching) setup.searching ?: it else it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (thread.isNotEmpty()) {
            TextButton(onClick = { deleting = true }) { Text("Delete this chat") }
        }
    }

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

/** The sources an answer drew on, as a Markdown list stored with it. */
private fun sourcesMarkdown(answer: AskAnswer): String {
    if (answer.sources.isEmpty() && answer.searchQueries.isEmpty()) return ""
    return buildString {
        if (answer.searchQueries.isNotEmpty()) {
            append("\n\n**Searched:** ")
            append(answer.searchQueries.joinToString(" · ") { q ->
                "[$q](https://www.google.com/search?q=${java.net.URLEncoder.encode(q, "UTF-8")})"
            })
        }
        if (answer.sources.isNotEmpty()) {
            append("\n\n**Sources**\n")
            answer.sources.forEach { append("- [${it.title.replace("]", ")")}](${it.url})\n") }
        }
    }.trimEnd()
}

/**
 * Starter questions from what the player actually has: their amp, their
 * songs, the song they're furthest into. Generic ones fill in when there's
 * little to go on.
 */
private fun starters(rig: Rig?, songs: List<Song>): List<String> = buildList {
    val amp = rig?.amp?.name
    val learning = songs.firstOrNull { it.status == com.riffbuddy.domain.SongStatus.LEARNING } ?: songs.firstOrNull()
    if (learning != null) add("What should I focus on to learn ${learning.title} faster?")
    if (learning != null && amp != null) add("How do I get the ${learning.title} tone on my $amp?")
    if (amp != null) add("What's the best clean sound on my $amp?")
    add("What should I practice to get faster at chord changes?")
    add("How do I start improvising over a blues?")
}.take(4)

/**
 * One of the coach's answers: Markdown drawn properly, its sources, and a
 * Copy button. "What I sent" is offered for answers given in this sitting.
 */
@Composable
private fun CoachBubble(text: String, meta: String?, details: AskAnswer?, isLast: Boolean, pending: Boolean = false) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var showSent by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Column(
            Modifier
                .fillMaxWidth(0.97f)
                .clip(RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Coach", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            if (pending && text.endsWith("…") && !text.contains('\n')) {
                Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Markdown(text, color = MaterialTheme.colorScheme.onSurface)
            }
            meta?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(10.dp),
                )
            }
            if (isLast && !pending) Text(
                "May be wrong; check anything that matters.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The player's message, as a bubble on the right in amber. Who said it is
 * also in words ("You"), for TalkBack and for anyone who can't tell the
 * colors apart.
 */
@Composable
private fun Bubble(text: String, @Suppress("UNUSED_PARAMETER") fromPlayer: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(
            Modifier
                .fillMaxWidth(0.85f)
                .wrapContentWidth(Alignment.End)
                .clip(RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("You", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}


