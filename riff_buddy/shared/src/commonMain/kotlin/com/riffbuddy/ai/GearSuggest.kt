package com.riffbuddy.ai

import com.riffbuddy.domain.gear.AmpControl
import com.riffbuddy.domain.gear.AmpModel
import com.riffbuddy.domain.gear.ControlKind
import com.riffbuddy.domain.gear.ControlRole
import com.riffbuddy.domain.gear.EffectCategory
import com.riffbuddy.domain.gear.EffectModel
import com.riffbuddy.domain.gear.PickupPosition
import com.riffbuddy.domain.gear.Reach
import com.riffbuddy.json.JSONArray
import com.riffbuddy.json.JSONObject

/**
 * "Suggest controls from the model name", grown into a full profile: the
 * AI drafts everything a tone card uses about a piece of gear, and the player
 * checks it before it is saved.
 *
 * The built-in Mustang LT25 profile was transcribed from Fender's manual: its
 * panel knobs, which knob is loudness rather than tone, the Middle that lives
 * only in the display, twenty amp models and twenty-six effects. Anyone with
 * different gear deserves tone cards that know as much about theirs, and
 * nobody can be asked to type in twenty amp models. So the coach drafts the
 * same things — and because a model asked to *recall* gear invents with
 * confidence, the draft is always shown for review, says when it is
 * unsure, and is saved only when Save is pressed.
 */
object GearSuggest {

    enum class Kind(val noun: String) { AMP("amplifier"), GUITAR("guitar"), PEDAL("pedal or multi-effects unit") }

    data class Suggestion(
        val controls: List<AmpControl>,
        val pickups: List<PickupPosition>,
        val channels: List<String>,
        val confident: Boolean,
        val note: String,
        val ampModels: List<AmpModel> = emptyList(),
        val effects: List<EffectModel> = emptyList(),
        val editorApp: String = "",
        val effectsPerCategory: Int = 1,
        val pedalType: String = "",
    )

    const val PROMPT_VERSION = "gear-suggest-v2"

    fun request(kind: Kind, make: String, model: String) = AiRequest(
        systemPrompt = buildString {
            appendLine("You describe a piece of guitar gear for a practice app that writes tone setups for it.")
            appendLine("Describe only what the named model actually has. A short true answer beats a long invented one.")
            appendLine()
            appendLine("controls: the front-panel controls, in panel order, left to right.")
            appendLine("  Knobs: their printed range (most are 0 to 10). Switches: on/off. Selectors: their positions.")
            when (kind) {
                Kind.AMP -> {
                    appendLine("  role: \"level\" for a control that only sets loudness in the room (master volume),")
                    appendLine("  \"tone\" for everything that shapes the sound, including a channel or preset volume.")
                    appendLine("  For a modeling amp, also list settings every preset has but the panel lacks a knob for")
                    appendLine("  (for example Middle), with reach \"display\". Panel knobs have reach \"panel\".")
                    appendLine("channels: its channels or modes, if it has more than one.")
                    appendLine("ampModels: for a modeling amp, every amp model or voice it offers, by the exact name on the")
                    appendLine("  amp or in its app, each with a few words on what it models. Empty for a non-modeling amp.")
                    appendLine("effects: built-in effects, by exact name, each with a category (stomp for drive, fuzz,")
                    appendLine("  compressor, wah and similar; mod for chorus, flanger, phaser, tremolo and similar;")
                    appendLine("  delay; reverb) and a few words. Empty if it has none.")
                    appendLine("effectsPerCategory: how many effects of one category a single preset can hold (usually 1).")
                    appendLine("editorApp: the name of the app or program that edits its presets, or empty if none.")
                }
                Kind.GUITAR -> {
                    appendLine("  Include a coil split or tap if this model has one, and no split if it does not.")
                    appendLine("pickups: the pickup selector's positions, each with a few words on the pickups it uses")
                    appendLine("  and the sound (for example Bridge: humbucker, tight and bright, for riffs).")
                }
                Kind.PEDAL -> {
                    appendLine("pedalType: what it is, in two or three words (for example overdrive, digital delay, multi-effects).")
                }
            }
            appendLine()
            appendLine("If you are not sure of this exact model, set confident to false and say why in note.")
            appendLine("Never guess to fill a list: leave it empty rather than invent names.")
        },
        userPrompt = "${kind.noun}: ${make.trim()} ${model.trim()}".trim(),
        schema = schema(kind),
        promptVersion = PROMPT_VERSION,
        feature = "gear_suggest",
    )

    fun cacheKey(kind: Kind, make: String, model: String) =
        "$PROMPT_VERSION|${kind.name}|${make.trim().lowercase()}|${model.trim().lowercase()}"

    private fun str() = JSONObject().put("type", "string")
    private fun enum(vararg values: String) = JSONObject().put("type", "string").put("enum", JSONArray(values.toList()))
    private fun array(items: JSONObject) = JSONObject().put("type", "array").put("items", items)
    private fun obj(properties: JSONObject, vararg required: String) = JSONObject()
        .put("type", "object").put("properties", properties).put("required", JSONArray(required.toList()))

    /** Only the fields this kind of gear has: models answer the whole schema, and a pedal once came back with "Channels: mono". */
    fun schema(kind: Kind): JSONObject {
        val control = JSONObject()
            .put("name", str())
            .put("type", enum("knob", "switch", "selector"))
            .put("min", JSONObject().put("type", "number"))
            .put("max", JSONObject().put("type", "number"))
            .put("options", array(str()))
        if (kind == Kind.AMP) control.put("role", enum("tone", "level")).put("reach", enum("panel", "display"))
        val properties = JSONObject()
            .put("controls", array(obj(control, "name", "type")))
            .put("confident", JSONObject().put("type", "boolean"))
            .put("note", str())
        when (kind) {
            Kind.AMP -> properties
                .put("channels", array(str()))
                .put("ampModels", array(obj(JSONObject().put("name", str()).put("description", str()), "name")))
                .put("effects", array(obj(
                    JSONObject().put("name", str()).put("category", enum("stomp", "mod", "delay", "reverb")).put("description", str()),
                    "name", "category",
                )))
                .put("effectsPerCategory", JSONObject().put("type", "integer"))
                .put("editorApp", str())
            Kind.GUITAR -> properties
                .put("pickups", array(obj(JSONObject().put("position", str()).put("description", str()), "position")))
            Kind.PEDAL -> properties.put("pedalType", str())
        }
        return obj(properties, "controls", "confident")
    }

    /**
     * Reads the reply defensively: unnamed things are dropped, duplicate names
     * kept once, an inverted range put the right way round, and every list
     * capped — controls at 16, the most the faceplate drawing has room for.
     * A v1-style reply (pickups as plain strings) still reads.
     */
    fun parse(json: JSONObject): Suggestion {
        val seen = mutableSetOf<String>()
        val array = json.optJSONArray("controls") ?: JSONArray()
        val controls = (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name").trim().takeIf { it.isNotEmpty() && seen.add(it.lowercase()) } ?: return@mapNotNull null
            val kind = when (o.optString("type")) {
                "switch" -> ControlKind.SWITCH
                "selector" -> ControlKind.SELECTOR
                else -> ControlKind.KNOB
            }
            val a = o.optDouble("min", 0.0).takeIf { !it.isNaN() } ?: 0.0
            val b = o.optDouble("max", 10.0).takeIf { !it.isNaN() } ?: 10.0
            AmpControl(
                name = name,
                kind = kind,
                reach = if (o.optString("reach") == "display") Reach.DISPLAY else Reach.PANEL,
                role = if (o.optString("role") == "level") ControlRole.LEVEL else ControlRole.TONE,
                min = minOf(a, b),
                max = maxOf(a, b).takeIf { it > minOf(a, b) } ?: (minOf(a, b) + 10),
                options = if (kind == ControlKind.SELECTOR) strings(o.optJSONArray("options")) else emptyList(),
            )
        }.take(MAX_CONTROLS)

        val pickups = json.optJSONArray("pickups")?.let { p ->
            (0 until p.length()).mapNotNull { i ->
                val o = p.optJSONObject(i)
                val label = (o?.optString("position") ?: p.optString(i)).trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                PickupPosition(label, o?.optString("description")?.trim() ?: "")
            }.distinctBy { it.label.lowercase() }.take(MAX_PICKUPS)
        }.orEmpty()

        val models = named(json.optJSONArray("ampModels")).map { (n, d) -> AmpModel(n, d) }.take(MAX_MODELS)
        val effects = json.optJSONArray("effects")?.let { e ->
            (0 until e.length()).mapNotNull { i ->
                val o = e.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("name").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val category = when (o.optString("category")) {
                    "mod" -> EffectCategory.MOD
                    "delay" -> EffectCategory.DELAY
                    "reverb" -> EffectCategory.REVERB
                    else -> EffectCategory.STOMP
                }
                EffectModel(name, category, o.optString("description").trim())
            }.distinctBy { it.name.lowercase() }.take(MAX_EFFECTS)
        }.orEmpty()

        return Suggestion(
            controls = controls,
            pickups = pickups,
            channels = strings(json.optJSONArray("channels")),
            confident = json.optBoolean("confident", false),
            note = json.optString("note"),
            ampModels = models,
            effects = effects,
            editorApp = json.optString("editorApp").trim(),
            effectsPerCategory = json.optInt("effectsPerCategory", 1).coerceIn(1, 4),
            pedalType = json.optString("pedalType").trim(),
        )
    }

    /** Whether the reply said anything worth reviewing. */
    fun Suggestion.isEmpty(): Boolean =
        controls.isEmpty() && pickups.isEmpty() && ampModels.isEmpty() && effects.isEmpty()

    const val MAX_CONTROLS = 16
    const val MAX_PICKUPS = 8
    const val MAX_MODELS = 60
    const val MAX_EFFECTS = 80

    private fun named(a: JSONArray?): List<Pair<String, String>> {
        if (a == null) return emptyList()
        val seen = mutableSetOf<String>()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i)
            val name = (o?.optString("name") ?: a.optString(i)).trim().takeIf { it.isNotEmpty() && seen.add(it.lowercase()) }
                ?: return@mapNotNull null
            name to (o?.optString("description")?.trim() ?: "")
        }
    }

    private fun strings(a: JSONArray?): List<String> =
        if (a == null) emptyList()
        else (0 until a.length()).mapNotNull { a.optString(it).trim().takeIf { s -> s.isNotEmpty() } }.distinct()
}
