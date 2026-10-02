package com.riffbuddy.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

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
    private val fileDirs: Map<String, File>,
    private val cacheDir: File,
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
    )

    /** A backup read far enough to summarize, and not yet applied. */
    class Contents(val root: JSONObject, internal val zip: File)

    suspend fun exportTo(output: OutputStream): Int = withContext(Dispatchers.IO) {
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

        ZipOutputStream(output.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(BackupFormat.ENTRY_NAME))
            zip.write(root.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            // The bytes behind every row that names a file. Read from the rows,
            // not by listing folders, so a stray file never ends up in a backup
            // and a missing one is simply absent rather than an error.
            BackupFormat.FILE_TABLES.forEach { (table, folder) ->
                val dir = fileDirs.getValue(folder)
                storedNames(database, table).forEach { name ->
                    val file = File(dir, name)
                    if (BackupFormat.isSafeStoredName(name) && file.isFile) {
                        zip.putNextEntry(ZipEntry(folder + name))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
        }
        rowCount
    }

    private fun storedNames(database: SqlDatabase, table: String): List<String> =
        database.rawQuery("SELECT stored_name FROM $table", null)
            .use { c -> buildList { while (c.moveToNext()) add(c.requireString(0)) } }

    /** Copies the backup to the cache and reads its manifest, applying nothing. */
    suspend fun inspect(input: InputStream): Result<Contents> = withContext(Dispatchers.IO) {
        val copy = File(cacheDir, "restore-${System.currentTimeMillis()}.zip")
        runCatching {
            copy.outputStream().use { input.copyTo(it) }
            val text = ZipFile(copy).use { zip ->
                val entry = zip.getEntry(BackupFormat.ENTRY_NAME) ?: error("No Riff Buddy data inside that file.")
                zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
            }
            Contents(JSONObject(text), copy)
        }.onFailure { copy.delete() }
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
    suspend fun restore(contents: Contents, mode: BackupFormat.Mode): Result<Restored> =
        withContext(Dispatchers.IO) {
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
                                renumbered = outcome.renumbered, skipped = outcome.failed)
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
                    // Only once the rows are committed: files no row points at
                    // are gone — after a replace, the old library's; after a
                    // merge, any backup file whose row lost to a local one.
                    sweepUnreferenced(database)
                }.onFailure {
                    written.forEach(File::delete)
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
    private fun writeFiles(zipFile: File): List<File> {
        val written = mutableListOf<File>()
        try {
            ZipFile(zipFile).use { zip ->
                for (entry in zip.entries()) {
                    val folder = BackupFormat.FILE_TABLES.map { it.second }.firstOrNull { entry.name.startsWith(it) } ?: continue
                    val name = entry.name.removePrefix(folder)
                    if (!BackupFormat.isSafeStoredName(name)) continue
                    val dir = fileDirs.getValue(folder).apply { mkdirs() }
                    val target = File(dir, name)
                    if (target.exists()) continue
                    val partial = File(dir, "$name.part")
                    zip.getInputStream(entry).use { input -> partial.outputStream().use { input.copyTo(it) } }
                    if (!partial.renameTo(target)) {
                        partial.delete()
                        throw java.io.IOException("Could not restore a file")
                    }
                    written += target
                }
            }
        } catch (e: Exception) {
            written.forEach(File::delete)
            throw e
        }
        return written
    }

    private fun sweepUnreferenced(database: SqlDatabase) {
        BackupFormat.FILE_TABLES.forEach { (table, folder) ->
            val keep = storedNames(database, table).toSet()
            fileDirs.getValue(folder).listFiles()?.filter { it.isFile && it.name !in keep }?.forEach(File::delete)
        }
    }

    /** "Clear all data". Keys are removed separately; they are not in here. */
    suspend fun clearAll(): Unit = withContext(Dispatchers.IO) {
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
        fileDirs.values.forEach { dir -> dir.listFiles()?.forEach(File::delete) }
    }

    suspend fun rowCounts(): Map<String, Int> = withContext(Dispatchers.IO) {
        BackupFormat.TABLES.associateWith { table ->
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
