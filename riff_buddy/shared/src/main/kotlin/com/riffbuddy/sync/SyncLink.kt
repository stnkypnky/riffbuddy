package com.riffbuddy.sync

import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * One connection between two devices, as a conversation of messages.
 *
 * A TCP connection is a stream of bytes with no edges, so each message goes
 * with its length in front. The first two messages each way are plain (who
 * I am, my public half or a fresh random number); after [secure] every
 * message is sealed, and one that fails to open ends the conversation.
 *
 * A message is JSON, or a chunk of a file, told apart by its first byte. A
 * file goes as chunks with an empty one at the end, so a backup of any size
 * passes through without ever being in memory whole.
 */
class SyncLink(input: InputStream, output: OutputStream) {
    private val input = DataInputStream(input.buffered())
    private val output = DataOutputStream(output.buffered())
    private var sending: SyncCrypto.Sealer? = null
    private var receiving: SyncCrypto.Sealer? = null

    /** Bytes of file chunks sent and received so far, for a progress line. */
    @Volatile var fileBytes = 0L
        private set

    fun secure(sendKey: ByteArray, receiveKey: ByteArray) {
        sending = SyncCrypto.Sealer(sendKey)
        receiving = SyncCrypto.Sealer(receiveKey)
    }

    /** Ends the conversation; a read waiting on the other side ends with an error. */
    fun close() {
        runCatching { output.close() }
        runCatching { input.close() }
    }

    fun send(message: JSONObject) = write(KIND_JSON, message.toString().toByteArray(Charsets.UTF_8))

    fun receive(): JSONObject {
        val (kind, body) = read()
        if (kind != KIND_JSON) throw IOException("Expected a message, got a file")
        return JSONObject(String(body, Charsets.UTF_8))
    }

    /** Sends whatever [write] writes as one file. */
    suspend fun sendFile(write: suspend (OutputStream) -> Unit) {
        val chunks = object : OutputStream() {
            private val buffer = ByteArray(CHUNK)
            private var used = 0
            override fun write(b: Int) {
                buffer[used++] = b.toByte()
                if (used == CHUNK) flush()
            }
            override fun write(b: ByteArray, off: Int, len: Int) {
                var at = off
                var left = len
                while (left > 0) {
                    val n = minOf(left, CHUNK - used)
                    System.arraycopy(b, at, buffer, used, n)
                    used += n; at += n; left -= n
                    if (used == CHUNK) flush()
                }
            }
            override fun flush() {
                if (used == 0) return
                this@SyncLink.write(KIND_CHUNK, buffer.copyOf(used))
                fileBytes += used
                used = 0
            }
            // Closing (as a zip writer does when it finishes) sends what is left; the end comes after.
            override fun close() = flush()
        }
        write(chunks)
        chunks.flush()
        write(KIND_CHUNK, ByteArray(0))
    }

    /** Receives one file into [out]. */
    fun receiveFile(out: OutputStream) {
        while (true) {
            val (kind, body) = read()
            if (kind != KIND_CHUNK) throw IOException("Expected a file, got a message")
            if (body.isEmpty()) return
            out.write(body)
            fileBytes += body.size
        }
    }

    private fun write(kind: Byte, body: ByteArray) {
        val plain = byteArrayOf(kind) + body
        val frame = sending?.seal(plain) ?: plain
        output.writeInt(frame.size)
        output.write(frame)
        output.flush()
    }

    private fun read(): Pair<Byte, ByteArray> {
        val size = try { input.readInt() } catch (e: EOFException) { throw IOException("The other device hung up", e) }
        // A length no message of ours could have: a stranger, or a damaged stream.
        if (size !in 1..MAX_FRAME) throw IOException("Not a Riff Buddy device")
        val frame = ByteArray(size).also(input::readFully)
        val plain = receiving?.open(frame) ?: frame
        return plain[0] to plain.copyOfRange(1, plain.size)
    }

    private companion object {
        const val KIND_JSON: Byte = 1
        const val KIND_CHUNK: Byte = 2
        const val CHUNK = 64 * 1024
        // Room for the list of files a large library holds; anything longer is not ours.
        const val MAX_FRAME = 4 * 1024 * 1024
    }
}
