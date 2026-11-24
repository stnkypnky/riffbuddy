package com.riffbuddy.ai

import com.riffbuddy.domain.gear.AmpProfile
import com.riffbuddy.domain.gear.ControlRole
import com.riffbuddy.domain.gear.EffectCategory
import com.riffbuddy.domain.gear.GuitarProfile
import com.riffbuddy.domain.gear.Reach
import com.riffbuddy.domain.toFixed
import com.riffbuddy.json.JSONArray
import com.riffbuddy.json.JSONObject
import kotlin.math.round

/** One control set to a value. */
data class ControlSetting(
    val name: String,
    val value: Double,
    val scale: String,
    val reach: Reach,
    val note: String = "",
    /** The dial's real ends; [scale] as text cannot carry a negative start. */
    val min: Double = 0.0,
    val max: Double = 10.0,
    /** What the AI asked for, when it was off the dial and was clamped. */
    val clampedFrom: Double? = null,
)

data class EffectSetting(
    val category: EffectCategory,
    val model: String,
    val settings: List<ControlSetting> = emptyList(),
    val purpose: String = "",
)

data class ToneCard(
    val toneDescription: String,
    val ampModel: String?,
    val pickup: String?,
    val guitarNotes: String = "",
    val ampControls: List<ControlSetting> = emptyList(),
    val effects: List<EffectSetting> = emptyList(),
    val playingTips: List<String> = emptyList(),
    val assumptions: List<String> = emptyList(),
    val confidence: String = "medium",
    val caveats: String = "",
    /** Things the model asked for that the rig cannot do ("not on your amp"). */
    val unavailable: List<String> = emptyList(),
)

/**
 * The tone card's schema and its validation.
 *
 * Both are built *from the rig*, not written out by hand. That is the whole
 * idea: the list of allowed amp models is literally the twenty the LT25 has, so
 * a provider that honors schemas cannot return an invented one — and for a
 * provider that only offers JSON mode, [validate] catches the same thing on this
 * end. An amp model that does not exist is the single most damaging output this
 * feature could produce, because it reads as authoritative and sends the player
 * hunting through a menu for something that was never there.
 */
object ToneCardSchema {

    const val PROMPT_VERSION = "tone-v1"

    /** A JSON schema whose enums are this rig's actual capabilities. */
    fun forRig(amp: AmpProfile, guitar: GuitarProfile?): JSONObject {
        val toneControlNames = amp.toneControls.map { it.name }
        val effectCategories = EffectCategory.entries.map { it.name }

        fun enumOf(values: List<String>) = JSONObject()
            .put("type", "string")
            .put("enum", JSONArray(values))

        val controlItem = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put("name", enumOf(toneControlNames))
                    .put("value", JSONObject().put("type", "number"))
            )
            .put("required", JSONArray(listOf("name", "value")))

        val effectItem = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put("category", enumOf(effectCategories))
                    .put("model", enumOf(amp.effects.map { it.name }))
                    .put("purpose", JSONObject().put("type", "string"))
            )
            .put("required", JSONArray(listOf("category", "model")))

        val properties = JSONObject()
            .put("tone_description", JSONObject().put("type", "string"))
            .put("amp_controls", JSONObject().put("type", "array").put("items", controlItem))
            .put("effects", JSONObject().put("type", "array").put("items", effectItem))
            .put(
                "playing_tips",
                JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")),
            )
            .put(
                "assumptions",
                JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")),
            )
            .put("confidence", enumOf(listOf("low", "medium", "high")))
            .put("caveats", JSONObject().put("type", "string"))

        if (amp.ampModels.isNotEmpty()) {
            properties.put("amp_model", enumOf(amp.ampModels.map { it.name }))
        }
        guitar?.pickups?.takeIf { it.isNotEmpty() }?.let {
            properties.put("pickup", enumOf(it.map { p -> p.label }))
        }

        return JSONObject()
            .put("type", "object")
            .put("properties", properties)
            .put(
                "required",
                JSONArray(listOf("tone_description", "amp_controls", "confidence")),
            )
    }

    /**
     * Turns a reply into a card, discarding anything the rig cannot do.
     *
     * Discarding rather than failing: a card that is right about nine things and
     * invented a tenth is still worth showing, with the tenth moved to
     * "not on your amp" where the player can see it was asked for and why it is not
     * there. Rejecting the whole card would spend another free-tier request to
     * punish one bad field.
     */
    fun validate(json: JSONObject, amp: AmpProfile, guitar: GuitarProfile?): ToneCard {
        val unavailable = mutableListOf<String>()

        val ampModel = json.optString("amp_model").takeIf { it.isNotBlank() }?.let { raw ->
            val match = amp.ampModels.firstOrNull { it.name.equals(raw, ignoreCase = true) }
            if (match == null) {
                unavailable += "Amp model \"$raw\" — this amp does not have one by that name"
                null
            } else match.name
        }

        val pickup = json.optString("pickup").takeIf { it.isNotBlank() }?.let { raw ->
            val match = guitar?.pickups?.firstOrNull { it.label.equals(raw, ignoreCase = true) }
            if (match == null && guitar != null) {
                unavailable += "Pickup position \"$raw\""
                null
            } else match?.label
        }

        val controls = mutableListOf<ControlSetting>()
        json.optJSONArray("amp_controls")?.let { array ->
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val name = item.optString("name").takeIf { it.isNotBlank() } ?: continue
                val control = amp.control(name)

                if (control == null) {
                    unavailable += "$name — not a control on this amp"
                    continue
                }
                // A level control is not part of a tone. Silently accepting a
                // master setting would make the card wrong at every volume other
                // than the one it was imagined at.
                if (control.role == ControlRole.LEVEL) {
                    unavailable += "${control.name} — that is loudness, not tone; set it to taste"
                    continue
                }

                val raw = item.optDouble("value", Double.NaN)
                if (raw.isNaN()) continue
                val clamped = raw.coerceIn(control.min, control.max)
                controls += ControlSetting(
                    name = control.name,
                    value = clamped,
                    scale = control.scale,
                    reach = control.reach,
                    note = control.note,
                    min = control.min,
                    max = control.max,
                    clampedFrom = raw.takeIf { clamped != raw },
                )
            }
        }

        val effects = mutableListOf<EffectSetting>()
        val usedCategories = mutableSetOf<EffectCategory>()
        json.optJSONArray("effects")?.let { array ->
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val modelName = item.optString("model").takeIf { it.isNotBlank() } ?: continue
                val known = amp.effects.firstOrNull { it.name.equals(modelName, ignoreCase = true) }
                if (known == null) {
                    unavailable += "Effect \"$modelName\" — not on this amp"
                    continue
                }
                // One slot per category: a second overdrive describes something
                // the amp cannot be made to do.
                if (!usedCategories.add(known.category)) {
                    unavailable += "${known.name} — only one ${known.category.label.lowercase()} fits in a preset"
                    continue
                }
                effects += EffectSetting(
                    category = known.category,
                    model = known.name,
                    purpose = item.optString("purpose"),
                )
            }
        }

        return ToneCard(
            toneDescription = json.optString("tone_description"),
            ampModel = ampModel,
            pickup = pickup,
            ampControls = controls,
            effects = effects.sortedBy { it.category.ordinal },
            // Six tips at most. More than that is not advice, it is a lecture,
            // and nobody reads it with a guitar on.
            playingTips = json.optJSONArray("playing_tips").toStringList().take(6),
            assumptions = json.optJSONArray("assumptions").toStringList(),
            confidence = json.optString("confidence").takeIf {
                it in listOf("low", "medium", "high")
            } ?: "medium",
            caveats = json.optString("caveats"),
            unavailable = unavailable,
        )
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }
    }
}

/**
 * "Copy as text": the card as plain lines, for a message or a note.
 * Numbers beside names, as on the faceplate; clamped values say so.
 */
object ToneCardText {
    fun format(card: ToneCard, heading: String): String = buildString {
        appendLine(heading)
        appendLine(card.toneDescription)
        card.ampModel?.let { appendLine("Amp model: $it") }
        card.pickup?.let { appendLine("Pickup: $it") }
        if (card.guitarNotes.isNotBlank()) appendLine("Guitar: ${card.guitarNotes}")
        if (card.ampControls.isNotEmpty()) {
            appendLine()
            card.ampControls.forEach { c ->
                append("${c.name}: ${num(c.value)}")
                if (c.reach == Reach.DISPLAY) append(" (in the amp's display)")
                c.clampedFrom?.let { append(" — the coach said ${num(it)}, off the dial") }
                appendLine()
            }
        }
        if (card.effects.isNotEmpty()) {
            appendLine()
            card.effects.forEach { e ->
                append("${e.category.label}: ${e.model}")
                if (e.settings.isNotEmpty()) append(" — " + e.settings.joinToString(", ") { "${it.name} ${num(it.value)}" })
                appendLine()
            }
        }
        if (card.playingTips.isNotEmpty()) {
            appendLine()
            card.playingTips.forEach { appendLine("· $it") }
        }
        appendLine()
        append(card.caveats.ifBlank { "A starting point — finish by ear at your own volume." })
    }

    private fun num(v: Double) = if (v == round(v)) v.toLong().toString() else v.toFixed(1)
}

/**
 * What changed between two versions of a card, from their stored JSON — not from
 * re-validated cards, so the list does not depend on today's rig.
 */
object ToneCardDiff {
    fun changes(previousJson: String, nextJson: String): List<String> {
        val a = runCatching { JSONObject(previousJson) }.getOrNull() ?: return emptyList()
        val b = runCatching { JSONObject(nextJson) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<String>()
        fun field(key: String, label: String) {
            val x = a.optString(key).trim()
            val y = b.optString(key).trim()
            if (x != y) out += when {
                x.isEmpty() -> "$label: $y"
                y.isEmpty() -> "$label removed"
                else -> "$label: $x → $y"
            }
        }
        field("amp_model", "Amp model")
        field("pickup", "Pickup")

        val before = controls(a)
        val after = controls(b)
        (before.keys + after.keys).distinctBy { it.lowercase() }.forEach { name ->
            val x = before.entries.firstOrNull { it.key.equals(name, true) }?.value
            val y = after.entries.firstOrNull { it.key.equals(name, true) }?.value
            when {
                x == null && y != null -> out += "$name set to ${num(y)}"
                x != null && y == null -> out += "$name no longer set"
                x != null && y != null && x != y -> out += "$name ${num(x)} → ${num(y)}"
            }
        }

        val fxBefore = effects(a)
        val fxAfter = effects(b)
        (fxAfter - fxBefore).forEach { out += "$it added" }
        (fxBefore - fxAfter).forEach { out += "$it removed" }
        return out
    }

    private fun controls(o: JSONObject): Map<String, Double> {
        val array = o.optJSONArray("amp_controls") ?: return emptyMap()
        return (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            val name = item.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val v = item.optDouble("value", Double.NaN).takeIf { !it.isNaN() } ?: return@mapNotNull null
            name to v
        }.toMap()
    }

    private fun effects(o: JSONObject): Set<String> {
        val array = o.optJSONArray("effects") ?: return emptySet()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optString("model")?.takeIf { m -> m.isNotBlank() } }.toSet()
    }

    private fun num(v: Double) = if (v == round(v)) v.toLong().toString() else v.toFixed(1)
}

