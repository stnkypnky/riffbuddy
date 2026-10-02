package com.riffbuddy

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Shared and desktop code explain themselves in plain terms.
 *
 * Comments here say why the code is the way it is: a trade-off, a platform
 * quirk, a measurement. They do not cite requirement or decision numbers,
 * planning documents, milestones, test devices or people, which mean nothing
 * to someone reading the code without those documents. This test is what
 * keeps that true as the code changes.
 */
class CleanSourceTest {

    /** Not allowed anywhere in a file: code, strings, test names or comments. */
    private val anywhere = listOf(
        Regex("""\b(FR|DR)-\d"""),
        // Split where a literal would match itself, here and in the release
        // snapshot's own scan.
        Regex("""\b""" + "BR" + """D\b"""),
        Regex("SESSION" + """_LOG|DECISIONS\.md|ACCEPTANCE\.md"""),
        Regex("Jo" + "ey"),
    )

    /**
     * Not allowed in comments. Strings are exempt, because a chord name or a
     * line of help text can legitimately contain any of these.
     */
    private val inComments = listOf(
        // Milestone and user-journey labels from the planning documents.
        Regex("""\b[MJ]\d{1,2}\b"""),
        Regex("""\bFire\b|Fire HD|\bS22\b"""),
        // Split for the same reason as above.
        Regex("""\b(""" + listOf("h" + "e", "h" + "im", "h" + "is", "h" + "imself").joinToString("|") + """)\b"""),
    )

    @Test
    fun `shared and desktop sources carry no process notes`() {
        val files = sources()
        assertTrue("found no shared sources to scan", files.isNotEmpty())

        val offences = files.flatMap { file ->
            val text = file.readText()
            val comments = commentsOf(text)
            anywhere.mapNotNull { re -> re.find(text)?.let { "${file.name}: \"${it.value}\"" } } +
                inComments.mapNotNull { re -> re.find(comments)?.let { "${file.name} (comment): \"${it.value}\"" } }
        }
        if (offences.isNotEmpty()) fail(offences.joinToString("\n", prefix = "\n"))
    }

    private fun commentsOf(text: String): String =
        Regex("""/\*.*?\*/|//[^\n]*""", RegexOption.DOT_MATCHES_ALL)
            .findAll(text).joinToString("\n") { it.value }

    /** Every Kotlin file under shared/ and desktop/src, wherever the tests run from. */
    private fun sources(): List<File> {
        val module = generateSequence(File("").absoluteFile) { it.parentFile }
            .flatMap { sequenceOf(it, File(it, "apps/riff_buddy")) }
            .firstOrNull { File(it, "shared/src").isDirectory }
            ?: return emptyList()
        return listOf("shared/src", "desktop/src")
            .map { File(module, it) }
            .filter { it.isDirectory }
            .flatMap { root -> root.walkTopDown().filter { it.extension == "kt" || it.extension == "kts" }.toList() }
    }
}
