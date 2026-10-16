package com.riffbuddy.sync

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The cryptography sync needs, from what every Java and Android since 8.0
 * already has: no library.
 *
 * Pairing is a key agreement (elliptic-curve Diffie-Hellman): each device
 * makes a key pair, sends the public half in the open, and each works out
 * the same shared secret from its own private half and the other's public
 * one. Someone who hears both public halves cannot. Someone who sits in the
 * middle and swaps in their own halves can, but then the two devices end up
 * with different secrets, so the six digits each shows (made from its
 * secret) differ, and the player sees it before saying yes.
 *
 * Everything after that is sealed with AES-GCM: encrypted, and stamped so
 * that a single changed byte makes it fail to open rather than open wrong.
 */
object SyncCrypto {

    private val random = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also(random::nextBytes)

    fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC")
        .apply { initialize(ECGenParameterSpec("secp256r1"), random) }
        .generateKeyPair()

    /** The public half as sent to the other device. */
    fun publicBytes(pair: KeyPair): ByteArray = pair.public.encoded

    /** The secret both sides of a pairing arrive at, from ours and their public half. */
    fun agree(ours: KeyPair, theirPublic: ByteArray): ByteArray {
        val theirs = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(theirPublic))
        return KeyAgreement.getInstance("ECDH").run {
            init(ours.private)
            doPhase(theirs, true)
            generateSecret()
        }
    }

    fun sha256(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").run { parts.forEach(::update); digest() }

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            parts.forEach(::update)
            doFinal()
        }

    /**
     * Stretches one secret into as many keys as are needed, each tied to
     * what it is for ([label]) and to this exchange ([salt]), so no two uses
     * ever share a key (HKDF, RFC 5869).
     */
    fun derive(secret: ByteArray, salt: ByteArray, label: String, length: Int = 32): ByteArray {
        val prk = hmac(salt, secret)
        val out = java.io.ByteArrayOutputStream()
        var block = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            block = hmac(prk, block, label.toByteArray(), byteArrayOf(counter++.toByte()))
            out.write(block)
        }
        return out.toByteArray().copyOf(length)
    }

    /**
     * The six digits a pairing shows on both screens. Made from the shared
     * secret and both public halves in a fixed order, so the two devices
     * show the same number only if nothing came between them.
     */
    fun confirmCode(secret: ByteArray, transcript: ByteArray): String {
        val bytes = derive(secret, transcript, "confirm code", 4)
        val n = ((bytes[0].toLong() and 0xff) shl 24) or ((bytes[1].toLong() and 0xff) shl 16) or
            ((bytes[2].toLong() and 0xff) shl 8) or (bytes[3].toLong() and 0xff)
        return (n % 1_000_000).toString().padStart(6, '0')
    }

    /** Both public halves, in an order both devices agree on: the one that asked first. */
    fun transcript(askerPublic: ByteArray, answerPublic: ByteArray, askerId: String, answerId: String): ByteArray =
        sha256(askerPublic, answerPublic, askerId.toByteArray(), answerId.toByteArray())

    /**
     * Seals messages in one direction of one connection. Each message's
     * nonce is its number in the conversation, so a message replayed,
     * dropped or put out of order fails to open: the receiver expects the
     * next number, and only the right one opens it.
     */
    class Sealer(key: ByteArray) {
        private val spec = SecretKeySpec(key, "AES")
        private var count = 0L

        fun seal(plain: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, spec, GCMParameterSpec(128, nonce(count++)))
            doFinal(plain)
        }

        /** Throws [javax.crypto.AEADBadTagException] for anything changed, forged or out of turn. */
        fun open(sealed: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, spec, GCMParameterSpec(128, nonce(count++)))
            doFinal(sealed)
        }

        private fun nonce(n: Long) = ByteArray(12).also { b -> for (i in 0 until 8) b[11 - i] = (n ushr (8 * i)).toByte() }
    }
}
