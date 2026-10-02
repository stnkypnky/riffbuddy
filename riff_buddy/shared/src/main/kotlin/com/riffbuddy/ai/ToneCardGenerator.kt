package com.riffbuddy.ai

import com.riffbuddy.domain.PlayerProfile

import com.riffbuddy.ai.prompts.AskPrompt
import com.riffbuddy.ai.prompts.AssistPrompts
import com.riffbuddy.ai.prompts.ToneCardPrompt
import com.riffbuddy.data.Rig
import com.riffbuddy.domain.Song
import org.json.JSONObject

/** Any assist result, with where it came from. */
data class Assisted<T>(
    val value: T,
    val provider: ProviderId,
    val model: String,
    val fromCache: Boolean,
)

/** An answer to a free-form question, with the exact payload that produced it. */
data class AskAnswer(
    val question: String,
    val answer: String,
    val provider: ProviderId,
    val model: String,
    /** What actually left the device, for the "what I'm sending" panel. */
    val sent: String,
    val sources: List<WebSource> = emptyList(),
    val searchQueries: List<String> = emptyList(),
)

/** A generated card with its provenance stored alongside it. */
data class GeneratedCard(
    val card: ToneCard,
    val provider: ProviderId,
    val model: String,
    val elapsedMs: Long,
    val promptVersion: String,
    val rawJson: String,
    val fromCache: Boolean,
)

/**
 * Generating and refining a tone card.
 *
 * The rig is what makes this specific: the schema's enums, the system prompt's
 * closed lists and the validation all come from the same profile, so they cannot
 * drift apart and describe different amps.
 */
class ToneCardGenerator(private val coach: AiCoach) {

    /** "About you". Read by every default below, so all prompts follow it. */
    @Volatile var player: PlayerProfile = PlayerProfile()

    suspend fun generate(
        song: Song,
        rig: Rig,
        part: String,
        level: String = player.level.forPrompt,
        styles: String = player.stylesForPrompt,
        tweak: String? = null,
        regenerate: Boolean = false,
    ): Result<GeneratedCard> {
        val amp = rig.amp?.profile ?: return Result.failure(IllegalStateException("No amp in this rig"))
        val guitar = rig.guitar?.profile

        val request = AiRequest(
            systemPrompt = ToneCardPrompt.system(amp, guitar, level, styles, rig.pedals.map { it.profile }),
            userPrompt = ToneCardPrompt.user(
                title = song.title,
                artist = song.artist,
                part = part,
                tuning = song.tuningId?.let { null },
                capo = song.capo,
                sectionNote = null,
                tweak = tweak,
            ),
            schema = ToneCardSchema.forRig(amp, guitar),
            promptVersion = ToneCardPrompt.VERSION,
            feature = "tone-card",
        )

        // Same song, same part, same rig, same prompt version — same answer.
        // Regenerate skips the cache rather than deleting it, so a second
        // opinion does not throw away the first.
        val cacheKey = if (regenerate || tweak != null) null else AiPolicy.cacheKey(
            "tone-card", ToneCardPrompt.VERSION,
            song.title, song.artist, part, rig.id.toString(), amp.model,
        )

        return when (val result = coach.ask(request, cacheKey)) {
            is AiResult.Success -> {
                val json = result.json
                    ?: return Result.failure(IllegalStateException("The coach did not return JSON"))
                Result.success(
                    GeneratedCard(
                        card = ToneCardSchema.validate(json, amp, guitar),
                        provider = result.provider,
                        model = result.model,
                        elapsedMs = result.elapsedMs,
                        promptVersion = ToneCardPrompt.VERSION,
                        rawJson = json.toString(),
                        fromCache = result.fromCache,
                    )
                )
            }
            else -> Result.failure(IllegalStateException(describe(result)))
        }
    }

    /** Change what was complained about, leave the rest alone. */
    suspend fun refine(
        previous: GeneratedCard,
        rig: Rig,
        feedback: String,
    ): Result<GeneratedCard> {
        val amp = rig.amp?.profile ?: return Result.failure(IllegalStateException("No amp in this rig"))
        val guitar = rig.guitar?.profile

        val request = AiRequest(
            systemPrompt = ToneCardPrompt.system(amp, guitar, player.level.forPrompt, player.stylesForPrompt, rig.pedals.map { it.profile }),
            userPrompt = ToneCardPrompt.refinement(previous.rawJson, feedback),
            schema = ToneCardSchema.forRig(amp, guitar),
            promptVersion = ToneCardPrompt.VERSION,
            feature = "tone-refine",
        )

        return when (val result = coach.ask(request, cacheKey = null)) {
            is AiResult.Success -> {
                val json = result.json ?: JSONObject()
                Result.success(
                    previous.copy(
                        card = ToneCardSchema.validate(json, amp, guitar),
                        provider = result.provider,
                        model = result.model,
                        elapsedMs = result.elapsedMs,
                        rawJson = json.toString(),
                        fromCache = false,
                    )
                )
            }
            else -> Result.failure(IllegalStateException(describe(result)))
        }
    }

    /**
     * A question that is not about a song.
     *
     * No schema and no cache: an open question is a new question every time,
     * and serving a cached answer to a differently-worded one would be worse
     * than spending the request.
     */
    suspend fun ask(
        question: String,
        rig: Rig?,
        context: AskPrompt.Context,
        song: Song? = null,
        level: String = player.level.forPrompt,
        styles: String = player.stylesForPrompt,
        onPartial: ((String) -> Unit)? = null,
        /** Earlier turns of this chat, oldest first. */
        history: List<ChatTurn> = emptyList(),
        length: AskPrompt.Length = AskPrompt.Length.STANDARD,
        webSearch: Boolean = false,
    ): Result<AskAnswer> {
        val system = AskPrompt.system(
            context, rig?.amp?.profile, rig?.guitar?.profile, song, level, styles, length, webSearch,
        )
        // The last dozen messages: enough for a conversation to hold its
        // thread, and a bound on how much every follow-up re-sends.
        val recent = history.takeLast(MAX_TURNS)
        val request = AiRequest(
            systemPrompt = system,
            userPrompt = question,
            promptVersion = AskPrompt.VERSION,
            feature = "ask",
            history = recent,
            webSearch = webSearch,
            // A search reads pages before it answers; half a minute isn't enough.
            timeoutMs = if (webSearch) 90_000 else 30_000,
        )
        return when (val result = coach.ask(request, cacheKey = null, onPartial = onPartial)) {
            is AiResult.Success -> Result.success(
                AskAnswer(
                    question = question,
                    answer = result.text.trim(),
                    provider = result.provider,
                    model = result.model,
                    sent = AskPrompt.preview(system, question, recent, webSearch),
                    sources = result.sources,
                    searchQueries = result.searchQueries,
                )
            )
            else -> Result.failure(IllegalStateException(if (webSearch) describeSearch(result) else describe(result)))
        }
    }

    /**
     * A failed web search, said plainly, so the player knows it was the
     * search and not the question; the screen offers to ask without it.
     */
    private fun describeSearch(result: AiResult): String = when (result) {
        is AiResult.Failed, is AiResult.Malformed -> {
            val who = (result as? AiResult.Failed)?.provider ?: (result as? AiResult.Malformed)?.provider
            val detail = (result as? AiResult.Failed)?.message ?: (result as? AiResult.Malformed)?.detail
            "The web search failed" + (who?.let { " at ${it.label}" } ?: "") +
                (detail?.takeIf { it.isNotBlank() }?.let { " (\"$it\")" } ?: "") +
                ". Search tools on the free tiers come and go; try again in a while, or ask without the web."
        }
        else -> describe(result)
    }

    // ---- song assists -----------------------------------------------------

    /**
     * All three assists share this shape, and all three are cached per song.
     *
     * Cached because they are the same question every time — what a song is like,
     * how hard it is, how it goes — and because each one is a free-tier request
     * that would otherwise be spent again every time the page is reopened.
     */
    private suspend fun <T> assist(
        song: Song,
        feature: String,
        version: String,
        system: String,
        schema: JSONObject,
        parse: (JSONObject) -> T,
        extraKey: String = "",
        refresh: Boolean = false,
    ): Result<Assisted<T>> {
        val request = AiRequest(
            systemPrompt = system,
            userPrompt = AssistPrompts.user(song),
            schema = schema,
            promptVersion = version,
            feature = feature,
        )
        val key = AiPolicy.cacheKey(feature, version, song.title, song.artist, extraKey)

        return when (val result = coach.ask(request, key, refresh = refresh)) {
            is AiResult.Success -> {
                val json = result.json
                    ?: return Result.failure(IllegalStateException("The coach did not return JSON"))
                Result.success(
                    Assisted(parse(json), result.provider, result.model, result.fromCache)
                )
            }
            else -> Result.failure(IllegalStateException(describe(result)))
        }
    }

    suspend fun similarSongs(
        song: Song,
        level: String = player.level.forPrompt,
        styles: String = player.stylesForPrompt,
        refresh: Boolean = false,
    ): Result<Assisted<List<SimilarSong>>> = assist(
        song = song,
        feature = "similar-songs",
        version = SongAssists.SIMILAR_VERSION,
        system = AssistPrompts.similarSystem(level, styles),
        schema = SongAssists.similarSchema,
        parse = { SongAssists.parseSimilar(it, song) },
        refresh = refresh,
    )

    /**
     * [feedback] is "too_easy" or "too_hard" from a previous estimate, and it is
     * part of the cache key as well as the prompt — otherwise telling the coach
     * it was wrong would return the cached answer it was wrong about.
     */
    suspend fun difficulty(
        song: Song,
        songbookSummary: String,
        previousRating: Int? = null,
        feedback: String? = null,
        level: String = player.level.forPrompt,
        styles: String = player.stylesForPrompt,
        refresh: Boolean = false,
    ): Result<Assisted<DifficultyEstimate>> = assist(
        song = song,
        feature = "difficulty",
        version = SongAssists.DIFFICULTY_VERSION,
        system = AssistPrompts.difficultySystem(level, styles, songbookSummary, previousRating, feedback),
        schema = SongAssists.difficultySchema,
        parse = SongAssists::parseDifficulty,
        extraKey = "${previousRating ?: ""}:${feedback ?: ""}",
        refresh = refresh,
    )

    suspend fun howToPlay(
        song: Song,
        level: String = player.level.forPrompt,
        refresh: Boolean = false,
    ): Result<Assisted<HowToPlay>> = assist(
        song = song,
        feature = "how-to-play",
        version = SongAssists.HOWTO_VERSION,
        system = AssistPrompts.howToSystem(level),
        schema = SongAssists.howToSchema,
        parse = SongAssists::parseHowToPlay,
        refresh = refresh,
    )

    /**
     * Turns a failure into something worth reading.
     *
     * The app's voice: say what happened and what still works, never a stack
     * trace and never a shrug.
     */
    private fun describe(result: AiResult): String = when (result) {
        is AiResult.RateLimited ->
            "Every provider you have a key for is busy" +
                (result.retryAfterSeconds?.let { " — try again in about ${it}s" } ?: " just now")
        is AiResult.Malformed -> "The coach answered, but not in a shape I could use."
        is AiResult.Failed -> result.message
        AiResult.NoKey -> "Add a free AI key in Me > Settings to unlock the coach."
        AiResult.Offline -> "No connection. Cached cards still open, and everything else works."
        is AiResult.Success -> "ok"
    }

    private companion object {
        /** How many earlier messages a follow-up carries. */
        const val MAX_TURNS = 12
    }
}
