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
    /**
     * The foreign key whose parent owns this row (ON DELETE CASCADE): a
     * routine's steps, a session's items. Null when nothing owns it.
     */
    fun owner(table: String): Pair<String, String>?
    /** The first row whose columns equal [where], or null. */
    fun find(table: String, where: Map<String, String>): Map<String, String?>?
    /** Every row of [table]; only used for the small table of deletions. */
    fun all(table: String): List<Map<String, String?>>
    /**
     * Inserts [values]. With [keepId] false the id is left out and the
     * database picks one. Returns the row's id, or null if it could not go in.
     */
    fun insert(table: String, values: Map<String, String?>, keepId: Boolean): String?
    fun update(table: String, id: String, values: Map<String, String?>)
    fun delete(table: String, where: Map<String, String>)
    /** Writes the note that an item was deleted, at exactly [deletedAt]. */
    fun noteDeletion(table: String, identity: String, deletedAt: Long)
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
 *  - a row with no creation time of its own that belongs to an item (a
 *    session's items, a routine's steps) goes where its item's version
 *    goes: in with a new item, swapped for the backup's set when the item
 *    took the backup's newer version, and otherwise left as it is here.
 *    Matching them one by one is impossible, and adding them would double
 *    them every time two devices merged back and forth;
 *  - any other row with no creation time (gear, tunings, settings) is added
 *    if its key is free and otherwise left as it is here;
 *  - deletions travel: an item deleted on either device is not added back,
 *    and is deleted here, unless one side edited it after the deletion, in
 *    which case the edit wins and the item stays.
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
        /** Items here deleted because they were deleted on the other device. */
        val removed: Int = 0,
        /** Items in the backup not added, because they were deleted here. */
        val leftOut: Int = 0,
    )

    fun merge(tables: List<Pair<String, List<Map<String, String?>>>>): Outcome {
        // Backup id -> id here, per table, for every row placed or matched.
        val ids = mutableMapOf<String, MutableMap<String, String>>()
        // Ids here of rows this merge created, per table.
        val fresh = mutableMapOf<String, MutableSet<String>>()
        // Ids here of rows that took the backup's version, per table.
        val replaced = mutableMapOf<String, MutableSet<String>>()
        // Owners whose rows here were cleared for the backup's set: "table:id".
        val cleared = mutableSetOf<String>()
        // Backup ids of rows left out because they stay deleted, per table.
        val gone = mutableMapOf<String, MutableSet<String>>()
        val added = mutableMapOf<String, Int>()
        var updated = 0
        var renumbered = 0
        var failed = 0
        var leftOut = 0

        // Every deletion either device knows of, the latest time for each item.
        val deletions = mutableMapOf<Pair<String, String>, Long>()
        fun note(row: Map<String, String?>) {
            val table = row["table_name"] ?: return
            val identity = row["identity"] ?: return
            val at = row["deleted_at"]?.toLongOrNull() ?: return
            val key = table to identity
            if (at > (deletions[key] ?: Long.MIN_VALUE)) deletions[key] = at
        }
        store.all(BackupFormat.DELETIONS).forEach(::note)
        tables.firstOrNull { it.first == BackupFormat.DELETIONS }?.second?.forEach(::note)

        for ((table, rows) in tables) {
            if (table == BackupFormat.DELETIONS) continue
            val columns = store.columns(table)
            val key = store.primaryKey(table)
            val singleId = key == listOf("id")
            val parents = store.foreignKeys(table)
            val identity = identityColumn(table, columns)
            val ownedBy = store.owner(table)
            val owner = if (identity == null) ownedBy else null

            for (original in rows) {
                val row = original.filterKeys { it in columns }.toMutableMap()
                // Belongs to an item that stays deleted: it would have gone with it.
                if (ownedBy != null && row[ownedBy.first] in gone[ownedBy.second].orEmpty()) continue
                // Point at the parent's number here, not in the backup.
                var parentIsNew = false
                for ((column, parent) in parents) {
                    val v = row[column] ?: continue
                    val mapped = ids[parent]?.get(v)
                    if (mapped != null) row[column] = mapped
                    // Its parent stays deleted, and the backup's number for it
                    // may be another item here: point at nothing instead.
                    else if (v in gone[parent].orEmpty()) row[column] = null
                    if (row[column] in fresh[parent].orEmpty()) parentIsNew = true
                }
                val backupId = if (singleId) row["id"] else null

                // The same item, wherever it is here.
                val same = identity?.let { col -> row[col]?.let { store.find(table, mapOf(col to it)) } }

                // Deleted on one device after its last edit on the other: stays
                // deleted. A copy here is dealt with with the other deletions.
                val deletedAt = identity?.let { col -> row[col]?.let { deletions[table to it] } }
                if (deletedAt != null && !editedAfter(row, deletedAt)) {
                    if (same == null) leftOut++
                    if (backupId != null) gone.getOrPut(table) { mutableSetOf() } += backupId
                    continue
                }

                if (same != null) {
                    val hereId = same["id"]
                    if (singleId && backupId != null && hereId != null) ids.getOrPut(table) { mutableMapOf() }[backupId] = hereId
                    if (isNewer(same, row) && hereId != null) {
                        store.update(table, hereId, row - "id")
                        replaced.getOrPut(table) { mutableSetOf() } += hereId
                        updated++
                    }
                    continue
                }

                if (owner != null) {
                    val (column, parent) = owner
                    val parentHere = row[column]
                    // Its item was not placed or matched: nothing here to belong to.
                    if (parentHere == null || ids[parent]?.values?.contains(parentHere) != true) continue
                    when (parentHere) {
                        in fresh[parent].orEmpty() -> Unit // a new item's rows go in with it
                        in replaced[parent].orEmpty() -> {
                            if (cleared.add("$parent:$parentHere")) store.delete(table, mapOf(column to parentHere))
                        }
                        else -> continue // the item kept its version here, so its rows stay as they are
                    }
                }

                // Not here yet (or not recognizable): is its key free?
                val taken = key.isNotEmpty() && key.all { row[it] != null } &&
                    store.find(table, key.associateWith { row[it]!! }) != null
                val isNew = identity != null || owner != null || parentIsNew || !taken
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

        // Deletions made elsewhere, applied here. Children first, so a count
        // of removed items is not inflated by what a parent takes with it.
        var removed = 0
        val order = tables.map { it.first }.filter { it != BackupFormat.DELETIONS }.reversed()
        val byTable = deletions.entries.groupBy { it.key.first }
        for (table in order) {
            val columns = store.columns(table)
            val identity = identityColumn(table, columns) ?: continue
            for ((key, at) in byTable[table].orEmpty()) {
                val here = store.find(table, mapOf(identity to key.second))
                if (here != null && !editedAfter(here, at)) {
                    store.delete(table, mapOf(identity to key.second))
                    removed++
                }
            }
        }
        // The deleting above noted each item at this moment; the true time
        // is the first deletion's, which is what an edit has to be newer than.
        deletions.forEach { (key, at) -> store.noteDeletion(key.first, key.second, at) }

        return Outcome(added, updated, renumbered, failed, removed, leftOut)
    }

    private fun identityColumn(table: String, columns: Set<String>): String? =
        (IDENTITY_OVERRIDES[table]?.let(::listOf) ?: IDENTITY_COLUMNS).firstOrNull { it in columns }

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

        /** True when a row was edited after it was deleted somewhere, which keeps it. */
        fun editedAfter(row: Map<String, String?>, deletedAt: Long): Boolean =
            (row["updated_at"]?.toLongOrNull() ?: return false) > deletedAt
    }
}
