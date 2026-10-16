package com.riffbuddy.sync

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.riffbuddy.data.BackupFormat
import com.riffbuddy.data.BackupRepo
import com.riffbuddy.data.SecureStore
import com.riffbuddy.platform.AppLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.UUID

/** A device this one is paired with. */
data class PairedDevice(
    val peer: SyncPeer,
    /** Where it was last reached, for when it can't be heard on the network. */
    val address: String? = null,
    val lastSynced: Long? = null,
)

/** Six digits on screen, waiting for the person to say whether the other device shows the same. */
class PairPrompt(val peer: SyncPeer, val code: String, internal val answer: CompletableDeferred<Boolean>) {
    fun reply(match: Boolean) { answer.complete(match) }
}

/**
 * Syncing with the player's other devices on the same network: no account,
 * no server, nothing leaves the house.
 *
 * Runs only while the app is open: it listens for the other devices'
 * "here I am", says its own, and answers a paired device that asks to sync.
 * A device that is closed isn't there to sync with, which is the simple
 * rule and the only one a phone allows without running in the background.
 *
 * What syncs is the backup, merged both ways (a song added on each ends up
 * on both, the newer edit wins, a deletion is carried across). API keys and
 * the pairings themselves never do. Before each merge a copy of this
 * device's data is kept, so the last sync can be undone here.
 */
class SyncService(
    private val backup: BackupRepo,
    private val keys: SecureStore,
    filesDir: File,
    private val cacheDir: File,
    /** What to call this device until the player names it. */
    private val defaultName: String,
    /** Lets broadcasts in while held; a phone drops them otherwise to save power. */
    private val holdNetwork: () -> AutoCloseable = { AutoCloseable {} },
    private val port: Int = PORT,
) {
    private val dir = File(filesDir, "sync")
    private val file = File(dir, "devices.json")
    private val snapshot = File(dir, "before-last-sync.riffbuddy")

    /** Paired devices, this device's own id and name, and what the last sync can undo. */
    var devices by mutableStateOf(emptyList<PairedDevice>())
        private set
    var me by mutableStateOf(SyncPeer("", defaultName))
        private set
    var undo by mutableStateOf<UndoInfo?>(null)
        private set

    /** Devices heard on the network just now. */
    var nearby by mutableStateOf(emptyList<Nearby>())
        private set

    /** Something under way ("Syncing with Tablet…"), a result, or a problem, for the screen. */
    var busy by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var problem by mutableStateOf<String?>(null)
        private set

    /** What another device just did here, for a moment's notice on whatever screen is showing. */
    var notice by mutableStateOf<String?>(null)

    /** A pairing waiting for the person's answer, from whichever device asked. */
    var prompt by mutableStateOf<PairPrompt?>(null)
        private set

    /** Goes up each time a sync changed this device's data, so screens reload. */
    var dataVersion by mutableIntStateOf(0)
        private set

    /** Whether this device can be reached; false when another app holds the port. */
    var listening by mutableStateOf(false)
        private set

    data class UndoInfo(val peerName: String, val at: Long)

    private val discovery = SyncDiscovery(port, { me }, known = { devices.mapNotNull { it.address } })
    private val oneAtATime = Mutex()
    private var server: ServerSocket? = null
    private var jobs = mutableListOf<Job>()
    private var networkHold: AutoCloseable? = null

    init { load() }

    // ---- Open and closed -----------------------------------------------------

    /** The app came to the front: be findable and answer. */
    fun start(scope: CoroutineScope) {
        if (jobs.isNotEmpty()) return
        networkHold = runCatching(holdNetwork).getOrNull()
        discovery.start(scope)
        jobs += scope.launch { discovery.nearby.collect { nearby = it } }
        jobs += scope.launch(Dispatchers.IO) { serve(this) }
    }

    /** The app went to the back: stop listening, and stop saying we're here. */
    fun stop() {
        jobs.forEach { it.cancel() }; jobs.clear()
        discovery.stop()
        runCatching { server?.close() }; server = null
        runCatching { networkHold?.close() }; networkHold = null
        listening = false
        nearby = emptyList()
    }

    /**
     * Listens until stopped. A port still held a moment longer by the app
     * that had it (another copy closing as this one opens, a network
     * switching) is tried again every few seconds rather than given up on.
     */
    private suspend fun serve(scope: CoroutineScope) {
        var warned = false
        val listenOn = InetSocketAddress(port)
        while (scope.isActive) {
            val socket = runCatching {
                ServerSocket().apply { reuseAddress = true; bind(listenOn) }
            }.getOrElse {
                if (!warned) AppLog.w(TAG, "can't listen on $port yet; trying again", it)
                warned = true
                delay(RETRY_MS)
                continue
            }
            server = socket
            listening = true
            try {
                while (true) {
                    val client = socket.accept()
                    scope.launch(Dispatchers.IO) { answer(client) }
                }
            } catch (e: Exception) {
                // Closed by stop(), which also ends this loop, or the network went away.
            } finally {
                listening = false
                runCatching { socket.close() }
            }
            if (scope.isActive) delay(RETRY_MS)
        }
    }

    private suspend fun answer(client: Socket) {
        client.use { s ->
            s.soTimeout = TIMEOUT_MS
            val link = SyncLink(s.getInputStream(), s.getOutputStream())
            if (!oneAtATime.tryLock()) {
                runCatching {
                    link.receive()
                    link.send(JSONObject().put("app", SyncProtocol.APP).put("v", SyncProtocol.VERSION).put("id", me.id)
                        .put("name", me.name).put("ok", false).put("reason", "${me.name} is busy syncing. Try again in a minute."))
                }
                return
            }
            try {
                val address = s.inetAddress?.hostAddress
                when (val result = SyncProtocol.answer(link, me, ::decide, ::secretFor, data, cacheDir)) {
                    is SyncProtocol.Answered.Paired -> {
                        remember(result.peer, result.secret, address)
                        say("Paired with ${result.peer.name}.")
                        notice = message
                    }
                    is SyncProtocol.Answered.Synced -> {
                        val o = result.outcome
                        synced(o.peer, address)
                        say("Synced with ${o.peer.name}: ${o.here.words()} here.")
                        notice = message
                    }
                }
            } catch (e: Exception) {
                AppLog.w(TAG, "answering a device failed", e)
                if (e is SyncRefused) problem = e.message
            } finally {
                busy = null
                oneAtATime.unlock()
            }
        }
    }

    // ---- Asking ------------------------------------------------------------

    /** Pairs with a device heard nearby, or at an address typed in. */
    suspend fun pair(address: String) = talk("Pairing…", address) { link ->
        val (peer, secret) = SyncProtocol.askToPair(link, me, ::decide)
        remember(peer, secret, address)
        say("Paired with ${peer.name}. You can sync from either one now.")
    }

    /** Syncs both ways with a paired device, wherever it is now. */
    suspend fun sync(device: PairedDevice) {
        val address = nearby.firstOrNull { it.peer.id == device.peer.id }?.address ?: device.address
        if (address == null) {
            problem = "Can't find ${device.peer.name}. Open Riff Buddy on it, on the same Wi-Fi."
            return
        }
        val secret = secretFor(device.peer.id) ?: run {
            problem = "This device lost its pairing with ${device.peer.name}. Unpair it and pair again."
            return
        }
        talk("Syncing with ${device.peer.name}…", address) { link ->
            val outcome = SyncProtocol.askToSync(link, me, device.peer, secret, data, cacheDir)
            synced(outcome.peer, address)
            say("Synced with ${outcome.peer.name}: ${outcome.here.words()} here, ${outcome.there.words()} there.")
        }
    }

    private suspend fun talk(doing: String, address: String, block: suspend (SyncLink) -> Unit) {
        if (!oneAtATime.tryLock()) { problem = "A sync is already under way."; return }
        busy = doing; message = null; problem = null
        try {
            withContext(Dispatchers.IO) {
                Socket().use { s ->
                    // "host:port" reaches a copy listening elsewhere: two on one computer.
                    val host = address.trim().substringBefore(':')
                    val at = address.trim().substringAfter(':', "").toIntOrNull() ?: port
                    s.connect(InetSocketAddress(host, at), CONNECT_MS)
                    s.soTimeout = TIMEOUT_MS
                    block(SyncLink(s.getInputStream(), s.getOutputStream()))
                }
            }
        } catch (e: SyncRefused) {
            problem = e.message
        } catch (e: java.net.SocketTimeoutException) {
            problem = "No answer from $address. Is Riff Buddy open there, on the same Wi-Fi?"
        } catch (e: java.io.IOException) {
            AppLog.w(TAG, "sync with $address failed", e)
            problem = "Couldn't reach $address. Is Riff Buddy open there, on the same Wi-Fi? (${e.message})"
        } finally {
            busy = null
            oneAtATime.unlock()
        }
    }

    /** Shows the digits and waits for the person; closing the question counts as "no". */
    private suspend fun decide(code: String, other: SyncPeer): Boolean {
        val p = PairPrompt(other, code, CompletableDeferred())
        prompt = p
        return try { p.answer.await() } finally { if (prompt === p) prompt = null }
    }

    private fun say(text: String) { message = text; problem = null }

    fun dismissMessages() { message = null; problem = null }

    // ---- The data ----------------------------------------------------------

    private val data = object : SyncData {
        override suspend fun storedFiles() = backup.storedFiles()

        override suspend fun export(out: OutputStream, include: (String) -> Boolean) {
            backup.exportTo(out, include)
        }

        override suspend fun merge(zip: File, from: SyncPeer): SyncSummary {
            busy = "Adding what ${from.name} has…"
            // Files the previous sync left for its undo can go now: this
            // sync's copy replaces that one.
            backup.sweep()
            dir.mkdirs()
            val partial = File(dir, snapshot.name + ".part")
            partial.outputStream().use { backup.exportTo(it) { false } }
            replace(partial, snapshot)
            val contents = backup.inspectFile(zip).getOrThrow()
            BackupFormat.validate(contents.root)?.let { throw SyncRefused(it.message) }
            val restored = backup.restore(contents, BackupFormat.Mode.MERGE, sweep = false).getOrThrow()
            undo = UndoInfo(from.name, System.currentTimeMillis())
            save()
            dataVersion++
            return SyncSummary(restored.counts.values.sum(), restored.updated, restored.removed, restored.files)
        }
    }

    /**
     * Puts this device back as it was before the last sync. The other
     * device keeps what it took in, so the next sync brings it back unless
     * it is changed there too.
     */
    suspend fun undoLastSync() {
        if (!snapshot.isFile || !oneAtATime.tryLock()) return
        busy = "Undoing the last sync…"; message = null; problem = null
        try {
            val copy = File(cacheDir.apply { mkdirs() }, "undo-${System.currentTimeMillis()}.zip")
            withContext(Dispatchers.IO) { snapshot.copyTo(copy, overwrite = true) }
            val restored = backup.inspectFile(copy).getOrThrow()
            backup.restore(restored, BackupFormat.Mode.REPLACE).getOrThrow()
            snapshot.delete()
            undo = null
            save()
            dataVersion++
            say("Back to how this device was before the last sync.")
        } catch (e: Exception) {
            AppLog.w(TAG, "undo failed", e)
            problem = "Couldn't undo the last sync: ${e.message}"
        } finally {
            busy = null
            oneAtATime.unlock()
        }
    }

    // ---- What this device remembers ----------------------------------------

    fun rename(name: String) {
        val clean = name.trim().take(40)
        if (clean.isEmpty()) return
        me = me.copy(name = clean)
        save()
    }

    fun unpair(device: PairedDevice) {
        keys.removeSecret(secretName(device.peer.id))
        devices = devices.filter { it.peer.id != device.peer.id }
        save()
    }

    private fun secretName(id: String) = "sync:$id"

    private fun secretFor(id: String): ByteArray? =
        keys.getSecret(secretName(id))?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }

    private fun remember(peer: SyncPeer, secret: ByteArray, address: String?) {
        keys.putSecret(secretName(peer.id), Base64.getEncoder().encodeToString(secret))
        devices = devices.filter { it.peer.id != peer.id } + PairedDevice(peer, address)
        save()
    }

    private fun synced(peer: SyncPeer, address: String?) {
        devices = devices.map {
            if (it.peer.id == peer.id) it.copy(peer = peer, address = address ?: it.address, lastSynced = System.currentTimeMillis()) else it
        }
        save()
    }

    private fun load() {
        val o = runCatching { JSONObject(file.readText()) }.getOrNull()
        val id = o?.optString("id")?.takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString()
        me = SyncPeer(id, o?.optString("name")?.takeIf { it.isNotEmpty() } ?: defaultName)
        devices = o?.optJSONArray("devices")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val d = a.optJSONObject(i) ?: return@mapNotNull null
                PairedDevice(
                    SyncPeer(d.optString("id"), d.optString("name")),
                    d.optString("address").takeIf { it.isNotEmpty() },
                    d.optLong("lastSynced", 0).takeIf { it > 0 },
                )
            }
        }.orEmpty()
        undo = o?.optJSONObject("undo")?.takeIf { snapshot.isFile }?.let { UndoInfo(it.optString("from"), it.optLong("at")) }
        if (o == null) save()
    }

    private fun save() {
        // The name only when the player chose one, so a better default (a
        // later version naming this device better) still reaches the rest.
        val o = JSONObject().put("id", me.id).put("name", me.name.takeIf { it != defaultName } ?: "")
            .put("devices", JSONArray(devices.map { d ->
            JSONObject().put("id", d.peer.id).put("name", d.peer.name).put("address", d.address ?: "").put("lastSynced", d.lastSynced ?: 0)
        }))
        undo?.let { o.put("undo", JSONObject().put("from", it.peerName).put("at", it.at)) }
        runCatching {
            dir.mkdirs()
            val partial = File(dir, file.name + ".part")
            partial.writeText(o.toString())
            replace(partial, file)
        }.onFailure { AppLog.w(TAG, "couldn't save the paired devices", it) }
    }

    /** Written beside, then moved over: a crash midway leaves the old file, never half a new one. */
    private fun replace(partial: File, target: File) {
        java.nio.file.Files.move(partial.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        /** UDP for "here I am", TCP for the conversation; the one number to allow through a firewall. */
        const val PORT = 47321
        private const val CONNECT_MS = 5000
        private const val RETRY_MS = 3000L
        /** Long enough for a person to compare six digits, and for a slow tablet to merge. */
        private const val TIMEOUT_MS = 180_000
        private const val TAG = "RiffSync"
    }
}
