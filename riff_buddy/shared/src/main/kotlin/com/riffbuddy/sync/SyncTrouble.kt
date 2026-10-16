package com.riffbuddy.sync

import java.net.NoRouteToHostException
import java.net.SocketTimeoutException

/**
 * Why another device couldn't be reached, turned into what to do about it.
 *
 * A failed connection says more than "can't reach" if its error is read,
 * and each kind has its own fix:
 *  - refused: the device is there, but nothing is listening, so Riff Buddy
 *    isn't open on it (or is in the background);
 *  - no route: this device can't find the other's hardware address on the
 *    network at all. Two devices can each reach everything else and still
 *    not find each other (a phone's changing private Wi-Fi address can do
 *    it); one reaching out from the other side usually fixes it;
 *  - no answer: the connection was dropped without a word, which is what a
 *    computer's firewall does;
 *  - dropped: it connected, then stopped partway.
 */
object SyncTrouble {

    enum class Kind { REFUSED, NOT_FOUND, NO_ANSWER, DROPPED, OTHER }

    /** [connected] is whether the connection was made before it failed. */
    fun kind(e: Throwable, connected: Boolean): Kind {
        if (connected) return Kind.DROPPED
        // Android puts the system's own error name in the message ("EHOSTUNREACH");
        // a desktop gives a class or plain words. Read the whole chain for either.
        val text = generateSequence(e) { it.cause }.joinToString(" ") { "${it.javaClass.simpleName} ${it.message}" }
        return when {
            e is NoRouteToHostException || listOf("EHOSTUNREACH", "ENETUNREACH", "No route to host", "Network is unreachable")
                .any { it in text } -> Kind.NOT_FOUND
            // A reset while connecting is the same answer said differently:
            // something is there, and it isn't taking connections.
            listOf("ECONNREFUSED", "Connection refused", "ECONNRESET", "Connection reset").any { it in text } -> Kind.REFUSED
            e is SocketTimeoutException || "ETIMEDOUT" in text || "timed out" in text -> Kind.NO_ANSWER
            else -> Kind.OTHER
        }
    }

    /** What to tell the person, about the device called [who] (its name, or the address typed). */
    fun advice(e: Throwable, connected: Boolean, who: String): String = when (kind(e, connected)) {
        Kind.REFUSED ->
            "$who is on the network, but Riff Buddy isn't open on it. Open Riff Buddy there, on screen " +
                "rather than in the background, and try again."
        Kind.NOT_FOUND ->
            "This device can't find $who on the network. Check both are on the same Wi-Fi. If they are, " +
                "try it from $who instead: that often lets the two find each other, and then it works from " +
                "here too."
        Kind.NO_ANSWER ->
            "$who didn't answer. Check Riff Buddy is open on it, on the same Wi-Fi. If $who is a computer, " +
                "its firewall may be blocking Riff Buddy: allow it on private networks (on Windows, Allow an " +
                "app through firewall)."
        Kind.DROPPED ->
            "The connection to $who stopped partway. Nothing was left half-done; try again."
        Kind.OTHER ->
            "Couldn't reach $who (${e.message}). Check Riff Buddy is open on it, on the same Wi-Fi."
    }
}
