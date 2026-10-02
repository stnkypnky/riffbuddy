package com.riffbuddy.data

import com.riffbuddy.ai.AiResult
import com.riffbuddy.ai.ProviderId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The AI cache and the daily usage counters.
 *
 * Both live in the database rather than in memory because both have to survive
 * the app being closed: a cached tone card is worth nothing if it evaporates
 * when the player switches apps, and a usage counter that resets on launch would let
 * a free tier be burned through without a single warning.
 */
class AiCacheRepo(private val db: Db) {

    suspend fun get(key: String): AiResult.Success? = withContext(Dispatchers.IO) {
        db.readableDatabase.rawQuery(
            "SELECT content_json, provider, model FROM ai_cache WHERE cache_key = ?",
            arrayOf(key),
        ).use { c ->
            if (!c.moveToFirst()) return@withContext null
            val raw = c.requireString(0)
            AiResult.Success(
                text = raw,
                json = runCatching { JSONObject(raw) }.getOrNull(),
                provider = ProviderId.fromName(c.getString(1)),
                model = c.requireString(2),
                elapsedMs = 0,
            )
        }
    }

    suspend fun put(key: String, result: AiResult.Success): Unit = withContext(Dispatchers.IO) {
        val values = SqlValues().apply {
            put("cache_key", key)
            put("feature", "")
            put("content_json", result.json?.toString() ?: result.text)
            put("provider", result.provider.name)
            put("model", result.model)
            put("created_at", Instant.now().toEpochMilli())
        }
        db.writableDatabase.insertWithOnConflict(
            "ai_cache", null, values, Conflict.REPLACE,
        )
    }

    /** Regenerate: drop the cached answer so the next ask really asks. */
    suspend fun invalidate(key: String): Unit = withContext(Dispatchers.IO) {
        db.writableDatabase.delete("ai_cache", "cache_key = ?", arrayOf(key))
    }

    /**
     * Bump today's counter for a provider.
     *
     * UPSERT (`ON CONFLICT … DO UPDATE`) is the natural way to write this and is
     * what was here; it needs SQLite 3.24, which is Android 11 / API 30. minSdk
     * is 26, and on an API 28 tablet it threw `near "ON": syntax error` — so
     * every AI request crashed the app there, since the counter is bumped
     * before the call goes out.
     *
     * Update-then-insert is the portable shape. The transaction is what makes it
     * safe: without it, two requests firing together could both find no row and
     * both insert, and the second would throw on the primary key.
     */
    suspend fun recordUsage(provider: ProviderId): Unit = withContext(Dispatchers.IO) {
        val d = db.writableDatabase
        d.beginTransaction()
        try {
            val updated = d.executeUpdateDelete(
                "UPDATE ai_usage SET requests = requests + 1 WHERE provider = ? AND day = ?",
                arrayOf(provider.name, today()),
            )
            if (updated == 0) {
                d.insert(
                    "ai_usage", null,
                    SqlValues().apply {
                        put("provider", provider.name)
                        put("day", today())
                        put("requests", 1)
                    },
                )
            }
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
    }

    suspend fun usageToday(provider: ProviderId): Int = withContext(Dispatchers.IO) {
        db.readableDatabase.rawQuery(
            "SELECT requests FROM ai_usage WHERE provider = ? AND day = ?",
            arrayOf(provider.name, today()),
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    /**
     * The local day, not UTC.
     *
     * Providers reset on their own schedule, so this cannot be exact — but a
     * counter that rolled over at 4pm local time would look broken to the person
     * reading it, and its job is to warn a human, not to audit a provider.
     */
    private fun today(): String = LocalDate.now(ZoneId.systemDefault()).toString()
}
