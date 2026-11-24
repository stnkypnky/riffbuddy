package com.riffbuddy.platform

import okio.Path
import okio.Source
import okio.buffer
import okio.use

/*
 * The app's files, as okio paths on [appFileSystem]: the disk on a phone or
 * computer, the browser's storage on the web. These read the way the code
 * that used java.io.File did.
 */

fun Path.exists(): Boolean = appFileSystem.exists(this)

val Path.isFile: Boolean get() = appFileSystem.metadataOrNull(this)?.isRegularFile == true

/** Size in bytes, 0 if there is no such file. */
fun Path.size(): Long = appFileSystem.metadataOrNull(this)?.size ?: 0L

/** Deletes the file if it is there; true if it was. */
fun Path.delete(): Boolean {
    if (!exists()) return false
    appFileSystem.delete(this)
    return true
}

fun Path.mkdirs(): Path = also { appFileSystem.createDirectories(it) }

/** The files and folders directly inside this folder; empty if it isn't one. */
fun Path.list(): List<Path> = appFileSystem.listOrNull(this) ?: emptyList()

fun Path.readBytes(): ByteArray = appFileSystem.read(this) { readByteArray() }

fun Path.writeBytes(bytes: ByteArray) {
    appFileSystem.write(this) { write(bytes) }
}

/** Copies this file to [target], replacing it. */
fun Path.copyTo(target: Path) {
    appFileSystem.source(this).use { from -> appFileSystem.sink(target).buffer().use { it.writeAll(from) } }
}

/** Moves this file to [target], replacing it; false if it could not. */
fun Path.renameTo(target: Path): Boolean = runCatching { appFileSystem.atomicMove(this, target) }.isSuccess

/** All of a stream's bytes, closing it. */
fun Source.readBytes(): ByteArray = buffer().use { it.readByteArray() }
