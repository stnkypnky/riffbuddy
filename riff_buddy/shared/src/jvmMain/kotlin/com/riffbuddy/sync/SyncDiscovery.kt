package com.riffbuddy.sync

import com.riffbuddy.domain.nowMillis
import com.riffbuddy.json.JSONObject
import com.riffbuddy.platform.AppLog
import com.riffbuddy.platform.Blocking
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** A device heard on the network: who, where to reach it, and when it last spoke. */
data class Nearby(val peer: SyncPeer, val address: String, val heardAt: Long)

/**
 * Finding the other devices without anyone typing an address.
 *
 * While the app is open, each device sends a short "here I am" (its id, its
 * name) to the whole local network every couple of seconds: a UDP
 * broadcast, a postcard to every address at once rather than a letter to
 * one. Each device also listens, and lists who it has heard lately.
 *
 * It says nothing secret and is trusted for nothing: it only tells this
 * device where to knock. Who answers the knock is proven by the pair
 * secret.
 *
 * Routers differ in what they pass between devices, so the postcard goes
 * three ways: to a multicast group every copy joins (the way most often let
 * through), as a broadcast, and directly to each paired device's last known
 * address. A device hearing another for the first time answers it directly,
 * so hearing in one direction is enough. Where nothing gets through, a
 * device can still be reached by typing its address.
 */
class SyncDiscovery(
    private val port: Int,
    private val me: () -> SyncPeer,
    /** Where paired devices were last reached: told directly too, whatever the router passes. */
    private val known: () -> List<String> = { emptyList() },
) {

    private val _nearby = MutableStateFlow<List<Nearby>>(emptyList())
    val nearby: StateFlow<List<Nearby>> = _nearby

    private var job: Job? = null
    private var socket: DatagramSocket? = null

    fun start(scope: CoroutineScope) {
        if (job != null) return
        // Named outside apply(): inside it, "port" is the socket's own (-1, unconnected).
        val listenOn = InetSocketAddress(port)
        val s = runCatching {
            MulticastSocket(null).apply {
                // Another copy of the app on the same computer can listen too.
                reuseAddress = true
                broadcast = true
                soTimeout = 1000
                timeToLive = 1 // this network only, never past the router
                bind(listenOn)
                joinEverywhere(this)
            }
        }.getOrElse {
            AppLog.w("RiffSync", "can't listen for other devices on $port", it)
            return
        }
        socket = s
        job = scope.launch(Dispatchers.Blocking) {
            launch { while (isActive) { announce(s); delay(ANNOUNCE_MS) } }
            val buffer = ByteArray(1024)
            while (isActive) {
                val packet = DatagramPacket(buffer, buffer.size)
                try { s.receive(packet) } catch (e: SocketTimeoutException) { forgetQuiet(); continue } catch (e: Exception) { break }
                if (heard(String(packet.data, 0, packet.length, Charsets.UTF_8), packet.address)) {
                    // Heard for the first time: answer it directly, in case our
                    // broadcasts don't reach it (some routers pass them one way only).
                    val bytes = postcard()
                    runCatching { s.send(DatagramPacket(bytes, bytes.size, packet.socketAddress)) }
                }
                forgetQuiet()
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        socket?.close(); socket = null
        _nearby.value = emptyList()
    }

    private fun postcard(): ByteArray {
        val me = me()
        return JSONObject().put("app", SyncProtocol.APP).put("v", SyncProtocol.VERSION)
            .put("id", me.id).put("name", me.name).toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Joins the group on every network this device is on, so a computer on
     * Wi-Fi and a cable at once hears both. A network that refuses is skipped.
     */
    private fun joinEverywhere(s: MulticastSocket) {
        val group = InetSocketAddress(GROUP, 0)
        val joined = lanInterfaces().count { nif -> runCatching { s.joinGroup(group, nif) }.isSuccess }
        if (joined == 0) runCatching { s.joinGroup(group, null) }
    }

    private fun announce(s: DatagramSocket) {
        val bytes = postcard()
        val direct = known().mapNotNull { runCatching { InetAddress.getByName(it.substringBefore(':')) }.getOrNull() }
        (listOf(GROUP) + broadcastAddresses() + direct).distinct().forEach { address ->
            runCatching { s.send(DatagramPacket(bytes, bytes.size, address, port)) }
                .onFailure { if (warned.add(address.hostAddress ?: "")) AppLog.w("RiffSync", "can't announce to $address", it) }
        }
    }

    /** Each address that refused once, so a failure is logged once, not every two seconds. */
    private val warned = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Notes a device heard; true if it wasn't on the list a moment ago. */
    private fun heard(text: String, from: InetAddress): Boolean {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return false
        if (o.optString("app") != SyncProtocol.APP) return false
        val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return false
        if (id == me().id) return false // our own postcard, come back
        val device = Nearby(SyncPeer(id, o.optString("name", "Another device")), from.hostAddress ?: return false, nowMillis())
        val known = _nearby.value.any { it.peer.id == id }
        _nearby.value = (_nearby.value.filter { it.peer.id != id } + device).sortedBy { it.peer.name.lowercase() }
        return !known
    }

    private fun forgetQuiet() {
        val cutoff = nowMillis() - FORGET_MS
        if (_nearby.value.any { it.heardAt < cutoff }) _nearby.value = _nearby.value.filter { it.heardAt >= cutoff }
    }

    companion object {
        /**
         * The group every copy of the app joins: a multicast address, which
         * routers pass between devices more often than a broadcast (it is how
         * printers and casting are found), and which a phone lets in while
         * the app holds its multicast lock. 239.x is for private use.
         */
        val GROUP: InetAddress = InetAddress.getByName("239.255.47.32")

        const val ANNOUNCE_MS = 2000L
        /** Three postcards missed: the app was closed or the device left. */
        const val FORGET_MS = 7000L

        /**
         * Each network's own broadcast address (192.168.1.255 on a typical
         * home network) as well as the everywhere one: some phones only pass
         * on the first.
         */
        private fun lanInterfaces(): List<NetworkInterface> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && it.supportsMulticast() && it.inetAddresses.toList().any { a -> a is Inet4Address } }
        }.getOrDefault(emptyList())

        fun broadcastAddresses(): List<InetAddress> {
            val own = runCatching {
                NetworkInterface.getNetworkInterfaces().toList()
                    .filter { it.isUp && !it.isLoopback }
                    .flatMap { it.interfaceAddresses }
                    .mapNotNull { it.broadcast }
            }.getOrDefault(emptyList())
            return (own + InetAddress.getByName("255.255.255.255")).distinct()
        }

        /** This device's addresses on the local network, to show for typing in on another. */
        fun localAddresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress }
                .mapNotNull { it.hostAddress }
        }.getOrDefault(emptyList())
    }
}
