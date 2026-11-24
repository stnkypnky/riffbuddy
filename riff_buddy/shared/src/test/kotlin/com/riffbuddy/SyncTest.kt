package com.riffbuddy

import com.riffbuddy.sync.SyncCrypto
import com.riffbuddy.sync.SyncData
import com.riffbuddy.sync.SyncLink
import com.riffbuddy.sync.SyncPeer
import com.riffbuddy.sync.SyncProtocol
import com.riffbuddy.sync.SyncRefused
import com.riffbuddy.sync.SyncSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files

/**
 * Pairing and syncing between two devices, over a real connection on this
 * computer. The data behind each side is a stand-in here; the desktop tests
 * run the same conversation between two real databases.
 */
class SyncTest {

    // ---- The cryptography ----------------------------------------------------

    @Test
    fun `both sides of a key agreement arrive at the same secret, and show the same digits`() {
        val a = SyncCrypto.newKeyPair()
        val b = SyncCrypto.newKeyPair()
        val fromA = SyncCrypto.agree(a, SyncCrypto.publicBytes(b))
        val fromB = SyncCrypto.agree(b, SyncCrypto.publicBytes(a))
        assertArrayEquals(fromA, fromB)
        val transcript = SyncCrypto.transcript(SyncCrypto.publicBytes(a), SyncCrypto.publicBytes(b), "a", "b")
        val code = SyncCrypto.confirmCode(fromA, transcript)
        assertEquals(code, SyncCrypto.confirmCode(fromB, transcript))
        assertTrue(code, code.matches(Regex("\\d{6}")))
    }

    @Test
    fun `someone in the middle ends up with different digits on each side`() {
        val a = SyncCrypto.newKeyPair()
        val b = SyncCrypto.newKeyPair()
        val m = SyncCrypto.newKeyPair() // swapped in for each side's view of the other
        val aSees = SyncCrypto.transcript(SyncCrypto.publicBytes(a), SyncCrypto.publicBytes(m), "a", "b")
        val bSees = SyncCrypto.transcript(SyncCrypto.publicBytes(m), SyncCrypto.publicBytes(b), "a", "b")
        assertNotEquals(
            SyncCrypto.confirmCode(SyncCrypto.agree(a, SyncCrypto.publicBytes(m)), aSees),
            SyncCrypto.confirmCode(SyncCrypto.agree(b, SyncCrypto.publicBytes(m)), bSees),
        )
    }

    @Test
    fun `a sealed message opens once, in order, and not at all if changed`() {
        val key = SyncCrypto.randomBytes(32)
        val send = SyncCrypto.Sealer(key)
        val first = send.seal("one".toByteArray())
        val second = send.seal("two".toByteArray())

        assertEquals("one", String(SyncCrypto.Sealer(key).open(first)))
        assertFails("out of order") { SyncCrypto.Sealer(key).open(second) }
        assertFails("changed") { SyncCrypto.Sealer(key).open(first.copyOf().also { it[0] = (it[0] + 1).toByte() }) }
        assertFails("another key") { SyncCrypto.Sealer(SyncCrypto.randomBytes(32)).open(first) }
        val replayed = SyncCrypto.Sealer(key).apply { open(first) }
        assertFails("replayed") { replayed.open(first) }
    }

    @Test
    fun `a file goes through a link in chunks, sealed, whatever its size`() {
        val key1 = SyncCrypto.randomBytes(32)
        val key2 = SyncCrypto.randomBytes(32)
        val wire = ByteArrayOutputStream()
        val bytes = SyncCrypto.randomBytes(200_000)
        runBlocking {
            SyncLink(ByteArrayInputStream(ByteArray(0)), wire).apply { secure(key1, key2) }.sendFile { it.write(bytes) }
        }
        val got = ByteArrayOutputStream()
        SyncLink(ByteArrayInputStream(wire.toByteArray()), ByteArrayOutputStream()).apply { secure(key2, key1) }.receiveFile(got)
        assertArrayEquals(bytes, got.toByteArray())
        assertFalse("nothing travels in the clear", String(wire.toByteArray(), Charsets.ISO_8859_1).contains(String(bytes.copyOf(64), Charsets.ISO_8859_1)))
    }

    private fun assertFails(what: String, block: () -> Unit) {
        try { block(); fail("$what should not open") } catch (e: javax.crypto.AEADBadTagException) { }
    }

    // ---- The conversation ----------------------------------------------------

    private val phone = SyncPeer("phone-id", "Phone")
    private val tablet = SyncPeer("tablet-id", "Tablet")
    private val cache: File = Files.createTempDirectory("sync-test").toFile().apply { deleteOnExit() }

    /** Runs [answer] on one end of a fresh connection and [ask] on the other. */
    private fun <A, B> converse(
        ask: suspend (SyncLink) -> A,
        answer: suspend (SyncLink) -> B,
    ): Pair<Result<A>, Result<B>> = runBlocking(Dispatchers.IO) {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            withTimeout(20_000) {
                val answering = async {
                    server.accept().use { s -> runCatching { answer(SyncLink(s.getInputStream(), s.getOutputStream())) } }
                }
                val asked = Socket(InetAddress.getLoopbackAddress(), server.localPort).use { s ->
                    runCatching { ask(SyncLink(s.getInputStream(), s.getOutputStream())) }
                }
                asked to answering.await()
            }
        }
    }

    private class Data(val files: Set<String>, val rows: String) : SyncData {
        var received: String? = null
        var sentFiles: List<String> = emptyList()
        override suspend fun storedFiles() = files
        override suspend fun export(out: OutputStream, include: (String) -> Boolean) {
            sentFiles = files.filter(include).sorted()
            out.write("$rows|${sentFiles.joinToString(",")}".toByteArray())
        }
        override suspend fun merge(zip: File, from: SyncPeer): SyncSummary {
            received = zip.readText()
            return SyncSummary(added = 1)
        }
    }

    private fun pair(phoneSays: Boolean = true, tabletSays: Boolean = true) = converse(
        ask = { SyncProtocol.askToPair(it, phone) { _, _ -> phoneSays } },
        answer = { link ->
            SyncProtocol.answer(link, tablet, { _, _ -> tabletSays }, { null }, Data(emptySet(), ""), cache)
        },
    )

    @Test
    fun `two yeses pair both devices with the same secret, and the same digits were shown`() {
        val codes = mutableListOf<String>()
        val (asked, answered) = converse(
            ask = { SyncProtocol.askToPair(it, phone) { code, other -> codes += code; assertEquals(tablet, other); true } },
            answer = { link ->
                SyncProtocol.answer(link, tablet, { code, other -> codes += code; assertEquals(phone, other); true }, { null }, Data(emptySet(), ""), cache)
            },
        )
        val (peer, secret) = asked.getOrThrow()
        val paired = answered.getOrThrow() as SyncProtocol.Answered.Paired
        assertEquals(tablet, peer)
        assertEquals(phone, paired.peer)
        assertArrayEquals(secret, paired.secret)
        assertEquals(1, codes.distinct().size)
    }

    @Test
    fun `a no on either side pairs neither`() {
        for ((p, t) in listOf(false to true, true to false)) {
            val (asked, answered) = pair(phoneSays = p, tabletSays = t)
            assertTrue(asked.exceptionOrNull() is SyncRefused)
            assertTrue(answered.exceptionOrNull() is SyncRefused)
        }
    }

    @Test
    fun `a sync carries each side's backup, without the files the other already has`() {
        val secret = SyncCrypto.randomBytes(32)
        val phoneData = Data(setOf("tabs/a.gp", "recordings/b.wav"), "phone rows")
        val tabletData = Data(setOf("tabs/a.gp", "recordings/c.wav"), "tablet rows")
        val (asked, answered) = converse(
            ask = { SyncProtocol.askToSync(it, phone, tablet, secret, phoneData, cache) },
            answer = { link -> SyncProtocol.answer(link, tablet, { _, _ -> false }, { id -> secret.takeIf { id == phone.id } }, tabletData, cache) },
        )
        assertEquals(tablet, asked.getOrThrow().peer)
        assertEquals(phone, (answered.getOrThrow() as SyncProtocol.Answered.Synced).outcome.peer)
        assertEquals("phone rows|recordings/b.wav", tabletData.received)
        assertEquals("tablet rows|recordings/c.wav", phoneData.received)
        assertEquals(1, asked.getOrThrow().there.added)
    }

    @Test
    fun `a device that isn't paired, or has the wrong secret, gets nothing`() {
        val tabletData = Data(setOf("tabs/a.gp"), "tablet rows")
        val (notPaired, _) = converse(
            ask = { SyncProtocol.askToSync(it, phone, tablet, SyncCrypto.randomBytes(32), Data(emptySet(), "x"), cache) },
            answer = { link -> SyncProtocol.answer(link, tablet, { _, _ -> false }, { null }, tabletData, cache) },
        )
        assertTrue(notPaired.exceptionOrNull() is SyncRefused)

        val (wrongSecret, answered) = converse(
            ask = { SyncProtocol.askToSync(it, phone, tablet, SyncCrypto.randomBytes(32), Data(emptySet(), "x"), cache) },
            answer = { link -> SyncProtocol.answer(link, tablet, { _, _ -> false }, { SyncCrypto.randomBytes(32) }, tabletData, cache) },
        )
        assertTrue(wrongSecret.isFailure)
        assertTrue(answered.isFailure)
        assertEquals("nothing was merged", null, tabletData.received)
    }

    // ---- Discovery -----------------------------------------------------------

    @Test
    fun `discovery lists a device it hears, but not itself, and answers it directly`() = runBlocking {
        val port = java.net.DatagramSocket(0).use { it.localPort }
        val discovery = com.riffbuddy.sync.SyncDiscovery(port, { phone })
        val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO)
        discovery.start(scope)
        // Stands in for the tablet: its own socket, so the direct answer has somewhere to land.
        val other = java.net.DatagramSocket(0, InetAddress.getLoopbackAddress()).apply { soTimeout = 3000 }
        try {
            fun say(peer: SyncPeer) {
                val bytes = com.riffbuddy.json.JSONObject().put("app", SyncProtocol.APP).put("v", 1)
                    .put("id", peer.id).put("name", peer.name).toString().toByteArray()
                other.send(java.net.DatagramPacket(bytes, bytes.size, InetAddress.getLoopbackAddress(), port))
            }
            withTimeout(5000) {
                while (discovery.nearby.value.isEmpty()) { say(phone); say(tablet); kotlinx.coroutines.delay(100) }
            }
            assertEquals(listOf(tablet), discovery.nearby.value.map { it.peer })
            val answer = java.net.DatagramPacket(ByteArray(1024), 1024)
            other.receive(answer)
            assertEquals(phone.id, com.riffbuddy.json.JSONObject(String(answer.data, 0, answer.length)).getString("id"))
        } finally {
            discovery.stop()
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            other.close()
        }
    }

    // ---- When a device can't be reached ---------------------------------------

    @Test
    fun `a failed connection is told apart by its error, as each platform words it`() {
        val k = com.riffbuddy.sync.SyncTrouble::kind
        // Android, word for word from a phone that couldn't find a computer on the network.
        val android = java.net.ConnectException(
            "failed to connect to /192.168.1.76 (port 47321) from /192.168.1.65 (port 43360) after 5000ms: " +
                "isConnected failed: EHOSTUNREACH (No route to host)",
        )
        assertEquals(com.riffbuddy.sync.SyncTrouble.Kind.NOT_FOUND, k(android, false))
        assertEquals(com.riffbuddy.sync.SyncTrouble.Kind.NOT_FOUND, k(java.net.NoRouteToHostException("No route to host"), false))
        assertEquals(com.riffbuddy.sync.SyncTrouble.Kind.REFUSED, k(java.net.ConnectException("Connection refused"), false))
        assertEquals(com.riffbuddy.sync.SyncTrouble.Kind.REFUSED, k(java.net.ConnectException("failed to connect: ECONNREFUSED (Connection refused)"), false))
        assertEquals(com.riffbuddy.sync.SyncTrouble.Kind.REFUSED, k(java.net.SocketException("Connection reset by peer"), false))
        assertEquals(com.riffbuddy.sync.SyncTrouble.Kind.NO_ANSWER, k(java.net.SocketTimeoutException("Connect timed out"), false))
        assertEquals(com.riffbuddy.sync.SyncTrouble.Kind.DROPPED, k(java.net.SocketException("Connection reset"), true))
        assertEquals(com.riffbuddy.sync.SyncTrouble.Kind.OTHER, k(java.io.IOException("Something else"), false))

        val advice = com.riffbuddy.sync.SyncTrouble.advice(android, false, "the PC")
        assertTrue(advice, advice.contains("try it from the PC instead"))
        assertTrue(com.riffbuddy.sync.SyncTrouble.advice(java.net.SocketTimeoutException(), false, "the PC").contains("firewall"))
    }
}
