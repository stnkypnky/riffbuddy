package com.riffbuddy.web

import com.riffbuddy.data.Conflict
import com.riffbuddy.data.Db
import com.riffbuddy.data.Schema
import com.riffbuddy.data.SqlCursor
import com.riffbuddy.data.SqlDatabase
import com.riffbuddy.data.SqlValues
import com.riffbuddy.json.JSONArray
import com.riffbuddy.json.JSONObject

/**
 * The database in a browser: SQLite's own WebAssembly build, in memory on the
 * page's thread, saved whole to the browser's storage shortly after every
 * change (see db-bridge.js). The schema and its migrations are the ones every
 * edition runs. It behaves as the desktop's JDBC database does: an insert
 * that fails returns -1, and transactions nest, with any inner one that wasn't
 * marked successful rolling back the whole.
 */
class WebDb : Db {
    private val database = WebSqlDatabase()

    init {
        migrate()
    }

    override val readableDatabase: SqlDatabase get() = database
    override val writableDatabase: SqlDatabase get() = database

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
}

private class WebSqlDatabase : SqlDatabase {
    private var depth = 0
    private var failed = false
    private val marked = ArrayList<Boolean>()

    override val version: Int
        get() = rawQuery("PRAGMA user_version", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    override fun rawQuery(sql: String, args: Array<String>?): SqlCursor {
        val result = JSONObject(jsQuery(sql, bindJson(args?.toList() ?: emptyList())))
        val cols = result.getJSONArray("cols").let { a -> Array(a.length()) { a.getString(it) } }
        val rows = result.getJSONArray("rows")
        return ListCursor(cols, List(rows.length()) { r ->
            val row = rows.getJSONArray(r)
            Array(cols.size) { c -> cellOf(row.opt(c)) }
        })
    }

    override fun execSQL(sql: String) {
        jsExec(sql)
        changed()
    }

    override fun execSQL(sql: String, args: Array<out Any?>) {
        run(sql, args.toList())
    }

    override fun executeUpdateDelete(sql: String, args: Array<String>): Int = run(sql, args.toList()).optInt("changes")

    override fun insert(table: String, nullColumnHack: String?, values: SqlValues): Long =
        try { insertOrThrow(table, nullColumnHack, values) } catch (_: Throwable) { -1L }

    override fun insertOrThrow(table: String, nullColumnHack: String?, values: SqlValues): Long =
        doInsert("INSERT", table, nullColumnHack, values)

    override fun insertWithOnConflict(table: String, nullColumnHack: String?, values: SqlValues, conflict: Conflict): Long =
        doInsert("INSERT OR $conflict", table, nullColumnHack, values)

    private fun doInsert(verb: String, table: String, nullColumnHack: String?, values: SqlValues): Long {
        val entries = values.entries()
        val sql = if (entries.isEmpty()) {
            // SQLite can't insert a row with no columns; name one as NULL, as Android does.
            "$verb INTO $table (${nullColumnHack ?: error("an empty insert needs a nullColumnHack")}) VALUES (NULL)"
        } else {
            "$verb INTO $table (${entries.joinToString { it.first }}) VALUES (${entries.joinToString { "?" }})"
        }
        val result = run(sql, entries.map { it.second })
        // An IGNORE that skipped the row changes nothing; Android reports that as -1.
        return if (result.optInt("changes") == 0) -1L else result.getString("id").toLong()
    }

    override fun update(table: String, values: SqlValues, whereClause: String?, whereArgs: Array<String>?): Int {
        val entries = values.entries()
        val where = if (whereClause.isNullOrBlank()) "" else " WHERE $whereClause"
        val sql = "UPDATE $table SET ${entries.joinToString { "${it.first} = ?" }}$where"
        return run(sql, entries.map { it.second } + (whereArgs?.toList() ?: emptyList())).optInt("changes")
    }

    override fun delete(table: String, whereClause: String?, whereArgs: Array<String>?): Int {
        val where = if (whereClause.isNullOrBlank()) "" else " WHERE $whereClause"
        return run("DELETE FROM $table$where", whereArgs?.toList() ?: emptyList()).optInt("changes")
    }

    override fun beginTransaction() {
        if (depth == 0) {
            jsExec("BEGIN")
            failed = false
        }
        depth++
        marked += false
    }

    override fun setTransactionSuccessful() {
        check(depth > 0) { "no transaction open" }
        marked[depth - 1] = true
    }

    override fun endTransaction() {
        check(depth > 0) { "no transaction open" }
        if (!marked.removeAt(depth - 1)) failed = true
        depth--
        if (depth == 0) {
            if (failed) jsExec("ROLLBACK") else { jsExec("COMMIT"); changed() }
        }
    }

    private fun run(sql: String, args: List<Any?>): JSONObject {
        val result = JSONObject(jsRun(sql, bindJson(args)))
        changed()
        return result
    }

    /** A save is asked for only once nothing is left uncommitted. */
    private fun changed() {
        if (depth == 0) jsChanged()
    }
}

/** Arguments as the bridge reads them: whole numbers as {i}, so 64 bits survive; reals as {r}. */
private fun bindJson(args: List<Any?>): String = JSONArray().also { a ->
    args.forEach { v ->
        when (v) {
            null -> a.put(JSONObject.NULL)
            is String -> a.put(v)
            is Long, is Int, is Short, is Byte -> a.put(JSONObject().put("i", v.toString()))
            is Boolean -> a.put(JSONObject().put("i", if (v) "1" else "0"))
            is Double, is Float -> a.put(JSONObject().put("r", (v as Number).toDouble()))
            else -> a.put(v.toString())
        }
    }
}.toString()

private fun cellOf(v: Any?): Any? = when (v) {
    null, JSONObject.NULL -> null
    is JSONObject -> if (v.has("i")) v.getString("i").toLong() else v.getDouble("r")
    else -> v.toString()
}

/** Rows read whole, as the JDBC edition reads them. */
private class ListCursor(override val columnNames: Array<String>, private val rows: List<Array<Any?>>) : SqlCursor {
    private var pos = -1
    override val count: Int get() = rows.size
    override fun moveToFirst(): Boolean { pos = 0; return rows.isNotEmpty() }
    override fun moveToNext(): Boolean { if (pos < rows.size) pos++; return pos < rows.size }
    private fun value(column: Int): Any? = rows[pos][column]
    override fun isNull(column: Int) = value(column) == null
    override fun getString(column: Int): String? = value(column)?.toString()
    override fun getLong(column: Int): Long = when (val v = value(column)) {
        is Long -> v
        is Double -> v.toLong()
        is String -> v.toLongOrNull() ?: v.toDoubleOrNull()?.toLong() ?: 0L
        else -> 0L
    }
    override fun getInt(column: Int): Int = getLong(column).toInt()
    override fun getDouble(column: Int): Double = when (val v = value(column)) {
        is Long -> v.toDouble()
        is Double -> v
        is String -> v.toDoubleOrNull() ?: 0.0
        else -> 0.0
    }
    override fun getColumnIndexOrThrow(name: String): Int =
        columnNames.indexOf(name).takeIf { it >= 0 } ?: throw IllegalArgumentException("column '$name' does not exist")
    override fun close() {}
}

private fun jsQuery(sql: String, bind: String): String = js("rbDb.query(sql, bind)")
private fun jsRun(sql: String, bind: String): String = js("rbDb.run(sql, bind)")
private fun jsExec(sql: String): Unit = js("rbDb.exec(sql)")
private fun jsChanged(): Unit = js("rbDb.changed()")
