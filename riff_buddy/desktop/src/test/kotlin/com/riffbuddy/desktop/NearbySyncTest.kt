package com.riffbuddy.desktop

import com.riffbuddy.AppServices
import com.riffbuddy.ai.OnlineCheck
import com.riffbuddy.ai.ProviderId
import com.riffbuddy.data.SecureStore
import com.riffbuddy.domain.Song
import com.riffbuddy.domain.TakeSource
import com.riffbuddy.sync.SyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files

/**
 * Two copies of the app, each with its own database and files, paired and
 * synced over a connection on this computer: the whole path a phone and a
 * tablet take, short of the Wi-Fi.
 */
class NearbySyncTest {

    private val root = Files.createTempDirectory("riffbuddy-sync").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Keys in memory, so a test never writes to the computer's keychain. */
    private class MemoryKeys : SecureStore {
        val secrets = mutableMapOf<String, String>()
        override fun put(provider: ProviderId, apiKey: String) = Unit
        override fun get(provider: ProviderId): String? = null
        override fun remove(provider: ProviderId) = Unit
        override fun clear() = Unit
        override fun putSecret(name: String, value: String) { secrets[name] = value }
        override fun getSecret(name: String) = secrets[name]
        override fun removeSecret(name: String) { secrets.remove(name) }
    }

    private inner class Device(name: String) {
        val dir = File(root, name).apply { mkdirs() }
        val keys = MemoryKeys()
        val app = AppServices(
            JdbcDb(File(dir, "riffbuddy.db")), DesktopAudioOutputs(), DesktopAudioInputs(), keys,
            OnlineCheck { true }, dir, File(dir, "cache"), "test",
        )
        val port = ServerSocket(0).use { it.localPort }
        val sync = SyncService(app.backup, keys, dir, File(dir, "cache"), defaultName = name, port = port)
        val address get() = "127.0.0.1:$port"

        suspend fun titles() = app.songs.all().map { it.title }.sorted()
        suspend fun song(title: String): Long { delay(3); return app.songs.save(Song(title = title)) }
    }

    @After
    fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
    }

    /** Says "it matches" on whichever device is showing the digits. */
    private fun agreeWhenAsked(vararg devices: Device) = scope.launch {
        while (true) {
            devices.forEach { d -> d.sync.prompt?.reply(true) }
            delay(10)
        }
    }

    private suspend fun waitListening(vararg devices: Device) {
        withTimeout(5000) { while (devices.any { !it.sync.listening }) delay(10) }
    }

    @Test
    fun `pair once, then each device gets the other's songs, deletions and takes`() = runBlocking {
        val phone = Device("Phone")
        val tablet = Device("Tablet")
        phone.sync.start(scope); tablet.sync.start(scope)
        waitListening(phone, tablet)

        // Both know a song before pairing, as after a backup copied across.
        val shared = phone.song("Floods")
        val backupFile = File(root, "copy.zip")
        backupFile.outputStream().use { phone.app.backup.exportTo(it) }
        tablet.app.backup.restore(tablet.app.backup.inspectFile(backupFile).getOrThrow(), com.riffbuddy.data.BackupFormat.Mode.MERGE).getOrThrow()

        val agreeing = agreeWhenAsked(phone, tablet)
        withTimeout(15_000) { phone.sync.pair(tablet.address) }
        agreeing.cancel()
        assertNull(phone.sync.problem, phone.sync.problem)
        assertEquals(listOf("Tablet"), phone.sync.devices.map { it.peer.name })
        withTimeout(5000) { while (tablet.sync.devices.isEmpty()) delay(10) }
        assertEquals(listOf("Phone"), tablet.sync.devices.map { it.peer.name })

        // Each adds a song; the phone deletes the one they share; the tablet records a take.
        phone.song("Little Wing")
        tablet.song("Master of Puppets")
        phone.app.songs.delete(shared)
        val take = tablet.app.recordings.begin("Riff", 48_000, null, null, 0, TakeSource.NONE)
        tablet.app.recordings.fileFor(take).writeBytes(ByteArray(4096) { it.toByte() })

        withTimeout(15_000) { phone.sync.sync(phone.sync.devices.single().copy(address = tablet.address)) }
        assertNull(phone.sync.problem, phone.sync.problem)

        val expected = listOf("Little Wing", "Master of Puppets")
        assertEquals(expected, phone.titles())
        assertEquals(expected, tablet.titles())
        val arrived = phone.app.recordings.all().single()
        assertEquals(4096L, phone.app.recordings.fileFor(arrived).length())
        assertTrue(tablet.sync.notice.orEmpty(), tablet.sync.notice.orEmpty().startsWith("Synced with Phone"))
        assertTrue(phone.sync.dataVersion > 0 && tablet.sync.dataVersion > 0)

        // A second sync finds nothing new.
        withTimeout(15_000) { phone.sync.sync(phone.sync.devices.single().copy(address = tablet.address)) }
        assertTrue(phone.sync.message.orEmpty(), phone.sync.message.orEmpty().contains("nothing new here, nothing new there"))
        assertEquals(expected, phone.titles())
        assertEquals(expected, tablet.titles())
    }

    @Test
    fun `undo puts this device back as it was before the sync, files included`() = runBlocking {
        val phone = Device("Phone")
        val tablet = Device("Tablet")
        phone.sync.start(scope); tablet.sync.start(scope)
        waitListening(phone, tablet)
        val agreeing = agreeWhenAsked(phone, tablet)
        withTimeout(15_000) { phone.sync.pair(tablet.address) }
        agreeing.cancel()

        phone.song("Little Wing")
        val doomed = phone.app.recordings.begin("Old take", 48_000, null, null, 0, TakeSource.NONE)
        phone.app.recordings.fileFor(doomed).writeBytes(ByteArray(100))
        tablet.song("Master of Puppets")
        withTimeout(15_000) { phone.sync.sync(phone.sync.devices.single().copy(address = tablet.address)) }

        // The tablet deletes the take; the next sync deletes it on the phone too.
        tablet.app.recordings.delete(tablet.app.recordings.all().single())
        withTimeout(15_000) { phone.sync.sync(phone.sync.devices.single().copy(address = tablet.address)) }
        assertTrue(phone.app.recordings.all().isEmpty())
        assertEquals("Tablet", phone.sync.undo?.peerName)

        phone.sync.undoLastSync()
        assertNull(phone.sync.problem, phone.sync.problem)
        val back = phone.app.recordings.all().single()
        assertEquals("the take's file was kept for the undo", 100L, phone.app.recordings.fileFor(back).length())
        assertEquals(listOf("Little Wing", "Master of Puppets"), phone.titles())
        assertNull(phone.sync.undo)
    }

    @Test
    fun `a device that was unpaired is refused, and says so`() = runBlocking {
        val phone = Device("Phone")
        val tablet = Device("Tablet")
        phone.sync.start(scope); tablet.sync.start(scope)
        waitListening(phone, tablet)
        val agreeing = agreeWhenAsked(phone, tablet)
        withTimeout(15_000) { phone.sync.pair(tablet.address) }
        agreeing.cancel()
        withTimeout(5000) { while (tablet.sync.devices.isEmpty()) delay(10) }

        tablet.song("Master of Puppets")
        tablet.sync.unpair(tablet.sync.devices.single())
        withTimeout(15_000) { phone.sync.sync(phone.sync.devices.single().copy(address = tablet.address)) }
        assertTrue(phone.sync.problem.orEmpty(), phone.sync.problem.orEmpty().contains("isn't paired"))
        assertTrue(phone.titles().isEmpty())
    }

    @Test
    fun `a port still held by another copy is taken as soon as it frees`() = runBlocking {
        val phone = Device("Phone")
        val blocker = ServerSocket(phone.port)
        phone.sync.start(scope)
        delay(500)
        assertTrue("not listening while the port is held", !phone.sync.listening)
        blocker.close()
        withTimeout(10_000) { while (!phone.sync.listening) delay(50) }
    }

    @Test
    fun `syncing with a device whose app is closed says to open it there`() = runBlocking {
        val phone = Device("Phone")
        val tablet = Device("Tablet")
        phone.sync.start(scope); tablet.sync.start(scope)
        waitListening(phone, tablet)
        val agreeing = agreeWhenAsked(phone, tablet)
        withTimeout(15_000) { phone.sync.pair(tablet.address) }
        agreeing.cancel()

        tablet.sync.stop() // the tablet's app goes to the background
        // Closing a listening socket can take a moment to reach the network;
        // this test is about the message once it has, not about that moment.
        withTimeout(5000) {
            while (runCatching { java.net.Socket("127.0.0.1", tablet.port).close() }.isSuccess) delay(20)
        }
        withTimeout(15_000) { phone.sync.sync(phone.sync.devices.single().copy(address = tablet.address)) }
        val problem = phone.sync.problem.orEmpty()
        assertTrue(problem, problem.startsWith("Tablet is on the network, but Riff Buddy isn't open on it"))
    }
}
