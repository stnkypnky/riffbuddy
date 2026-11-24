package com.riffbuddy.data

import com.riffbuddy.domain.Homework
import com.riffbuddy.domain.Lesson
import com.riffbuddy.domain.Routine
import com.riffbuddy.domain.RoutineStep
import com.riffbuddy.domain.StepKind
import com.riffbuddy.platform.Blocking
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Lessons and homework, and routines. */
class PlanRepo(private val db: Db) {

    // ---- lessons -----------------------------------------------------------

    suspend fun lessons(): List<Lesson> = withContext(Dispatchers.Blocking) {
        db.readableDatabase.rawQuery("SELECT id, lesson_date, teacher, notes FROM lessons ORDER BY lesson_date DESC", null)
            .use { c -> buildList { while (c.moveToNext()) add(Lesson(c.getLong(0), Instant.fromEpochMilliseconds(c.getLong(1)), c.requireString(2), c.requireString(3))) } }
    }

    /** Saves a lesson and any new homework with it, in one transaction. */
    suspend fun saveLesson(lesson: Lesson, newHomework: List<Homework>): Long = withContext(Dispatchers.Blocking) {
        val d = db.writableDatabase
        val now = Clock.System.now().toEpochMilliseconds()
        d.beginTransaction()
        try {
            val values = SqlValues().apply {
                put("lesson_date", lesson.date.toEpochMilliseconds())
                put("teacher", lesson.teacher.trim())
                put("notes", lesson.notes)
                put("updated_at", now)
            }
            val id = if (lesson.id != 0L) {
                d.update("lessons", values, "id = ?", arrayOf(lesson.id.toString())); lesson.id
            } else {
                values.put("created_at", now); d.insertOrThrow("lessons", null, values)
            }
            newHomework.filter { it.text.isNotBlank() }.forEach { insertHomework(it.copy(lessonId = id), now) }
            d.setTransactionSuccessful()
            id
        } finally {
            d.endTransaction()
        }
    }

    suspend fun deleteLesson(id: Long): Unit = withContext(Dispatchers.Blocking) {
        db.writableDatabase.delete("lessons", "id = ?", arrayOf(id.toString()))
    }

    // ---- homework ----------------------------------------------------------

    suspend fun homework(): List<Homework> = withContext(Dispatchers.Blocking) {
        db.readableDatabase.rawQuery(
            "SELECT id, lesson_id, song_id, text, due_at, done_at FROM homework ORDER BY created_at", null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    Homework(
                        id = c.getLong(0),
                        lessonId = if (c.isNull(1)) null else c.getLong(1),
                        songId = if (c.isNull(2)) null else c.getLong(2),
                        text = c.requireString(3),
                        dueAt = if (c.isNull(4)) null else Instant.fromEpochMilliseconds(c.getLong(4)),
                        doneAt = if (c.isNull(5)) null else Instant.fromEpochMilliseconds(c.getLong(5)),
                    )
                )
            }
        }
    }

    suspend fun addHomework(item: Homework): Long = withContext(Dispatchers.Blocking) {
        insertHomework(item, Clock.System.now().toEpochMilliseconds())
    }

    private fun insertHomework(item: Homework, now: Long): Long = db.writableDatabase.insertOrThrow(
        "homework", null,
        SqlValues().apply {
            if (item.lessonId != null) put("lesson_id", item.lessonId) else putNull("lesson_id")
            if (item.songId != null) put("song_id", item.songId) else putNull("song_id")
            put("text", item.text.trim())
            if (item.dueAt != null) put("due_at", item.dueAt.toEpochMilliseconds()) else putNull("due_at")
            putNull("done_at")
            put("created_at", now)
            put("updated_at", now)
        },
    )

    suspend fun setDone(id: Long, done: Boolean): Unit = withContext(Dispatchers.Blocking) {
        db.writableDatabase.update(
            "homework",
            SqlValues().apply {
                if (done) put("done_at", Clock.System.now().toEpochMilliseconds()) else putNull("done_at")
                put("updated_at", Clock.System.now().toEpochMilliseconds())
            },
            "id = ?", arrayOf(id.toString()),
        )
    }

    suspend fun deleteHomework(id: Long): Unit = withContext(Dispatchers.Blocking) {
        db.writableDatabase.delete("homework", "id = ?", arrayOf(id.toString()))
    }

    // ---- routines ----------------------------------------------------------

    suspend fun routines(): List<Routine> = withContext(Dispatchers.Blocking) {
        val d = db.readableDatabase
        val heads = d.rawQuery("SELECT id, name FROM routines ORDER BY name", null)
            .use { c -> buildList { while (c.moveToNext()) add(c.getLong(0) to c.requireString(1)) } }
        heads.map { (id, name) ->
            val steps = d.rawQuery(
                "SELECT kind, title, minutes, link FROM routine_steps WHERE routine_id = ? ORDER BY position",
                arrayOf(id.toString()),
            ).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val kind = StepKind.entries.firstOrNull { it.name == c.getString(0) } ?: StepKind.TECHNIQUE
                        add(RoutineStep(kind, c.requireString(1), c.getInt(2), c.requireString(3)))
                    }
                }
            }
            Routine(id, name, steps)
        }
    }

    /** Saves a routine whole — its steps replaced — so step order is exactly what is on screen. */
    suspend fun saveRoutine(routine: Routine): Long = withContext(Dispatchers.Blocking) {
        val d = db.writableDatabase
        val now = Clock.System.now().toEpochMilliseconds()
        d.beginTransaction()
        try {
            val id = if (routine.id != 0L) {
                d.update("routines", SqlValues().apply { put("name", routine.name.trim()); put("updated_at", now) },
                    "id = ?", arrayOf(routine.id.toString()))
                d.delete("routine_steps", "routine_id = ?", arrayOf(routine.id.toString()))
                routine.id
            } else {
                d.insertOrThrow("routines", null, SqlValues().apply {
                    put("name", routine.name.trim()); put("created_at", now); put("updated_at", now)
                })
            }
            routine.steps.forEachIndexed { i, step ->
                d.insertOrThrow("routine_steps", null, SqlValues().apply {
                    put("routine_id", id); put("position", i); put("kind", step.kind.name)
                    put("title", step.title); put("minutes", step.minutes.coerceIn(1, 120)); put("link", step.link)
                })
            }
            d.setTransactionSuccessful()
            id
        } finally {
            d.endTransaction()
        }
    }

    suspend fun deleteRoutine(id: Long): Unit = withContext(Dispatchers.Blocking) {
        db.writableDatabase.delete("routines", "id = ?", arrayOf(id.toString()))
    }

    companion object {
        /** Which routine Today's plan uses. */
        const val SELECTED_ROUTINE = "selected_routine_id"
    }
}
