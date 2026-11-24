package com.riffbuddy.sync

import com.riffbuddy.domain.nowMillis
import com.riffbuddy.json.JSONArray
import com.riffbuddy.json.JSONObject
import com.riffbuddy.platform.Blocking
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

/** A device as sync knows it: a random id made once, and a name a person gave it. */
data class SyncPeer(val id: String, val name: String)

/** What sync needs from this device's data: its backup, in practice. */
interface SyncData {
    /** The files this device holds, by their name in a backup. */
    suspend fun storedFiles(): Set<String>
    /** Writes a backup holding only the files [include] picks. */
    suspend fun export(out: OutputStream, include: (String) -> Boolean)
    /** Merges a backup received from [from], after keeping a copy of this device's data to undo it. */
    suspend fun merge(zip: File, from: SyncPeer): SyncSummary
}

/** What one side's merge did, in counts. */
data class SyncSummary(val added: Int = 0, val updated: Int = 0, val removed: Int = 0, val files: Int = 0) {
    fun toJson(): JSONObject = JSONObject().put("added", added).put("updated", updated).put("removed", removed).put("files", files)

    /** "3 added, 1 updated", or "nothing new". */
    fun words(): String = listOfNotNull(
        added.takeIf { it > 0 }?.let { "$it added" },
        updated.takeIf { it > 0 }?.let { "$it updated" },
        removed.takeIf { it > 0 }?.let { "$it removed" },
    ).joinToString(", ").ifEmpty { "nothing new" }

    companion object {
        fun from(o: JSONObject) = SyncSummary(o.optInt("added"), o.optInt("updated"), o.optInt("removed"), o.optInt("files"))
    }
}

/** A sync both ways: what this device took in, and what the other one did. */
data class SyncOutcome(val peer: SyncPeer, val here: SyncSummary, val there: SyncSummary)

/** Raised for anything that ends a conversation early, worded for the person. */
class SyncRefused(message: String) : IOException(message)

/**
 * The conversation between two devices, one asking and one answering.
 *
 * Pairing, once per pair of devices:
 *  1. each sends its id, its name and the public half of a fresh key pair;
 *  2. both work out the shared secret and show six digits made from it;
 *  3. each sends, sealed under keys from that secret, whether its person
 *     said the digits match: a sealed "yes" that opens is also proof the
 *     other side has the same secret;
 *  4. with two yeses, both keep a pair secret, made from the shared one
 *     and never sent anywhere.
 *
 * Syncing, any time after:
 *  1. each sends its id and a fresh random number; keys for this
 *     connection come from the pair secret and both numbers, so nothing
 *     recorded from an earlier sync can be played back into this one, and
 *     only a device holding the pair secret can send a message that opens;
 *  2. each says which files it already has;
 *  3. the asker sends its backup, without those files; the answerer merges
 *     it, then sends its own backup back, which now holds both;
 *  4. the asker merges that, and both have the same.
 */
object SyncProtocol {

    const val APP = "riffbuddy-sync"
    const val VERSION = 1

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    private fun unb64(text: String): ByteArray = Base64.getDecoder().decode(text)

    private fun hello(me: SyncPeer, purpose: String) = JSONObject()
        .put("app", APP).put("v", VERSION).put("purpose", purpose).put("id", me.id).put("name", me.name)

    private fun peerOf(o: JSONObject): SyncPeer {
        if (o.optString("app") != APP) throw SyncRefused("That isn't Riff Buddy.")
        if (o.optInt("v") > VERSION) throw SyncRefused("The other device has a newer Riff Buddy. Update this one and try again.")
        if (!o.optBoolean("ok", true)) throw SyncRefused(o.optString("reason", "The other device said no."))
        return SyncPeer(o.getString("id"), o.optString("name", "Another device"))
    }

    private fun refuse(link: SyncLink, me: SyncPeer, reason: String) {
        runCatching { link.send(hello(me, "answer").put("ok", false).put("reason", reason)) }
    }

    // ---- Pairing -----------------------------------------------------------

    /**
     * Pairs with the device on the other end of [link]. [decide] shows the
     * digits and returns what the person said. Returns the other device and
     * the pair secret to keep, or throws [SyncRefused].
     */
    suspend fun askToPair(
        link: SyncLink,
        me: SyncPeer,
        decide: suspend (code: String, other: SyncPeer) -> Boolean,
    ): Pair<SyncPeer, ByteArray> = withContext(Dispatchers.Blocking) {
        val keys = SyncCrypto.newKeyPair()
        val mine = SyncCrypto.publicBytes(keys)
        link.send(hello(me, "pair").put("public", b64(mine)))
        val answer = link.receive()
        val other = peerOf(answer)
        val theirs = unb64(answer.getString("public"))
        confirm(link, keys, asker = mine, answerer = theirs, askerId = me.id, answererId = other.id, asking = true, other, decide)
    }

    private suspend fun answerPair(
        link: SyncLink,
        me: SyncPeer,
        hello: JSONObject,
        decide: suspend (code: String, other: SyncPeer) -> Boolean,
    ): Pair<SyncPeer, ByteArray> {
        val other = peerOf(hello)
        val theirs = unb64(hello.getString("public"))
        val keys = SyncCrypto.newKeyPair()
        val mine = SyncCrypto.publicBytes(keys)
        link.send(hello(me, "answer").put("public", b64(mine)))
        return confirm(link, keys, asker = theirs, answerer = mine, askerId = other.id, answererId = me.id, asking = false, other, decide)
    }

    private suspend fun confirm(
        link: SyncLink,
        keys: java.security.KeyPair,
        asker: ByteArray,
        answerer: ByteArray,
        askerId: String,
        answererId: String,
        asking: Boolean,
        other: SyncPeer,
        decide: suspend (code: String, other: SyncPeer) -> Boolean,
    ): Pair<SyncPeer, ByteArray> {
        val secret = SyncCrypto.agree(keys, if (asking) answerer else asker)
        val transcript = SyncCrypto.transcript(asker, answerer, askerId, answererId)
        val toAnswerer = SyncCrypto.derive(secret, transcript, "pair asker to answerer")
        val toAsker = SyncCrypto.derive(secret, transcript, "pair answerer to asker")
        if (asking) link.secure(toAnswerer, toAsker) else link.secure(toAsker, toAnswerer)

        // Both people answer at their own pace, so this side listens while
        // its person decides: a "no" (or a hang-up) from the other device
        // closes the question here too, rather than leaving it on screen.
        val theirYes = coroutineScope {
            val reply = async(Dispatchers.Blocking) { runCatching { link.receive().optBoolean("match") } }
            val decision = async { decide(SyncCrypto.confirmCode(secret, transcript), other) }
            val answeredFirst = select<Boolean> {
                decision.onAwait { true }
                reply.onAwait { false }
            }
            if (!answeredFirst && reply.await().getOrNull() != true) {
                decision.cancel()
                throw refusal(reply.await().exceptionOrNull(), other)
            }
            val yes = decision.await()
            withContext(Dispatchers.Blocking) { link.send(JSONObject().put("match", yes)) }
            if (!yes) {
                // A read waiting on the network can't be canceled, only ended by closing.
                link.close()
                throw SyncRefused("Pairing canceled.")
            }
            reply.await().getOrElse { throw refusal(it, other) }
        }
        if (!theirYes) throw SyncRefused("${other.name} said the numbers didn't match.")
        return other to SyncCrypto.derive(secret, transcript, "pair secret")
    }

    private fun refusal(e: Throwable?, other: SyncPeer): SyncRefused = when (e) {
        // Different secrets: something stood between the two devices.
        is javax.crypto.AEADBadTagException -> SyncRefused("The two devices didn't agree. Try pairing again, on your own Wi-Fi.")
        null -> SyncRefused("${other.name} said the numbers didn't match.")
        else -> SyncRefused("${other.name} stopped pairing.")
    }

    // ---- Syncing -----------------------------------------------------------

    /**
     * Syncs with [expected], a paired device, on the other end of [link].
     * [cacheDir] holds the backup received until it is merged.
     */
    suspend fun askToSync(
        link: SyncLink,
        me: SyncPeer,
        expected: SyncPeer,
        secret: ByteArray,
        data: SyncData,
        cacheDir: File,
    ): SyncOutcome = withContext(Dispatchers.Blocking) {
        val mine = SyncCrypto.randomBytes(16)
        link.send(hello(me, "sync").put("nonce", b64(mine)))
        val answer = link.receive()
        val other = peerOf(answer)
        if (other.id != expected.id) throw SyncRefused("A different device answered at that address. Pair with it first.")
        val theirs = unb64(answer.getString("nonce"))
        secureSync(link, secret, asker = mine, answerer = theirs, asking = true)

        val have = data.storedFiles()
        link.send(JSONObject().put("have", JSONArray(have.toList())))
        val theyHave = try { names(link.receive()) } catch (e: javax.crypto.AEADBadTagException) {
            throw SyncRefused("${other.name} doesn't know this device any more. Pair them again.")
        }
        link.sendFile { out -> data.export(out) { it !in theyHave } }
        val there = SyncSummary.from(link.receive().getJSONObject("merged"))
        val here = receiveAndMerge(link, data, other, cacheDir)
        link.send(JSONObject().put("merged", here.toJson()))
        SyncOutcome(other, here, there)
    }

    private suspend fun answerSync(
        link: SyncLink,
        me: SyncPeer,
        hello: JSONObject,
        secretFor: (String) -> ByteArray?,
        data: SyncData,
        cacheDir: File,
    ): SyncOutcome {
        val other = peerOf(hello)
        val secret = secretFor(other.id) ?: run {
            refuse(link, me, "${me.name} isn't paired with this device. Pair them again.")
            throw SyncRefused("${other.name} asked to sync, but isn't paired with this device.")
        }
        val theirs = unb64(hello.getString("nonce"))
        val mine = SyncCrypto.randomBytes(16)
        link.send(hello(me, "answer").put("nonce", b64(mine)))
        secureSync(link, secret, asker = theirs, answerer = mine, asking = false)

        val theyHave = try { names(link.receive()) } catch (e: javax.crypto.AEADBadTagException) {
            throw SyncRefused("A device calling itself ${other.name} couldn't prove it was paired.")
        }
        link.send(JSONObject().put("have", JSONArray(data.storedFiles().toList())))
        val here = receiveAndMerge(link, data, other, cacheDir)
        link.send(JSONObject().put("merged", here.toJson()))
        link.sendFile { out -> data.export(out) { it !in theyHave } }
        val there = SyncSummary.from(link.receive().getJSONObject("merged"))
        return SyncOutcome(other, here, there)
    }

    private fun secureSync(link: SyncLink, secret: ByteArray, asker: ByteArray, answerer: ByteArray, asking: Boolean) {
        val salt = asker + answerer
        val toAnswerer = SyncCrypto.derive(secret, salt, "sync asker to answerer")
        val toAsker = SyncCrypto.derive(secret, salt, "sync answerer to asker")
        if (asking) link.secure(toAnswerer, toAsker) else link.secure(toAsker, toAnswerer)
    }

    private fun names(o: JSONObject): Set<String> {
        val array = o.getJSONArray("have")
        return (0 until array.length()).map { array.getString(it) }.toSet()
    }

    private suspend fun receiveAndMerge(link: SyncLink, data: SyncData, from: SyncPeer, cacheDir: File): SyncSummary {
        val zip = File(cacheDir.apply { mkdirs() }, "sync-${nowMillis()}.zip")
        try {
            zip.outputStream().use(link::receiveFile)
            return data.merge(zip, from)
        } finally {
            zip.delete()
        }
    }

    // ---- Answering ---------------------------------------------------------

    /** What an answered conversation came to. */
    sealed interface Answered {
        data class Paired(val peer: SyncPeer, val secret: ByteArray) : Answered
        data class Synced(val outcome: SyncOutcome) : Answered
    }

    /**
     * Answers whatever the device on the other end of [link] asks: to pair
     * ([decide] asks the person) or to sync ([secretFor] finds the pair
     * secret, or null for a device not paired).
     */
    suspend fun answer(
        link: SyncLink,
        me: SyncPeer,
        decide: suspend (code: String, other: SyncPeer) -> Boolean,
        secretFor: (String) -> ByteArray?,
        data: SyncData,
        cacheDir: File,
    ): Answered {
        val hello = withContext(Dispatchers.Blocking) { link.receive() }
        return when (hello.optString("purpose")) {
            "pair" -> withContext(Dispatchers.Blocking) {
                answerPair(link, me, hello, decide).let { (peer, secret) -> Answered.Paired(peer, secret) }
            }
            "sync" -> withContext(Dispatchers.Blocking) { Answered.Synced(answerSync(link, me, hello, secretFor, data, cacheDir)) }
            else -> {
                refuse(link, me, "Not something this Riff Buddy knows how to do.")
                throw SyncRefused("Something on the network sent a message this app doesn't know.")
            }
        }
    }
}
