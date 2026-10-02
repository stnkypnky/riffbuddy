package com.riffbuddy.desktop

import com.riffbuddy.data.Conflict
import com.riffbuddy.data.Db
import com.riffbuddy.data.Schema
import com.riffbuddy.data.SqlCursor
import com.riffbuddy.data.SqlDatabase
import com.riffbuddy.data.SqlValues
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.SQLException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The shared [Db] over SQLite through the sqlite-jdbc driver.
 *
 * It has to behave like Android's framework SQLite, because the repositories
 * were written against that: the same schema and migrations, the same return
 * values from insert, update and delete, the same nested transactions.
 *
 * **One connection, one lock.** Android gives each thread its own connection
 * and lets a transaction hold the database exclusively until it ends. Here a
 * single connection is guarded by a reentrant lock that a transaction keeps
 * from begin to end, so another thread's write waits rather than landing in
 * the middle of it. Repository calls are short and run on the IO dispatcher,
 * so the wait is never noticeable.
 *
 * **Rows are read up front.** A JDBC result set only moves forward, but the
 * repositories call `moveToFirst` after reading and ask for `count`, as an
 * Android cursor allows. Every query here returns a handful of rows, so they
 * are copied into memory and the cursor is a plain list.
 */
class JdbcDb(file: File) : Db {

    private val connection: Connection
    private val lock = ReentrantLock()
    private val database: JdbcSqlDatabase

    init {
        file.parentFile?.mkdirs()
        connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
        connection.createStatement().use { s ->
            s.execute("PRAGMA foreign_keys = ON")
            // Write-ahead logging, as Android uses: readers don't wait on a writer.
            s.execute("PRAGMA journal_mode = WAL")
        }
        database = JdbcSqlDatabase(connection, lock)
        migrate()
    }

    override val readableDatabase: SqlDatabase get() = database
    override val writableDatabase: SqlDatabase get() = database

    /** What Android's SQLiteOpenHelper does on open: create, or upgrade step by step. */
    private fun migrate() {
        val from = database.version
        if (from == Schema.VERSION) return
        check(from < Schema.VERSION) {
            "This database is from a newer version of Riff Buddy (schema $from, this app knows ${Schema.VERSION})."
        }
        database.beginTransaction()
        try {
            if (from == 0) {
                Schema.fresh().forEach(database::execSQL)
            } else {
                var v = from
                while (v < Schema.VERSION) {
                    Schema.MIGRATIONS[v]?.forEach(database::execSQL)
                    v++
                }
            }
            database.execSQL("PRAGMA user_version = ${Schema.VERSION}")
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun close() = lock.withLock { connection.close() }
}

private class JdbcSqlDatabase(private val c: Connection, private val lock: ReentrantLock) : SqlDatabase {

    /** Depth of nested transactions, and whether any level ended without success. */
    private var depth = 0
    private var failed = false
    private var markedAtDepth = BooleanArray(16)

    override val version: Int
        get() = rawQuery("PRAGMA user_version", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    override fun rawQuery(sql: String, args: Array<String>?): SqlCursor = locked {
        c.prepareStatement(sql).use { st ->
            args?.forEachIndexed { i, a -> st.setString(i + 1, a) }
            st.executeQuery().use { rs ->
                val meta = rs.metaData
                val names = Array(meta.columnCount) { meta.getColumnLabel(it + 1) }
                val rows = ArrayList<Array<Any?>>()
                while (rs.next()) rows += Array(names.size) { rs.getObject(it + 1) }
                ListCursor(names, rows)
            }
        }
    }

    override fun execSQL(sql: String) = locked {
        c.createStatement().use { it.execute(sql) }
        Unit
    }

    override fun execSQL(sql: String, args: Array<out Any?>) = locked {
        c.prepareStatement(sql).use { st -> bindAll(st, args.toList()); st.execute() }
        Unit
    }

    override fun executeUpdateDelete(sql: String, args: Array<String>): Int = locked {
        c.prepareStatement(sql).use { st -> args.forEachIndexed { i, a -> st.setString(i + 1, a) }; st.executeUpdate() }
    }

    override fun insert(table: String, nullColumnHack: String?, values: SqlValues): Long =
        try { insertOrThrow(table, nullColumnHack, values) } catch (_: SQLException) { -1L }

    override fun insertOrThrow(table: String, nullColumnHack: String?, values: SqlValues): Long =
        doInsert("INSERT", table, nullColumnHack, values)

    override fun insertWithOnConflict(table: String, nullColumnHack: String?, values: SqlValues, conflict: Conflict): Long =
        doInsert("INSERT OR $conflict", table, nullColumnHack, values)

    private fun doInsert(verb: String, table: String, nullColumnHack: String?, values: SqlValues): Long = locked {
        val entries = values.entries()
        val sql = if (entries.isEmpty()) {
            // SQLite can't insert a row with no columns; name one as NULL, as Android does.
            "$verb INTO $table (${nullColumnHack ?: error("an empty insert needs a nullColumnHack")}) VALUES (NULL)"
        } else {
            "$verb INTO $table (${entries.joinToString { it.first }}) VALUES (${entries.joinToString { "?" }})"
        }
        val changed = c.prepareStatement(sql).use { st -> bindAll(st, entries.map { it.second }); st.executeUpdate() }
        // An IGNORE that skipped the row changes nothing; Android reports that as -1.
        if (changed == 0) -1L
        else c.createStatement().use { s -> s.executeQuery("SELECT last_insert_rowid()").use { it.next(); it.getLong(1) } }
    }

    override fun update(table: String, values: SqlValues, whereClause: String?, whereArgs: Array<String>?): Int = locked {
        val entries = values.entries()
        val where = if (whereClause.isNullOrBlank()) "" else " WHERE $whereClause"
        val sql = "UPDATE $table SET ${entries.joinToString { "${it.first} = ?" }}$where"
        c.prepareStatement(sql).use { st ->
            bindAll(st, entries.map { it.second } + (whereArgs?.toList() ?: emptyList()))
            st.executeUpdate()
        }
    }

    override fun delete(table: String, whereClause: String?, whereArgs: Array<String>?): Int = locked {
        val where = if (whereClause.isNullOrBlank()) "" else " WHERE $whereClause"
        c.prepareStatement("DELETE FROM $table$where").use { st ->
            bindAll(st, whereArgs?.toList() ?: emptyList())
            st.executeUpdate()
        }
    }

    override fun beginTransaction() {
        lock.lock()
        if (depth == 0) {
            c.autoCommit = false
            failed = false
        }
        depth++
        if (depth > markedAtDepth.size) markedAtDepth = markedAtDepth.copyOf(depth * 2)
        markedAtDepth[depth - 1] = false
    }

    override fun setTransactionSuccessful() {
        check(lock.isHeldByCurrentThread && depth > 0) { "no transaction on this thread" }
        markedAtDepth[depth - 1] = true
    }

    override fun endTransaction() {
        check(lock.isHeldByCurrentThread && depth > 0) { "no transaction on this thread" }
        try {
            if (!markedAtDepth[depth - 1]) failed = true
            depth--
            if (depth == 0) {
                try {
                    if (failed) c.rollback() else c.commit()
                } finally {
                    c.autoCommit = true
                }
            }
        } finally {
            lock.unlock()
        }
    }

    private inline fun <T> locked(block: () -> T): T = lock.withLock(block)

    private fun bindAll(st: PreparedStatement, args: List<Any?>) {
        args.forEachIndexed { i, a ->
            when (a) {
                null -> st.setObject(i + 1, null)
                is String -> st.setString(i + 1, a)
                is Long -> st.setLong(i + 1, a)
                is Int -> st.setLong(i + 1, a.toLong())
                is Double -> st.setDouble(i + 1, a)
                is Float -> st.setDouble(i + 1, a.toDouble())
                is Boolean -> st.setLong(i + 1, if (a) 1 else 0)
                is ByteArray -> st.setBytes(i + 1, a)
                else -> st.setString(i + 1, a.toString())
            }
        }
    }
}

/** Rows already read; behaves like an Android cursor over them. */
private class ListCursor(override val columnNames: Array<String>, private val rows: List<Array<Any?>>) : SqlCursor {
    private var pos = -1

    override val count: Int get() = rows.size
    override fun moveToFirst(): Boolean { pos = 0; return rows.isNotEmpty() }
    override fun moveToNext(): Boolean { if (pos < rows.size) pos++; return pos < rows.size }
    private fun value(column: Int): Any? = rows[pos][column]
    override fun isNull(column: Int) = value(column) == null

    override fun getString(column: Int): String? = when (val v = value(column)) {
        null -> null
        is ByteArray -> String(v)
        // SQLite REALs print as Android prints them: 2.0, not 2.
        is Double, is Float -> v.toString()
        else -> v.toString()
    }

    override fun getLong(column: Int): Long = when (val v = value(column)) {
        null -> 0L
        is Number -> v.toLong()
        is String -> v.toLongOrNull() ?: v.toDoubleOrNull()?.toLong() ?: 0L
        else -> 0L
    }

    override fun getInt(column: Int): Int = getLong(column).toInt()

    override fun getDouble(column: Int): Double = when (val v = value(column)) {
        null -> 0.0
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull() ?: 0.0
        else -> 0.0
    }

    override fun getColumnIndexOrThrow(name: String): Int =
        columnNames.indexOf(name).takeIf { it >= 0 } ?: throw IllegalArgumentException("column '$name' does not exist")

    override fun close() {}
}
