package com.riffbuddy.platform

import okio.Buffer
import okio.BufferedSink
import okio.Closeable
import okio.Path
import okio.Source

/*
 * Zip files without a zip library, for the browser, which has none it can
 * call synchronously. Entries are written uncompressed (stored), which every
 * zip reader opens, Java's included; entries are read stored or deflated,
 * since a backup made on a phone is deflated. Common code, so the JVM tests
 * can hold it to Java's own zip classes.
 */

/** Writes a zip to [out]. Each entry is held until it is complete, to put its size and checksum ahead of it. */
class PortableZipWriter(private val out: BufferedSink) : Closeable {
    private class Entry(val name: ByteArray, val crc: Int, val size: Int, val offset: Long)
    private val entries = mutableListOf<Entry>()
    private var offset = 0L

    fun entry(name: String, write: (BufferedSink) -> Unit) {
        val data = Buffer().also(write).readByteArray()
        val nameBytes = name.encodeToByteArray()
        val crc = crc32(data)
        entries += Entry(nameBytes, crc, data.size, offset)
        out.writeIntLe(0x04034b50).writeShortLe(20).writeShortLe(0x0800).writeShortLe(0)
            .writeShortLe(0).writeShortLe(0x21)
            .writeIntLe(crc).writeIntLe(data.size).writeIntLe(data.size)
            .writeShortLe(nameBytes.size).writeShortLe(0).write(nameBytes).write(data)
        offset += 30 + nameBytes.size + data.size
    }

    override fun close() {
        val start = offset
        var size = 0L
        for (e in entries) {
            out.writeIntLe(0x02014b50).writeShortLe(20).writeShortLe(20).writeShortLe(0x0800).writeShortLe(0)
                .writeShortLe(0).writeShortLe(0x21)
                .writeIntLe(e.crc).writeIntLe(e.size).writeIntLe(e.size)
                .writeShortLe(e.name.size).writeShortLe(0).writeShortLe(0).writeShortLe(0).writeShortLe(0)
                .writeIntLe(0).writeIntLe(e.offset.toInt()).write(e.name)
            size += 46 + e.name.size
        }
        out.writeIntLe(0x06054b50).writeShortLe(0).writeShortLe(0)
            .writeShortLe(entries.size).writeShortLe(entries.size)
            .writeIntLe(size.toInt()).writeIntLe(start.toInt()).writeShortLe(0)
        out.close()
    }
}

/** Reads a zip file the app holds, from its central directory. */
class PortableZipReader(path: Path) : Closeable {
    private class Entry(val method: Int, val compressed: Int, val size: Int, val local: Int)
    private val bytes = path.readBytes()
    private val entries = LinkedHashMap<String, Entry>()

    init {
        var end = bytes.size - 22
        while (end >= 0 && int(end) != 0x06054b50) end--
        require(end >= 0) { "Not a zip file" }
        val count = short(end + 10)
        var at = int(end + 16)
        repeat(count) {
            require(int(at) == 0x02014b50) { "A damaged zip file" }
            val nameLength = short(at + 28)
            val extra = short(at + 30)
            val comment = short(at + 32)
            val name = bytes.decodeToString(at + 46, at + 46 + nameLength)
            entries[name] = Entry(short(at + 10), int(at + 20), int(at + 24), int(at + 42))
            at += 46 + nameLength + extra + comment
        }
    }

    val names: List<String> get() = entries.keys.toList()

    fun open(name: String): Source? {
        val e = entries[name] ?: return null
        val start = e.local + 30 + short(e.local + 26) + short(e.local + 28)
        val raw = bytes.copyOfRange(start, start + e.compressed)
        val data = when (e.method) {
            0 -> raw
            8 -> Inflater(raw, e.size).inflate()
            else -> throw IllegalStateException("A zip entry packed in a way this app can't read")
        }
        return Buffer().write(data)
    }

    override fun close() {}

    private fun short(at: Int) = (bytes[at].toInt() and 0xff) or ((bytes[at + 1].toInt() and 0xff) shl 8)
    private fun int(at: Int) = short(at) or (short(at + 2) shl 16)
}

private val CRC_TABLE = IntArray(256) { n ->
    var c = n
    repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
    c
}

private fun crc32(data: ByteArray): Int {
    var c = -1
    for (b in data) c = CRC_TABLE[(c xor b.toInt()) and 0xff] xor (c ushr 8)
    return c.inv()
}

/** Raw deflate (RFC 1951), the way a zip entry is compressed. */
private class Inflater(private val input: ByteArray, expected: Int) {
    private var pos = 0
    private var bitBuf = 0
    private var bitCount = 0
    private var out = ByteArray(maxOf(expected, 1024))
    private var outLen = 0

    fun inflate(): ByteArray {
        do {
            val last = bits(1)
            when (bits(2)) {
                0 -> stored()
                1 -> block(FIXED_LIT, FIXED_DIST)
                2 -> dynamic()
                else -> error("bad deflate block")
            }
        } while (last == 0)
        return out.copyOf(outLen)
    }

    private fun bits(n: Int): Int {
        while (bitCount < n) {
            check(pos < input.size) { "deflate data ends early" }
            bitBuf = bitBuf or ((input[pos++].toInt() and 0xff) shl bitCount)
            bitCount += 8
        }
        val v = bitBuf and ((1 shl n) - 1)
        bitBuf = bitBuf ushr n
        bitCount -= n
        return v
    }

    private fun put(b: Int) {
        if (outLen == out.size) out = out.copyOf(out.size * 2)
        out[outLen++] = b.toByte()
    }

    private fun stored() {
        bitBuf = 0; bitCount = 0
        val len = (input[pos].toInt() and 0xff) or ((input[pos + 1].toInt() and 0xff) shl 8)
        pos += 4
        repeat(len) { put(input[pos++].toInt()) }
    }

    /** A canonical Huffman code: counts per length, and symbols in code order. */
    class Huffman(lengths: IntArray) {
        val counts = IntArray(16)
        val symbols: IntArray
        init {
            for (l in lengths) counts[l]++
            counts[0] = 0
            val offs = IntArray(16)
            for (i in 1 until 16) offs[i] = offs[i - 1] + counts[i - 1]
            symbols = IntArray(lengths.size)
            for (s in lengths.indices) if (lengths[s] != 0) symbols[offs[lengths[s]]++] = s
        }
    }

    private fun decode(h: Huffman): Int {
        var code = 0; var first = 0; var index = 0
        for (len in 1 until 16) {
            code = code or bits(1)
            val count = h.counts[len]
            if (code - count < first) return h.symbols[index + (code - first)]
            index += count; first += count
            first = first shl 1; code = code shl 1
        }
        error("bad deflate code")
    }

    private fun block(lit: Huffman, dist: Huffman) {
        while (true) {
            val sym = decode(lit)
            if (sym < 256) { put(sym); continue }
            if (sym == 256) return
            val i = sym - 257
            val length = LEN_BASE[i] + bits(LEN_EXTRA[i])
            val d = decode(dist)
            val distance = DIST_BASE[d] + bits(DIST_EXTRA[d])
            repeat(length) { put(out[outLen - distance].toInt()) }
        }
    }

    private fun dynamic() {
        val nlen = bits(5) + 257
        val ndist = bits(5) + 1
        val ncode = bits(4) + 4
        val codeLengths = IntArray(19)
        for (i in 0 until ncode) codeLengths[ORDER[i]] = bits(3)
        val codes = Huffman(codeLengths)
        val lengths = IntArray(nlen + ndist)
        var i = 0
        while (i < nlen + ndist) {
            val sym = decode(codes)
            when {
                sym < 16 -> lengths[i++] = sym
                sym == 16 -> { val prev = lengths[i - 1]; repeat(3 + bits(2)) { lengths[i++] = prev } }
                sym == 17 -> repeat(3 + bits(3)) { lengths[i++] = 0 }
                else -> repeat(11 + bits(7)) { lengths[i++] = 0 }
            }
        }
        block(Huffman(lengths.copyOfRange(0, nlen)), Huffman(lengths.copyOfRange(nlen, nlen + ndist)))
    }

    companion object {
        val ORDER = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)
        val LEN_BASE = intArrayOf(3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258)
        val LEN_EXTRA = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0)
        val DIST_BASE = intArrayOf(1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577)
        val DIST_EXTRA = intArrayOf(0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13)
        val FIXED_LIT = Huffman(IntArray(288) { when { it < 144 -> 8; it < 256 -> 9; it < 280 -> 7; else -> 8 } })
        val FIXED_DIST = Huffman(IntArray(30) { 5 })
    }
}
