package com.riffbuddy.data

import com.riffbuddy.domain.gear.AmpControl
import com.riffbuddy.domain.gear.ControlKind
import com.riffbuddy.domain.gear.ControlRole
import com.riffbuddy.domain.gear.Reach
import org.json.JSONArray
import org.json.JSONObject

/**
 * The JSON columns of the gear tables, both ways.
 *
 * Pure apart from org.json, so the round trip is tested on the JVM. Reading
 * is forgiving: a field missing from an older row (kind and options came in
 * with the gear editor) takes its default rather than failing the row.
 */
object GearJson {

    fun controls(list: List<AmpControl>): String = JSONArray(
        list.map {
            JSONObject()
                .put("name", it.name)
                .put("reach", it.reach.name)
                .put("role", it.role.name)
                .put("min", it.min)
                .put("max", it.max)
                .put("note", it.note)
                .put("kind", it.kind.name)
                .put("options", JSONArray(it.options))
        }
    ).toString()

    fun controls(json: String?): List<AmpControl> {
        val array = runCatching { JSONArray(json ?: "[]") }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            AmpControl(
                name = name,
                reach = enumOr(o.optString("reach"), Reach.PANEL),
                role = enumOr(o.optString("role"), ControlRole.TONE),
                min = o.optDouble("min", 0.0),
                max = o.optDouble("max", 10.0),
                note = o.optString("note"),
                kind = enumOr(o.optString("kind"), ControlKind.KNOB),
                options = strings(o.optJSONArray("options")?.toString()),
            )
        }
    }

    fun strings(list: List<String>): String = JSONArray(list).toString()

    fun strings(json: String?): List<String> {
        val array = runCatching { JSONArray(json ?: "[]") }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            // Seeded effects are objects with a name; everything else is a plain string.
            array.optJSONObject(i)?.optString("name") ?: array.optString(i).takeIf { it.isNotBlank() }
        }
    }

    /**
     * Names with descriptions (amp models, pickups), as objects. Read back
     * with [named], which also accepts the plain strings stored before
     * descriptions were kept.
     */
    fun named(list: List<Pair<String, String>>): String = JSONArray(
        list.map { (name, description) -> JSONObject().put("name", name).put("description", description) }
    ).toString()

    fun named(json: String?): List<Pair<String, String>> {
        val array = runCatching { JSONArray(json ?: "[]") }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i)
            val name = (o?.optString("name") ?: array.optString(i)).trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            name to (o?.optString("description")?.trim() ?: "")
        }
    }

    /** Pedal ids as the rigs table stores them: "3,7". */
    fun ids(text: String?): List<Long> =
        text?.split(",")?.mapNotNull { it.trim().toLongOrNull() } ?: emptyList()

    fun ids(list: List<Long>): String = list.joinToString(",")

    private inline fun <reified E : Enum<E>> enumOr(name: String, default: E): E =
        runCatching { enumValueOf<E>(name) }.getOrDefault(default)
}
