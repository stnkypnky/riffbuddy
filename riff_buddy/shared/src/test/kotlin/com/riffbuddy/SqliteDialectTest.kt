package com.riffbuddy

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The SQL in this app has to run on the oldest device it claims to support.
 *
 * minSdk is 26, and Android ships whatever SQLite its release shipped with —
 * there is no bundled copy to fall back on. So a statement that runs on a new
 * phone (API 36, SQLite 3.45) can be a syntax error on an Android 9 tablet
 * (API 28, SQLite 3.22), and a syntax error from `execSQL` is an uncaught
 * exception on whatever thread ran it.
 *
 * That is not hypothetical: `INSERT … ON CONFLICT … DO UPDATE` in the settings
 * store and the AI usage counter once crashed the app on an API 28 tablet
 * every time the coach was used, because UPSERT landed in SQLite 3.24 — API 30.
 *
 * This walks the real source rather than testing a function, because the bug is
 * not in any one function: it is in choosing the wrong dialect anywhere. Reach
 * for `insertWithOnConflict`, or update-then-insert in a transaction, instead.
 */
class SqliteDialectTest {

    /** Construct, the SQLite version that introduced it, and the API level. */
    private val tooNew = listOf(
        Triple(Regex("""ON\s+CONFLICT\s*\([^)]*\)\s*DO\s+(UPDATE|NOTHING)""", RegexOption.IGNORE_CASE),
            "3.24 UPSERT", 30),
        Triple(Regex("""\bRETURNING\b""", RegexOption.IGNORE_CASE), "3.35 RETURNING", 34),
        Triple(Regex("""\bIIF\s*\(""", RegexOption.IGNORE_CASE), "3.32 IIF()", 31),
        Triple(Regex("""\bALTER\s+TABLE\s+\S+\s+DROP\s+COLUMN\b""", RegexOption.IGNORE_CASE),
            "3.35 DROP COLUMN", 34),
        Triple(Regex("""\bOVER\s*\(\s*(PARTITION|ORDER)\b""", RegexOption.IGNORE_CASE),
            "3.25 window functions", 30),
    )

    @Test
    fun `no SQL newer than the oldest supported device`() {
        val sources = mainSources()
        assertTrue("found no Kotlin sources to scan", sources.isNotEmpty())

        val offences = sources.flatMap { file ->
            val code = stripComments(file.readText())
            tooNew.mapNotNull { (pattern, what, api) ->
                pattern.find(code)?.let { match ->
                    "${file.name}: ${match.value.trim()} — $what needs API $api, minSdk is 26"
                }
            }
        }
        if (offences.isNotEmpty()) fail(offences.joinToString("\n", prefix = "\n"))
    }

    /**
     * Comments are stripped before scanning, so a comment may name the construct
     * it is warning about — which is exactly what the two fixed call sites do.
     */
    private fun stripComments(text: String): String = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("""//[^\n]*"""), " ")

    /**
     * Every source root: the Android app's own and the shared ones. Tests run
     * from the app module or the desktop module, so this walks up until it
     * finds the folder that holds `shared/`.
     */
    private fun mainSources(): List<File> {
        val module = generateSequence(File("").absoluteFile) { it.parentFile }
            .flatMap { sequenceOf(it, File(it, "apps/riff_buddy")) }
            .firstOrNull { File(it, "shared/src/commonMain/kotlin").isDirectory }
            ?: return emptyList()
        return listOf("src/main/kotlin", "shared/src/commonMain/kotlin", "shared/src/jvmMain/kotlin")
            .map { File(module, it) }
            .filter { it.isDirectory }
            .flatMap { root -> root.walkTopDown().filter { it.extension == "kt" }.toList() }
    }
}
