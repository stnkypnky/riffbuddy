package com.riffbuddy

import com.riffbuddy.platform.PortableZipReader
import com.riffbuddy.platform.PortableZipWriter
import okio.Path.Companion.toOkioPath
import okio.buffer
import okio.sink
import okio.use
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/**
 * The browser's zip code, held to Java's: a backup the browser writes must
 * open on a phone, and a phone's backup, deflated, must open in the browser.
 */
class PortableZipTest {

    private val dir: File = Files.createTempDirectory("zip").toFile()

    @After
    fun tearDown() { dir.deleteRecursively() }

    private val contents = linkedMapOf(
        "riffbuddy.json" to """{"tables":{"songs":[{"title":"Floods — ünïcödé"}]}}""".repeat(200).encodeToByteArray(),
        "tabs/a.gp" to Random(1).nextBytes(70_000),
        "recordings/take.wav" to ByteArray(300_000) { (it % 97).toByte() },
        "recordings/empty.wav" to ByteArray(0),
    )

    @Test
    fun `what the browser writes, Java reads`() {
        val file = File(dir, "web.zip")
        PortableZipWriter(file.sink().buffer()).use { zip -> contents.forEach { (n, b) -> zip.entry(n) { it.write(b) } } }
        ZipFile(file).use { zip ->
            assertEquals(contents.keys.toList(), zip.entries().asSequence().map { it.name }.toList())
            contents.forEach { (n, b) -> assertArrayEquals(n, b, zip.getInputStream(zip.getEntry(n)).readBytes()) }
        }
    }

    @Test
    fun `what a phone writes, deflated, the browser reads`() {
        val file = File(dir, "phone.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            contents.forEach { (n, b) -> zip.putNextEntry(ZipEntry(n)); zip.write(b); zip.closeEntry() }
        }
        PortableZipReader(file.toOkioPath()).use { zip ->
            assertEquals(contents.keys.toList(), zip.names)
            contents.forEach { (n, b) -> assertArrayEquals(n, b, zip.open(n)!!.buffer().readByteArray()) }
            assertNull(zip.open("not-there"))
        }
    }

    @Test
    fun `the browser reads its own`() {
        val file = File(dir, "round.zip")
        PortableZipWriter(file.sink().buffer()).use { zip -> contents.forEach { (n, b) -> zip.entry(n) { it.write(b) } } }
        PortableZipReader(file.toOkioPath()).use { zip ->
            contents.forEach { (n, b) -> assertArrayEquals(n, b, zip.open(n)!!.buffer().readByteArray()) }
        }
    }
}
