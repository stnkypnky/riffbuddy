package com.riffbuddy.data

/**
 * The database as the repositories see it, on any platform.
 *
 * Both platforms run real SQLite — Android its own framework copy, the desktop
 * the sqlite-jdbc driver — so the SQL in the repositories is the same text on
 * both, and the schema and migrations in [Schema] are shared exactly. What
 * differs is only the calling convention, and this file is the small part of
 * it the repositories actually use: run a query and read rows, insert, update
 * and delete with a set of column values, and group writes in a transaction.
 *
 * The names follow Android's own (`rawQuery`, `insertWithOnConflict`,
 * `moveToNext`), so a repository reads the same as it did before there was a
 * second platform, and each implementation has a clear reference for what
 * every call must do.
 */
interface Db {
    val readableDatabase: SqlDatabase
    val writableDatabase: SqlDatabase
}

/** What to do when an insert collides with an existing row's key. */
enum class Conflict { ABORT, IGNORE, REPLACE }

interface SqlDatabase {
    /** The schema version the file is at (SQLite's `user_version`). */
    val version: Int

    /** Arguments are bound as text, as on Android; SQLite converts by column affinity. */
    fun rawQuery(sql: String, args: Array<String>?): SqlCursor

    fun execSQL(sql: String)
    fun execSQL(sql: String, args: Array<out Any?>)

    /** Runs an UPDATE or DELETE written out in SQL; returns the rows it changed. */
    fun executeUpdateDelete(sql: String, args: Array<String>): Int

    /** The new row's id, or -1 if the insert failed. Never throws for a constraint. */
    fun insert(table: String, nullColumnHack: String?, values: SqlValues): Long

    /** The new row's id; throws if the insert fails. */
    fun insertOrThrow(table: String, nullColumnHack: String?, values: SqlValues): Long

    /** The new row's id, or -1 when [Conflict.IGNORE] skipped it. */
    fun insertWithOnConflict(table: String, nullColumnHack: String?, values: SqlValues, conflict: Conflict): Long

    /** Rows changed. */
    fun update(table: String, values: SqlValues, whereClause: String?, whereArgs: Array<String>?): Int

    /** Rows deleted. */
    fun delete(table: String, whereClause: String?, whereArgs: Array<String>?): Int

    /**
     * A transaction, Android-style: begin, mark successful, end. Ending without
     * marking rolls back. Transactions nest; only the outermost one commits.
     */
    fun beginTransaction()
    fun setTransactionSuccessful()
    fun endTransaction()
}

/** Rows from a query. Starts before the first row; close it (or `use` it) when done. */
interface SqlCursor : AutoCloseable {
    val count: Int
    val columnNames: Array<String>
    fun moveToFirst(): Boolean
    fun moveToNext(): Boolean
    fun isNull(column: Int): Boolean
    /** Null for SQL NULL. */
    fun getString(column: Int): String?

    /**
     * For a NOT NULL column: its text, or an exception if the row breaks that
     * rule — the same failure Android's own cursor gave Kotlin code that
     * assumed a value.
     */
    fun requireString(column: Int): String =
        getString(column) ?: throw NullPointerException("column ${columnNames[column]} is NULL")

    /** 0 for SQL NULL, as on Android. */
    fun getLong(column: Int): Long
    fun getInt(column: Int): Int
    fun getDouble(column: Int): Double
    fun getColumnIndexOrThrow(name: String): Int
}

/** Column values for an insert or update, in the order they were put. */
class SqlValues {
    private val map = LinkedHashMap<String, Any?>()

    fun put(column: String, value: String?) { map[column] = value }
    fun put(column: String, value: Long?) { map[column] = value }
    fun put(column: String, value: Int?) { map[column] = value?.toLong() }
    fun put(column: String, value: Double?) { map[column] = value }
    fun put(column: String, value: Float?) { map[column] = value?.toDouble() }
    /** Stored as 1 or 0: SQLite has no boolean type. */
    fun put(column: String, value: Boolean?) { map[column] = value?.let { if (it) 1L else 0L } }
    fun putNull(column: String) { map[column] = null }

    val columns: Set<String> get() = map.keys
    fun entries(): List<Pair<String, Any?>> = map.entries.map { it.key to it.value }
    fun isEmpty(): Boolean = map.isEmpty()
}
