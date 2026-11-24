package com.riffbuddy.platform

/*
 * Bytes across to JavaScript and back. A Kotlin ByteArray lives inside the
 * WebAssembly heap, where page scripts can't see it, so it is copied into a
 * Uint8Array, and back out the same way.
 */

private fun newUint8Array(size: Int): JsAny = js("new Uint8Array(size)")
private fun setAt(array: JsAny, index: Int, value: Int): Unit = js("array[index] = value")
private fun getAt(array: JsAny, index: Int): Int = js("array[index]")
private fun lengthOf(array: JsAny): Int = js("array.length")

fun ByteArray.toUint8Array(): JsAny {
    val out = newUint8Array(size)
    for (i in indices) setAt(out, i, this[i].toInt())
    return out
}

/** A JavaScript Uint8Array (or anything indexed like one) as Kotlin bytes. */
fun JsAny.toByteArray(): ByteArray = ByteArray(lengthOf(this)) { getAt(this, it).toByte() }
