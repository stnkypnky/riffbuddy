package com.riffbuddy.data

import com.riffbuddy.domain.nowMillis
import com.riffbuddy.json.JSONArray
import com.riffbuddy.json.JSONObject
import com.riffbuddy.platform.Blocking
import com.riffbuddy.platform.ZipReader
import com.riffbuddy.platform.ZipWriter
import com.riffbuddy.platform.appFileSystem
import com.riffbuddy.platform.delete
import com.riffbuddy.platform.exists
import com.riffbuddy.platform.isFile
import com.riffbuddy.platform.list
import com.riffbuddy.platform.mkdirs
import com.riffbuddy.platform.renameTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.BufferedSink
import okio.IOException
import okio.Path
import okio.Source
import okio.buffer
import okio.use

/**
 * Reading and writing the whole database as one file.
 *
 * Tables are dumped generically from their column names rather than by
 * hand-written per-table code. A hand-written exporter is a second place to
 * remember a new column, and the one that gets forgotten — and a backup missing
 * a column is not discovered until someone restores it.
 *
 * Files travel inside the zip beside the data: imported tab files under `tabs/`
 * and takes under `recordings/`. A backup that restored the
 * rows without the files would restore a list of things that no longer open.
 *
 * **Nothing is held in memory but the JSON.** Takes are 5.8 MB a minute, so a
 * backup can run to hundreds of MB — far past what a 2 GB tablet can hold at
 * once. Export streams each file into the zip; import first copies the picked
 * file to the cache (the picker's stream can only be read once, and the
 * summary is shown before anything is applied), then streams entries from it.
 */
class BackupRepo(
    private val db: Db,
    private val appVersion: String,
    /** Folder in the zip → folder on disk, one per [BackupFormat.FILE_TABLES] entry. */
    private val fileDirs: Map<String, Path>,
    private val cacheDir: Path,
) {

    /**
     * What a restore did. [updated] rows took the backup's newer version;
     * [skipped] rows could not go in at all (a constraint refused them).
     */
    data class Restored(
        val counts: Map<String, Int>,
        val mode: BackupFormat.Mode,
        val files: Int = 0,
        val updated: Int = 0,
        /** Rows added under a new id: this device had used theirs for something else. */
        val renumbered: Int = 0,
        val skipped: Int = 0,
        /** Items deleted here because they were deleted on the other device. */
        val removed: Int = 0,
        /** Items in the backup not added because they were deleted here. */
        val leftOut: Int = 0,
    )

    /** A backup read far enough to summarize, and not yet applied. */
    class Contents(val root: JSONObject, internal val zip: Path)

    /**
     * Writes the backup. [includeFile] picks which files go in, by their name
     * in the zip (`tabs/…`, `recordings/…`): a sync leaves out the ones the
     * other device already has, and the copy kept for undoing one leaves out
     * all of them, since the files are still on disk.
     */
    suspend fun exportTo(output: BufferedSink, includeFile: (String) -> Boolean = { true }): Int = withContext(Dispatchers.Blocking) {
        val database = db.readableDatabase
        val tables = JSONObject()
        var rowCount = 0

        BackupFormat.TABLES.forEach { table ->
            val rows = JSONArray()
            database.rawQuery("SELECT * FROM $table", null).use { cursor ->
                val columns = cursor.columnNames
                while (cursor.moveToNext()) {
                    val row = JSONObject()
                    columns.forEachIndexed { index, column ->
                        if (cursor.isNull(index)) row.put(column, JSONObject.NULL)
                        else row.put(column, cursor.getString(index))
                    }
                    rows.put(row)
                    rowCount++
                }
            }
            tables.put(table, rows)
        }

        val root = BackupFormat.manifest(appVersion).put("tables", tables)

        ZipWriter(output).use { zip ->
            zip.entry(BackupFormat.ENTRY_NAME) { it.writeUtf8(root.toString()) }

            // The bytes behind every row that names a file. Read from the rows,
            // not by listing folders, so a stray file never ends up in a backup
            // and a missing one is simply absent rather than an error.
            BackupFormat.FILE_TABLES.forEach { (table, folder) ->
                val dir = fileDirs.getValue(folder)
                storedNames(database, table).forEach { name ->
                    val file = dir / name
                    if (BackupFormat.isSafeStoredName(name) && file.isFile && includeFile(folder + name)) {
                        zip.entry(folder + name) { out -> appFileSystem.source(file).use { out.writeAll(it) } }
                    }
                }
            }
        }
        rowCount
    }

    private fun storedNames(database: SqlDatabase, table: String): List<String> =
        database.rawQuery("SELECT stored_name FROM $table", null)
            .use { c -> buildList { while (c.moveToNext()) add(c.requireString(0)) } }

    /** The files this device holds that a backup would carry, by their name in the zip. */
    suspend fun storedFiles(): Set<String> = withContext(Dispatchers.Blocking) {
        val database = db.readableDatabase
        BackupFormat.FILE_TABLES.flatMap { (table, folder) ->
            val dir = fileDirs.getValue(folder)
            storedNames(database, table).filter { (dir / it).isFile }.map { folder + it }
        }.toSet()
    }

    /** Copies the backup to the cache and reads its manifest, applying nothing. */
    suspend fun inspect(input: Source): Result<Contents> = withContext(Dispatchers.Blocking) {
        val copy = cacheDir.mkdirs() / "restore-${nowMillis()}.zip"
        runCatching {
            appFileSystem.sink(copy).buffer().use { it.writeAll(input) }
            contentsOf(copy)
        }.onFailure { copy.delete() }
    }

    /**
     * As [inspect], for a backup this app already wrote to its own storage
     * (one received by a sync): read where it is rather than copied, and
     * deleted once applied or discarded, like a copy.
     */
    suspend fun inspectFile(file: Path): Result<Contents> = withContext(Dispatchers.Blocking) {
        runCatching { contentsOf(file) }.onFailure { file.delete() }
    }

    private fun contentsOf(zipFile: Path): Contents {
        val text = ZipReader(zipFile).use { zip ->
            val entry = zip.open(BackupFormat.ENTRY_NAME) ?: error("No Riff Buddy data inside that file.")
            entry.buffer().use { it.readUtf8() }
        }
        return Contents(JSONObject(text), zipFile)
    }

    /** Throws away an inspected backup that was not applied. */
    fun discard(contents: Contents) {
        contents.zip.delete()
    }

    /**
     * Applies a backup, all or nothing.
     *
     * Files first, rows second, so no row is ever committed without its file.
     * The rows go in one transaction: a restore that failed halfway would leave
     * a database that is neither the old one nor the new one. If the rows fail,
     * the files written here are taken back out.
     */
    /**
     * [sweep] false keeps files no row points at any more, for a merge that
     * may be undone: the rows can be put back, and their files must still
     * be there. They go at the next sweep.
     */
    suspend fun restore(contents: Contents, mode: BackupFormat.Mode, sweep: Boolean = true): Result<Restored> =
        withContext(Dispatchers.Blocking) {
            try {
                val root = contents.root
                BackupFormat.validate(root)?.let {
                    return@withContext Result.failure(IllegalArgumentException(it.message))
                }
                val tables = root.optJSONObject("tables")
                    ?: return@withContext Result.failure(IllegalArgumentException("No data in that backup."))

                val written = runCatching { writeFiles(contents.zip) }
                    .getOrElse { return@withContext Result.failure(it) }

                val database = db.writableDatabase
                runCatching {
                    database.beginTransaction()
                    try {
                        if (mode == BackupFormat.Mode.REPLACE) {
                            // Reverse order, so children go before their parents.
                            BackupFormat.TABLES.reversed().forEach { database.delete(it, null, null) }
                        }
                        val restored = if (mode == BackupFormat.Mode.MERGE) {
                            val rows = BackupFormat.TABLES.map { table ->
                                val array = tables.optJSONArray(table)
                                table to (0 until (array?.length() ?: 0)).mapNotNull { i ->
                                    array!!.optJSONObject(i)?.let { o ->
                                        o.keys().asSequence().associateWith { if (o.isNull(it)) null else o.getString(it) }
                                    }
                                }
                            }
                            val outcome = BackupMerge(SqliteMergeStore(database)).merge(rows)
                            Restored(outcome.added, mode, files = written.size, updated = outcome.updated,
                                renumbered = outcome.renumbered, skipped = outcome.failed,
                                removed = outcome.removed, leftOut = outcome.leftOut)
                        } else {
                            val counts = mutableMapOf<String, Int>()
                            var failed = 0
                            BackupFormat.TABLES.forEach { table ->
                                val rows = tables.optJSONArray(table) ?: return@forEach
                                var inserted = 0
                                for (i in 0 until rows.length()) {
                                    val row = rows.optJSONObject(i) ?: continue
                                    val values = SqlValues()
                                    row.keys().forEach { column ->
                                        if (row.isNull(column)) values.putNull(column) else values.put(column, row.getString(column))
                                    }
                                    if (database.insertWithOnConflict(table, null, values, Conflict.REPLACE) >= 0) inserted++
                                    else failed++
                                }
                                if (inserted > 0) counts[table] = inserted
                            }
                            Restored(counts, mode, files = written.size, skipped = failed)
                        }
                        database.setTransactionSuccessful()
                        restored
                    } finally {
                        database.endTransaction()
                    }
                }.onSuccess {
                    if (!sweep) return@onSuccess
                    // Only once the rows are committed: files no row points at
                    // are gone — after a replace, the old library's; after a
                    // merge, any backup file whose row lost to a local one.
                    sweepUnreferenced(database)
                }.onFailure {
                    written.forEach { it.delete() }
                }
            } finally {
                contents.zip.delete()
            }
        }

    /**
     * Streams each file entry to its folder, atomically, skipping any already
     * present. Names that are not ones this app creates are skipped — a zip
     * entry naming `tabs/../databases/…` must not write anywhere (zip slip).
     */
    private fun writeFiles(zipFile: Path): List<Path> {
        val written = mutableListOf<Path>()
        try {
            ZipReader(zipFile).use { zip ->
                for (entryName in zip.names) {
                    val folder = BackupFormat.FILE_TABLES.map { it.second }.firstOrNull { entryName.startsWith(it) } ?: continue
                    val name = entryName.removePrefix(folder)
                    if (!BackupFormat.isSafeStoredName(name)) continue
                    val dir = fileDirs.getValue(folder).mkdirs()
                    val target = dir / name
                    if (target.exists()) continue
                    val partial = dir / "$name.part"
                    zip.open(entryName)!!.use { input -> appFileSystem.sink(partial).buffer().use { it.writeAll(input) } }
                    if (!partial.renameTo(target)) {
                        partial.delete()
                        throw IOException("Could not restore a file")
                    }
                    written += target
                }
            }
        } catch (e: Exception) {
            written.forEach { it.delete() }
            throw e
        }
        return written
    }

    /** Deletes files no row points at: what a merge with [restore]'s sweep off left behind. */
    suspend fun sweep(): Unit = withContext(Dispatchers.Blocking) { sweepUnreferenced(db.writableDatabase) }

    private fun sweepUnreferenced(database: SqlDatabase) {
        BackupFormat.FILE_TABLES.forEach { (table, folder) ->
            val keep = storedNames(database, table).toSet()
            fileDirs.getValue(folder).list().filter { it.isFile && it.name !in keep }.forEach { it.delete() }
        }
    }

    /** "Clear all data". Keys are removed separately; they are not in here. */
    suspend fun clearAll(): Unit = withContext(Dispatchers.Blocking) {
        val database = db.writableDatabase
        database.beginTransaction()
        try {
            BackupFormat.TABLES.reversed().forEach { database.delete(it, null, null) }
            database.delete("ai_cache", null, null)
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        // The rows are gone, so the files are unreachable — and "clear all
        // data" that left files on disk would not be telling the truth.
        fileDirs.values.forEach { dir -> dir.list().forEach { it.delete() } }
    }

    suspend fun rowCounts(): Map<String, Int> = withContext(Dispatchers.Blocking) {
        (BackupFormat.TABLES - BackupFormat.DELETIONS).associateWith { table ->
            db.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null)
                .use { if (it.moveToFirst()) it.getInt(0) else 0 }
        }.filterValues { it > 0 }
    }
}

/** [MergeStore] over this app's SQLite database. */
private class SqliteMergeStore(private val db: SqlDatabase) : MergeStore {

    private fun <T> pragma(sql: String, read: (SqlCursor) -> T): List<T> =
        db.rawQuery(sql, null).use { c -> buildList { while (c.moveToNext()) add(read(c)) } }

    override fun columns(table: String): Set<String> =
        pragma("PRAGMA table_info($table)") { it.requireString(it.getColumnIndexOrThrow("name")) }.toSet()

    override fun primaryKey(table: String): List<String> =
        pragma("PRAGMA table_info($table)") { c ->
            c.requireString(c.getColumnIndexOrThrow("name")) to c.getInt(c.getColumnIndexOrThrow("pk"))
        }.filter { it.second > 0 }.sortedBy { it.second }.map { it.first }

    override fun foreignKeys(table: String): List<Pair<String, String>> =
        pragma("PRAGMA foreign_key_list($table)") { c ->
            c.requireString(c.getColumnIndexOrThrow("from")) to c.requireString(c.getColumnIndexOrThrow("table"))
        }

    override fun owner(table: String): Pair<String, String>? =
        pragma("PRAGMA foreign_key_list($table)") { c ->
            Triple(c.requireString(c.getColumnIndexOrThrow("from")), c.requireString(c.getColumnIndexOrThrow("table")),
                c.requireString(c.getColumnIndexOrThrow("on_delete")))
        }.firstOrNull { it.third.equals("CASCADE", ignoreCase = true) }?.let { it.first to it.second }

    override fun all(table: String): List<Map<String, String?>> =
        db.rawQuery("SELECT * FROM $table", null).use { c ->
            buildList {
                while (c.moveToNext()) add(c.columnNames.indices.associate { c.columnNames[it] to (if (c.isNull(it)) null else c.getString(it)) })
            }
        }

    override fun delete(table: String, where: Map<String, String>) {
        db.delete(table, where.keys.joinToString(" AND ") { "$it = ?" }, where.values.toTypedArray())
    }

    override fun noteDeletion(table: String, identity: String, deletedAt: Long) {
        db.insertWithOnConflict(BackupFormat.DELETIONS, null, SqlValues().apply {
            put("table_name", table); put("identity", identity); put("deleted_at", deletedAt)
        }, Conflict.REPLACE)
    }

    override fun find(table: String, where: Map<String, String>): Map<String, String?>? {
        val clause = where.keys.joinToString(" AND ") { "$it = ?" }
        return db.rawQuery("SELECT * FROM $table WHERE $clause LIMIT 1", where.values.toTypedArray()).use { c ->
            if (!c.moveToFirst()) null
            else c.columnNames.indices.associate { c.columnNames[it] to (if (c.isNull(it)) null else c.getString(it)) }
        }
    }

    override fun insert(table: String, values: Map<String, String?>, keepId: Boolean): String? {
        val cv = SqlValues()
        values.forEach { (k, v) -> if (!keepId && k == "id") return@forEach; if (v == null) cv.putNull(k) else cv.put(k, v) }
        val rowId = db.insertWithOnConflict(table, null, cv, Conflict.ABORT)
        if (rowId < 0) return null
        // The id a child should point at: the row's own id where it has one.
        return values["id"]?.takeIf { keepId } ?: rowId.toString()
    }

    override fun update(table: String, id: String, values: Map<String, String?>) {
        val cv = SqlValues()
        values.forEach { (k, v) -> if (v == null) cv.putNull(k) else cv.put(k, v) }
        db.update(table, cv, "id = ?", arrayOf(id))
    }
}
