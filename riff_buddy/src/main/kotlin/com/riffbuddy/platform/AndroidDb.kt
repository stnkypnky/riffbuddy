package com.riffbuddy.platform

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.riffbuddy.data.Conflict
import com.riffbuddy.data.Db
import com.riffbuddy.data.Schema
import com.riffbuddy.data.SqlCursor
import com.riffbuddy.data.SqlDatabase
import com.riffbuddy.data.SqlValues

/**
 * The shared [Db] over Android's framework SQLite (not Room). A thin
 * wrapper — every call goes straight to the framework method of the same
 * name — so the repositories behave exactly as they did before the desktop
 * edition gave them a second platform.
 */
class AndroidDb(context: Context) : Db {

    private val helper = object : SQLiteOpenHelper(context, NAME, null, Schema.VERSION) {

        override fun onConfigure(db: SQLiteDatabase) {
            db.setForeignKeyConstraintsEnabled(true)
        }

        override fun onCreate(db: SQLiteDatabase) {
            Schema.fresh().forEach(db::execSQL)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            var v = oldVersion
            while (v < newVersion) {
                Schema.MIGRATIONS[v]?.forEach(db::execSQL)
                v++
            }
        }
    }

    override val readableDatabase: SqlDatabase get() = AndroidSqlDatabase(helper.readableDatabase)
    override val writableDatabase: SqlDatabase get() = AndroidSqlDatabase(helper.writableDatabase)

    companion object {
        const val NAME = "riffbuddy.db"
    }
}

private class AndroidSqlDatabase(private val db: SQLiteDatabase) : SqlDatabase {
    override val version: Int get() = db.version
    override fun rawQuery(sql: String, args: Array<String>?): SqlCursor = AndroidSqlCursor(db.rawQuery(sql, args))
    override fun execSQL(sql: String) = db.execSQL(sql)
    override fun execSQL(sql: String, args: Array<out Any?>) = db.execSQL(sql, args)
    override fun executeUpdateDelete(sql: String, args: Array<String>): Int =
        db.compileStatement(sql).use { it.bindAllArgsAsStrings(args); it.executeUpdateDelete() }
    override fun insert(table: String, nullColumnHack: String?, values: SqlValues): Long =
        db.insert(table, nullColumnHack, values.toContentValues())
    override fun insertOrThrow(table: String, nullColumnHack: String?, values: SqlValues): Long =
        db.insertOrThrow(table, nullColumnHack, values.toContentValues())
    override fun insertWithOnConflict(table: String, nullColumnHack: String?, values: SqlValues, conflict: Conflict): Long =
        db.insertWithOnConflict(table, nullColumnHack, values.toContentValues(), when (conflict) {
            Conflict.ABORT -> SQLiteDatabase.CONFLICT_ABORT
            Conflict.IGNORE -> SQLiteDatabase.CONFLICT_IGNORE
            Conflict.REPLACE -> SQLiteDatabase.CONFLICT_REPLACE
        })
    override fun update(table: String, values: SqlValues, whereClause: String?, whereArgs: Array<String>?): Int =
        db.update(table, values.toContentValues(), whereClause, whereArgs)
    override fun delete(table: String, whereClause: String?, whereArgs: Array<String>?): Int =
        db.delete(table, whereClause, whereArgs)
    override fun beginTransaction() = db.beginTransaction()
    override fun setTransactionSuccessful() = db.setTransactionSuccessful()
    override fun endTransaction() = db.endTransaction()
}

private fun SqlValues.toContentValues() = ContentValues().also { cv ->
    for ((column, value) in entries()) when (value) {
        null -> cv.putNull(column)
        is String -> cv.put(column, value)
        is Long -> cv.put(column, value)
        is Double -> cv.put(column, value)
        else -> error("unexpected ${value::class.simpleName} for $column")
    }
}

private class AndroidSqlCursor(private val c: Cursor) : SqlCursor {
    override val count: Int get() = c.count
    override val columnNames: Array<String> get() = c.columnNames
    override fun moveToFirst() = c.moveToFirst()
    override fun moveToNext() = c.moveToNext()
    override fun isNull(column: Int) = c.isNull(column)
    override fun getString(column: Int): String? = c.getString(column)
    override fun getLong(column: Int) = c.getLong(column)
    override fun getInt(column: Int) = c.getInt(column)
    override fun getDouble(column: Int) = c.getDouble(column)
    override fun getColumnIndexOrThrow(name: String) = c.getColumnIndexOrThrow(name)
    override fun close() = c.close()
}
