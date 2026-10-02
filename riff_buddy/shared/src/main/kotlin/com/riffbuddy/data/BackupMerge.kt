package com.riffbuddy.data

/**
 * The database as a merge sees it. `BackupRepo` implements it over SQLite;
 * tests implement it over maps, which is why the merge itself has no
 * platform code in it.
 */
interface MergeStore {
    fun columns(table: String): Set<String>
    fun primaryKey(table: String): List<String>
    /** (column, parent table) for each foreign key, pointing at the parent's id. */
    fun foreignKeys(table: String): List<Pair<String, String>>
    /** The first row whose columns equal [where], or null. */
    fun find(table: String, where: Map<String, String>): Map<String, String?>?
    /**
     * Inserts [values]. With [keepId] false the id is left out and the
     * database picks one. Returns the row's id, or null if it could not go in.
     */
    fun insert(table: String, values: Map<String, String?>, keepId: Boolean): String?
    fun update(table: String, id: String, values: Map<String, String?>)
}

/**
 * "Add to what is here": a backup from another device, merged into this one
 * without losing anything either side added.
 *
 * An item is recognized by its creation time (`created_at`, or `started_at`
 * for a session), which both devices copy verbatim — not by its id, because
 * each device numbers its own new rows from the same place. So:
 *
 *  - the same item, found here by its creation time (whatever its id here),
 *    takes the backup's version if the backup's `updated_at` is newer;
 *  - an item this device doesn't have goes in with its own id if that id is
 *    free, or with a new one if this device already used it for something
 *    else — the song added on the phone and the song added on the tablet
 *    both end up on both;
 *  - every row pointing at a re-numbered item is pointed at its new number,
 *    so a session's items follow their session;
 *  - a row with no creation time (gear, tunings, settings) is added if its
 *    key is free and otherwise left as it is here — unless it belongs to an
 *    item that is new here, in which case it is new too.
 *
 * Merging the same backup twice changes nothing the second time.
 */
class BackupMerge(private val store: MergeStore) {

    data class Outcome(
        val added: Map<String, Int>,
        /** Rows that took the backup's newer version. */
        val updated: Int,
        /** Rows added under a new id because this device had used theirs. */
        val renumbered: Int,
        /** Rows that could not go in at all (a constraint refused them). */
        val failed: Int,
    )

    fun merge(tables: List<Pair<String, List<Map<String, String?>>>>): Outcome {
        // Backup id -> id here, per table, for every row placed or matched.
        val ids = mutableMapOf<String, MutableMap<String, String>>()
        // Ids here of rows this merge created, per table.
        val fresh = mutableMapOf<String, MutableSet<String>>()
        val added = mutableMapOf<String, Int>()
        var updated = 0
        var renumbered = 0
        var failed = 0

        for ((table, rows) in tables) {
            val columns = store.columns(table)
            val key = store.primaryKey(table)
            val singleId = key == listOf("id")
            val parents = store.foreignKeys(table)
            val identity = (IDENTITY_OVERRIDES[table]?.let(::listOf) ?: IDENTITY_COLUMNS).firstOrNull { it in columns }

            for (original in rows) {
                val row = original.filterKeys { it in columns }.toMutableMap()
                // Point at the parent's number here, not in the backup.
                var parentIsNew = false
                for ((column, parent) in parents) {
                    val v = row[column] ?: continue
                    val mapped = ids[parent]?.get(v)
                    if (mapped != null) row[column] = mapped
                    if (row[column] in fresh[parent].orEmpty()) parentIsNew = true
                }
                val backupId = if (singleId) row["id"] else null

                // The same item, wherever it is here.
                val same = identity?.let { col -> row[col]?.let { store.find(table, mapOf(col to it)) } }
                if (same != null) {
                    val hereId = same["id"]
                    if (singleId && backupId != null && hereId != null) ids.getOrPut(table) { mutableMapOf() }[backupId] = hereId
                    if (isNewer(same, row) && hereId != null) {
                        store.update(table, hereId, row - "id")
                        updated++
                    }
                    continue
                }

                // Not here yet (or not recognizable): is its key free?
                val taken = key.isNotEmpty() && key.all { row[it] != null } &&
                    store.find(table, key.associateWith { row[it]!! }) != null
                val isNew = identity != null || parentIsNew || !taken
                if (!isNew) continue // gear, tunings, settings already here: keep them

                val keepId = !taken
                val hereId = store.insert(table, row, keepId)
                if (hereId == null) { failed++; continue }
                if (!keepId) renumbered++
                added[table] = (added[table] ?: 0) + 1
                if (singleId && backupId != null) ids.getOrPut(table) { mutableMapOf() }[backupId] = hereId
                fresh.getOrPut(table) { mutableSetOf() } += hereId
            }
        }
        return Outcome(added, updated, renumbered, failed)
    }

    companion object {
        /** What makes a row the same item on two devices, in order of preference. */
        val IDENTITY_COLUMNS = listOf("created_at", "started_at")

        /**
         * Where something better than a creation time says two rows are one
         * thing: the same tab file imported on each device (its contents'
         * hash), and a tuning (its name, which the schema keeps unique).
         */
        val IDENTITY_OVERRIDES = mapOf("tab_files" to "sha256", "tunings" to "name")

        /** True when the backup's copy was edited after this device's. */
        fun isNewer(here: Map<String, String?>, backup: Map<String, String?>): Boolean {
            val h = here["updated_at"]?.toLongOrNull() ?: return false
            val b = backup["updated_at"]?.toLongOrNull() ?: return false
            return b > h
        }
    }
}
