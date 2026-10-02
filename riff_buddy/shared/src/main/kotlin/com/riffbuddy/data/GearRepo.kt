package com.riffbuddy.data

import com.riffbuddy.domain.gear.AmpKind
import com.riffbuddy.domain.gear.AmpModel
import com.riffbuddy.domain.gear.AmpProfile
import com.riffbuddy.domain.gear.EffectCategory
import com.riffbuddy.domain.gear.EffectModel
import com.riffbuddy.domain.gear.GuitarProfile
import com.riffbuddy.domain.gear.KnownGear
import com.riffbuddy.domain.gear.PedalProfile
import com.riffbuddy.domain.gear.PickupPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class SavedAmp(val id: Long, val name: String, val profile: AmpProfile)

data class SavedGuitar(val id: Long, val name: String, val profile: GuitarProfile, val defaultTuningId: Long? = null)

data class SavedPedal(val id: Long, val name: String, val profile: PedalProfile)

data class Rig(
    val id: Long,
    val name: String,
    val guitar: SavedGuitar?,
    val amp: SavedAmp?,
    val isDefault: Boolean,
    val pedals: List<SavedPedal> = emptyList(),
)

/**
 * Gear profiles: guitars, amps, pedals, and rigs made of them.
 *
 * **What is stored is the truth.** Controls, pickups, channels and notes come
 * from the row, so an edit reaches the next tone card. The reference profiles
 * shipped in code (the LT25, the EC-256) only lend what the editor does not
 * cover: the LT25's amp-model and effect descriptions, and a pickup's
 * description. Before the editor, the repository returned those references
 * whatever the row said — harmless while nothing could change a row.
 *
 * A rig that generated a tone card six months ago still describes the gear
 * as it was then, because the card keeps its own copy (cards outlive gear
 * changes).
 */
class GearRepo(private val db: Db) {

    // ---- reading -------------------------------------------------------------

    suspend fun guitars(): List<SavedGuitar> = withContext(Dispatchers.IO) {
        query("SELECT $GUITAR_COLUMNS FROM guitars ORDER BY name COLLATE NOCASE") { it.toGuitar() }
    }

    suspend fun amps(): List<SavedAmp> = withContext(Dispatchers.IO) {
        query("SELECT $AMP_COLUMNS FROM amps ORDER BY name COLLATE NOCASE") { it.toAmp() }
    }

    suspend fun pedals(): List<SavedPedal> = withContext(Dispatchers.IO) {
        query("SELECT $PEDAL_COLUMNS FROM pedals ORDER BY name COLLATE NOCASE") { it.toPedal() }
    }

    /** Every rig, the default first. */
    suspend fun rigs(): List<Rig> = withContext(Dispatchers.IO) { readRigs() }

    /** The default rig, which is what a tone card is generated against. */
    suspend fun defaultRig(): Rig? = withContext(Dispatchers.IO) { readRigs().firstOrNull() }

    /** How many tone cards were made for a rig, for the delete question. */
    suspend fun cardCount(rigId: Long): Int = withContext(Dispatchers.IO) {
        db.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM tone_cards WHERE rig_id = ?", arrayOf(rigId.toString()),
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    // ---- writing -------------------------------------------------------------

    suspend fun saveGuitar(id: Long, name: String, profile: GuitarProfile, defaultTuningId: Long?): Long =
        withContext(Dispatchers.IO) {
            writeGuitar(id, name, profile, defaultTuningId).also { if (id == 0L) intoDefaultRig("guitar_id", it) }
        }

    suspend fun saveAmp(id: Long, name: String, profile: AmpProfile): Long =
        withContext(Dispatchers.IO) {
            writeAmp(id, name, profile).also { if (id == 0L) intoDefaultRig("amp_id", it) }
        }

    /**
     * A new guitar or amp goes straight into the default rig if that rig has
     * none, and the first one makes a rig. Someone setting up adds "my guitar"
     * and "my amp" and expects tone cards to work; making them also build a
     * rig out of the two was a step nobody would guess.
     */
    private fun intoDefaultRig(column: String, gearId: Long) {
        val d = db.writableDatabase
        val rig = d.rawQuery("SELECT id, $column FROM rigs WHERE is_default = 1 LIMIT 1", null).use { c ->
            if (c.moveToFirst()) c.getLong(0) to c.isNull(1) else null
        }
        when {
            rig == null -> writeRig(0, "My rig", if (column == "guitar_id") gearId else null,
                if (column == "amp_id") gearId else null, emptyList(), isDefault = true)
            rig.second -> d.execSQL("UPDATE rigs SET $column = ? WHERE id = ?", arrayOf(gearId, rig.first))
        }
    }

    suspend fun savePedal(id: Long, name: String, profile: PedalProfile): Long = withContext(Dispatchers.IO) {
        val values = SqlValues().apply {
            put("name", name)
            put("make", profile.make)
            put("model", profile.model)
            put("type", profile.type)
            put("controls_json", GearJson.strings(profile.controls))
            put("notes", profile.notes)
        }
        upsert("pedals", id, values)
    }

    suspend fun saveRig(id: Long, name: String, guitarId: Long?, ampId: Long?, pedalIds: List<Long>, isDefault: Boolean): Long =
        withContext(Dispatchers.IO) {
            val d = db.writableDatabase
            d.beginTransaction()
            try {
                // Exactly one default: taking it clears it everywhere else.
                if (isDefault) d.execSQL("UPDATE rigs SET is_default = 0")
                val saved = writeRig(id, name, guitarId, ampId, pedalIds, isDefault)
                ensureADefault()
                d.setTransactionSuccessful()
                saved
            } finally {
                d.endTransaction()
            }
        }

    suspend fun makeDefault(rigId: Long): Unit = withContext(Dispatchers.IO) {
        val d = db.writableDatabase
        d.beginTransaction()
        try {
            d.execSQL("UPDATE rigs SET is_default = 0")
            d.execSQL("UPDATE rigs SET is_default = 1 WHERE id = ?", arrayOf(rigId))
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
    }

    /**
     * Deleting a rig used by tone cards asks what to do; the default is to
     * keep the cards archived. Kept cards are marked archived and lose
     * their rig link (the schema's SET NULL); otherwise they go with the rig.
     */
    suspend fun deleteRig(rigId: Long, keepCards: Boolean): Unit = withContext(Dispatchers.IO) {
        val d = db.writableDatabase
        d.beginTransaction()
        try {
            val args = arrayOf(rigId.toString())
            if (keepCards) d.execSQL("UPDATE tone_cards SET archived = 1 WHERE rig_id = ?", args)
            else d.delete("tone_cards", "rig_id = ?", args)
            d.delete("rigs", "id = ?", args)
            ensureADefault()
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
    }

    /** Rigs that used it keep going without a guitar (SET NULL). */
    suspend fun deleteGuitar(id: Long): Unit = withContext(Dispatchers.IO) {
        db.writableDatabase.delete("guitars", "id = ?", arrayOf(id.toString()))
    }

    suspend fun deleteAmp(id: Long): Unit = withContext(Dispatchers.IO) {
        db.writableDatabase.delete("amps", "id = ?", arrayOf(id.toString()))
    }

    /** Also taken out of every rig's pedal list, which is text and has no foreign key. */
    suspend fun deletePedal(id: Long): Unit = withContext(Dispatchers.IO) {
        val d = db.writableDatabase
        d.beginTransaction()
        try {
            val rigs = d.rawQuery("SELECT id, pedal_ids FROM rigs", null).use { c ->
                buildList { while (c.moveToNext()) add(c.getLong(0) to GearJson.ids(c.getString(1))) }
            }
            rigs.filter { (_, ids) -> id in ids }.forEach { (rigId, ids) ->
                d.update("rigs", SqlValues().apply { put("pedal_ids", GearJson.ids(ids - id)) },
                    "id = ?", arrayOf(rigId.toString()))
            }
            d.delete("pedals", "id = ?", arrayOf(id.toString()))
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
    }

    // ---- internals -------------------------------------------------------------

    private fun writeGuitar(id: Long, name: String, profile: GuitarProfile, defaultTuningId: Long?): Long {
        val values = SqlValues().apply {
            put("name", name)
            put("make", profile.make)
            put("model", profile.model)
            put("string_count", profile.stringCount)
            if (defaultTuningId != null) put("default_tuning_id", defaultTuningId) else putNull("default_tuning_id")
            put("pickups_json", GearJson.named(profile.pickups.map { it.label to it.description }))
            put("controls_json", GearJson.strings(profile.controls))
            put("notes", profile.notes)
        }
        return upsert("guitars", id, values)
    }

    private fun writeAmp(id: Long, name: String, profile: AmpProfile): Long {
        val values = SqlValues().apply {
            put("name", name)
            put("make", profile.make)
            put("model", profile.model)
            put("type", profile.kind.name.lowercase())
            put("channels_json", GearJson.strings(profile.channels))
            put("controls_json", GearJson.controls(profile.controls))
            put("models_json", GearJson.named(profile.ampModels.map { it.name to it.description }))
            put(
                "effects_json",
                JSONArray(profile.effects.map {
                    JSONObject().put("name", it.name).put("category", it.category.name).put("description", it.description)
                }).toString(),
            )
            put("details_json", JSONObject().put("editorApp", profile.editorApp).put("slotsPerCategory", profile.slotsPerCategory).toString())
            put("notes", profile.notes)
        }
        return upsert("amps", id, values)
    }

    private fun writeRig(id: Long, name: String, guitarId: Long?, ampId: Long?, pedalIds: List<Long>, isDefault: Boolean): Long {
        val values = SqlValues().apply {
            put("name", name)
            if (guitarId != null) put("guitar_id", guitarId) else putNull("guitar_id")
            if (ampId != null) put("amp_id", ampId) else putNull("amp_id")
            put("pedal_ids", GearJson.ids(pedalIds))
            put("is_default", if (isDefault) 1 else 0)
        }
        return upsert("rigs", id, values)
    }

    private fun upsert(table: String, id: Long, values: SqlValues): Long =
        if (id == 0L) db.writableDatabase.insert(table, null, values)
        else {
            db.writableDatabase.update(table, values, "id = ?", arrayOf(id.toString()))
            id
        }

    /** If rigs remain and none is the default, the oldest becomes it. */
    private fun ensureADefault() {
        val d = db.writableDatabase
        val hasDefault = d.rawQuery("SELECT 1 FROM rigs WHERE is_default = 1 LIMIT 1", null).use { it.moveToFirst() }
        if (!hasDefault) d.execSQL("UPDATE rigs SET is_default = 1 WHERE id = (SELECT MIN(id) FROM rigs)")
    }

    private fun readRigs(): List<Rig> {
        val guitars = query("SELECT $GUITAR_COLUMNS FROM guitars") { it.toGuitar() }.associateBy { it.id }
        val amps = query("SELECT $AMP_COLUMNS FROM amps") { it.toAmp() }.associateBy { it.id }
        val pedals = query("SELECT $PEDAL_COLUMNS FROM pedals") { it.toPedal() }.associateBy { it.id }
        return query("SELECT id, name, guitar_id, amp_id, is_default, pedal_ids FROM rigs ORDER BY is_default DESC, id ASC") { c ->
            Rig(
                id = c.getLong(0),
                name = c.requireString(1),
                guitar = if (c.isNull(2)) null else guitars[c.getLong(2)],
                amp = if (c.isNull(3)) null else amps[c.getLong(3)],
                isDefault = c.getInt(4) == 1,
                pedals = GearJson.ids(c.getString(5)).mapNotNull { pedals[it] },
            )
        }
    }

    private fun <T> query(sql: String, map: (SqlCursor) -> T): List<T> =
        db.readableDatabase.rawQuery(sql, null).use { c -> buildList { while (c.moveToNext()) add(map(c)) } }

    private fun SqlCursor.toGuitar(): SavedGuitar {
        val make = requireString(2)
        val model = requireString(3)
        val reference = KnownGear.guitars.firstOrNull { it.model == model }
        val pickups = GearJson.named(getString(6))
        val controls = GearJson.strings(getString(7))
        return SavedGuitar(
            id = getLong(0),
            name = requireString(1),
            profile = GuitarProfile(
                make = make,
                model = model,
                stringCount = getInt(4),
                // A description stored with the pickup wins; rows saved before
                // descriptions were kept fall back to the known model's.
                pickups = pickups.map { (label, description) ->
                    PickupPosition(label, description.ifBlank { reference?.pickups?.firstOrNull { it.label == label }?.description ?: "" })
                },
                controls = controls,
                // Read from the controls rather than stored twice: a guitar has
                // a coil-split exactly when one of its controls is one.
                hasCoilSplit = controls.any { it.contains("coil", ignoreCase = true) || it.contains("split", ignoreCase = true) },
                notes = requireString(8),
            ),
            defaultTuningId = if (isNull(5)) null else getLong(5),
        )
    }

    private fun SqlCursor.toAmp(): SavedAmp {
        val model = requireString(3)
        val reference = KnownGear.amps.firstOrNull { it.model == model }
        val models = GearJson.named(getString(7))
        val details = runCatching { JSONObject(getString(10) ?: "{}") }.getOrElse { JSONObject() }
        val effects = runCatching { JSONArray(getString(8)) }.getOrNull()?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val category = runCatching { EffectCategory.valueOf(o.optString("category")) }.getOrDefault(EffectCategory.STOMP)
                val description = o.optString("description").ifBlank {
                    reference?.effects?.firstOrNull { it.name == name }?.description ?: ""
                }
                EffectModel(name, category, description)
            }
        }.orEmpty()
        val base = reference ?: AmpProfile(make = requireString(2), model = model, kind = AmpKind.ANALOG, controls = emptyList())
        return SavedAmp(
            id = getLong(0),
            name = requireString(1),
            profile = base.copy(
                make = requireString(2),
                model = model,
                kind = runCatching { AmpKind.valueOf(requireString(4).uppercase()) }.getOrDefault(AmpKind.ANALOG),
                channels = GearJson.strings(getString(5)),
                controls = GearJson.controls(getString(6)),
                ampModels = models.map { (n, description) ->
                    AmpModel(n, description.ifBlank { reference?.ampModels?.firstOrNull { it.name == n }?.description ?: "" })
                },
                effects = effects,
                notes = requireString(9),
                // Rows from before v10 have no details; the known model's (or
                // the defaults) stand in.
                editorApp = details.optString("editorApp", base.editorApp).ifBlank { base.editorApp },
                slotsPerCategory = details.optInt("slotsPerCategory", base.slotsPerCategory).coerceAtLeast(1),
            ),
        )
    }

    private fun SqlCursor.toPedal() = SavedPedal(
        id = getLong(0),
        name = requireString(1),
        profile = PedalProfile(
            make = requireString(2),
            model = requireString(3),
            type = requireString(4),
            controls = GearJson.strings(getString(5)),
            notes = requireString(6),
        ),
    )

    private companion object {
        const val GUITAR_COLUMNS = "id, name, make, model, string_count, default_tuning_id, pickups_json, controls_json, notes"
        const val AMP_COLUMNS = "id, name, make, model, type, channels_json, controls_json, models_json, effects_json, notes, details_json"
        const val PEDAL_COLUMNS = "id, name, make, model, type, controls_json, notes"
    }
}
