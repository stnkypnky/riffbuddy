package com.riffbuddy.ai

import com.riffbuddy.domain.Song
import com.riffbuddy.domain.Songbook
import com.riffbuddy.json.JSONArray
import com.riffbuddy.json.JSONObject

/** One suggestion from "songs like this". */
data class SimilarSong(
    val title: String,
    val artist: String,
    val reason: String,
    val tuning: String,
    val band: String,
)

data class DifficultyReason(val factor: String, val detail: String)

data class DifficultyEstimate(
    val rating: Int,
    val comparedToYou: String,
    val reasons: List<DifficultyReason>,
    val prerequisites: List<String>,
    val approach: List<String>,
)

data class SongSection(val name: String, val whatHappens: String, val watchOut: String)

data class HowToPlay(
    val overview: String,
    val sections: List<SongSection>,
    val techniques: List<String>,
    val pitfalls: List<String>,
)

/**
 * The three song assists, their schemas and their validation.
 *
 * All three are estimates about music the coach has not heard, from a model that
 * will produce a fluent answer whether or not it knows the song. So every one of
 * them is labeled as an estimate in the UI, and the parsing here is deliberately
 * forgiving about missing fields and strict about the few things that would be
 * actively misleading.
 */
object SongAssists {

    const val SIMILAR_VERSION = "similar-v1"
    const val DIFFICULTY_VERSION = "difficulty-v1"
    const val HOWTO_VERSION = "howto-v1"

    private fun array(itemType: JSONObject) =
        JSONObject().put("type", "array").put("items", itemType)

    private fun strings() = array(JSONObject().put("type", "string"))

    private fun enumOf(values: List<String>) =
        JSONObject().put("type", "string").put("enum", JSONArray(values))

    // ---- songs like this ---------------------------------------------------

    val similarSchema: JSONObject
        get() = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject().put(
                    "suggestions",
                    array(
                        JSONObject()
                            .put("type", "object")
                            .put(
                                "properties",
                                JSONObject()
                                    .put("title", JSONObject().put("type", "string"))
                                    .put("artist", JSONObject().put("type", "string"))
                                    .put("reason", JSONObject().put("type", "string"))
                                    .put("tuning", JSONObject().put("type", "string"))
                                    .put("band", enumOf(listOf("easier", "similar", "harder")))
                            )
                            .put("required", JSONArray(listOf("title", "artist", "reason")))
                    )
                )
            )
            .put("required", JSONArray(listOf("suggestions")))

    /**
     * "Songs like this" never returns the song it was asked about.
     *
     * Models do suggest the song you asked about, especially when it is famous.
     * The comparison reuses the songbook's own normalizer, so "Don't Stop
     * Believin'" and "Dont Stop Believin" are recognized as the same song rather
     * than slipping through on an apostrophe.
     */
    fun parseSimilar(json: JSONObject, source: Song): List<SimilarSong> {
        val sourceTitle = Songbook.normalize(source.title)
        val suggestions = json.optJSONArray("suggestions") ?: return emptyList()
        return (0 until suggestions.length()).mapNotNull { i ->
            val o = suggestions.optJSONObject(i) ?: return@mapNotNull null
            val title = o.optString("title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (Songbook.normalize(title) == sourceTitle) return@mapNotNull null
            SimilarSong(
                title = title,
                artist = o.optString("artist"),
                reason = o.optString("reason"),
                tuning = o.optString("tuning"),
                band = o.optString("band").takeIf { it in listOf("easier", "similar", "harder") } ?: "similar",
            )
        }.distinctBy { Songbook.normalize(it.title) + Songbook.normalize(it.artist) }.take(8)
    }

    // ---- how hard is this for me -------------------------------------------

    val difficultySchema: JSONObject
        get() = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put(
                        "difficulty",
                        JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 10),
                    )
                    .put("compared_to_you", JSONObject().put("type", "string"))
                    .put(
                        "reasons",
                        array(
                            JSONObject()
                                .put("type", "object")
                                .put(
                                    "properties",
                                    JSONObject()
                                        .put(
                                            "factor",
                                            enumOf(
                                                listOf(
                                                    "tempo", "technique", "tuning", "stamina",
                                                    "tricky sections", "chords", "theory",
                                                )
                                            )
                                        )
                                        .put("detail", JSONObject().put("type", "string"))
                                )
                        )
                    )
                    .put("prerequisites", strings())
                    .put("approach", strings())
            )
            .put("required", JSONArray(listOf("difficulty")))

    fun parseDifficulty(json: JSONObject): DifficultyEstimate {
        val reasons = json.optJSONArray("reasons").let { array ->
            if (array == null) emptyList() else (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val factor = o.optString("factor").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                DifficultyReason(factor, o.optString("detail"))
            }
        }
        return DifficultyEstimate(
            // Clamped rather than trusted: an out-of-range number rendered on a
            // 1-10 scale would draw a bar off the end of its track.
            rating = json.optInt("difficulty", 5).coerceIn(1, 10),
            comparedToYou = json.optString("compared_to_you"),
            reasons = reasons,
            prerequisites = json.optJSONArray("prerequisites").strings(),
            // A three-step approach. More than three is a plan nobody
            // follows; the cap keeps it to something doable this week.
            approach = json.optJSONArray("approach").strings().take(3),
        )
    }

    // ---- explain how to play it --------------------------------------------

    val howToSchema: JSONObject
        get() = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put("overview", JSONObject().put("type", "string"))
                    .put(
                        "sections",
                        array(
                            JSONObject()
                                .put("type", "object")
                                .put(
                                    "properties",
                                    JSONObject()
                                        .put("name", JSONObject().put("type", "string"))
                                        .put("what_happens", JSONObject().put("type", "string"))
                                        .put("watch_out", JSONObject().put("type", "string"))
                                )
                        )
                    )
                    .put("techniques", strings())
                    .put("pitfalls", strings())
            )
            .put("required", JSONArray(listOf("overview")))

    fun parseHowToPlay(json: JSONObject): HowToPlay {
        val sections = json.optJSONArray("sections").let { array ->
            if (array == null) emptyList() else (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                SongSection(name, o.optString("what_happens"), o.optString("watch_out"))
            }
        }
        return HowToPlay(
            overview = json.optString("overview"),
            sections = sections,
            techniques = json.optJSONArray("techniques").strings(),
            pitfalls = json.optJSONArray("pitfalls").strings(),
        )
    }

    private fun JSONArray?.strings(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }
    }
}
