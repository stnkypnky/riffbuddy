package com.riffbuddy.web

import com.riffbuddy.json.JSONArray
import com.riffbuddy.platform.AppLog
import com.riffbuddy.platform.toByteArray
import com.riffbuddy.platform.toUint8Array
import kotlinx.coroutines.await
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.js.Promise

/**
 * The app's files in a browser. The app reads and writes them synchronously,
 * as it does on a phone, so they are held in memory ([fileSystem]); everything
 * under [FILES] is mirrored to the browser's private storage, loaded on start
 * by [restore] and written back by [flush], which the app runs every few
 * seconds and when the page is hidden. The cache and the bundled assets live
 * in memory only.
 */
class WebFiles private constructor(val fileSystem: FakeFileSystem, saved: Map<Path, Long>) {

    /** What was last written to storage: each file's size and time, folded into one number. */
    private val saved = saved.toMutableMap()

    /**
     * Writes back every file whose size or time differs from what was last
     * saved, and removes what is gone. Comparing, rather than noting each
     * write, catches a take still being recorded into a file opened once.
     */
    suspend fun flush() {
        val now = mutableMapOf<Path, Long>()
        if (fileSystem.exists(FILES)) {
            fileSystem.listRecursively(FILES).forEach { p ->
                fileSystem.metadataOrNull(p)?.takeIf { it.isRegularFile }?.let { now[p] = stamp(it.size, it.lastModifiedAtMillis) }
            }
        }
        for (p in saved.keys - now.keys) {
            runCatching { jsRemove(p.toString()).await<JsAny?>() }
            saved.remove(p)
        }
        for ((p, stamp) in now) {
            if (saved[p] == stamp) continue
            runCatching { jsWrite(p.toString(), fileSystem.read(p) { readByteArray() }.toUint8Array()).await<JsAny?>() }
                .onSuccess { saved[p] = stamp }
                .onFailure { AppLog.w("RiffFiles", "could not save ${p.name}", it) }
        }
    }

    /** Bundled files fetched once into memory, so opening one is synchronous: sample tabs and recordings. */
    suspend fun preload(urls: List<String>) {
        for (url in urls) {
            val bytes = runCatching { jsFetch(url).await<JsAny>().toByteArray() }.getOrElse {
                AppLog.w("RiffFiles", "could not load $url", it)
                continue
            }
            val target = ASSETS / url
            target.parent?.let(fileSystem::createDirectories)
            fileSystem.write(target) { write(bytes) }
        }
    }

    companion object {
        val FILES = "/files".toPath()
        val CACHE = "/cache".toPath()
        val ASSETS = "/assets".toPath()
        private fun stamp(size: Long?, modified: Long?) = (size ?: 0) * 31 + (modified ?: 0)

        /** Loads every stored file into memory. */
        suspend fun restore(): WebFiles {
            val memory = FakeFileSystem().apply { emulateUnix(); allowSymlinks = false }
            memory.createDirectories(FILES)
            memory.createDirectories(CACHE)
            val saved = mutableMapOf<Path, Long>()
            val list = JSONArray(jsList().await<JsString>().toString())
            for (i in 0 until list.length()) {
                val path = list.getJSONObject(i).getString("path").toPath()
                runCatching {
                    val bytes = jsRead(path.toString()).await<JsAny>().toByteArray()
                    path.parent?.let(memory::createDirectories)
                    memory.write(path) { write(bytes) }
                    val meta = memory.metadata(path)
                    saved[path] = stamp(meta.size, meta.lastModifiedAtMillis)
                }.onFailure { AppLog.w("RiffFiles", "could not load ${path.name}", it) }
            }
            return WebFiles(memory, saved)
        }
    }
}

private fun jsList(): Promise<JsString> = js("rbFiles.list()")
private fun jsRead(path: String): Promise<JsAny> = js("rbFiles.read(path)")
private fun jsWrite(path: String, bytes: JsAny): Promise<JsAny?> = js("rbFiles.write(path, bytes)")
private fun jsRemove(path: String): Promise<JsAny?> = js("rbFiles.remove(path)")
private fun jsFetch(url: String): Promise<JsAny> = js("rbFiles.fetchBytes(url)")

/** The file system the app uses, once restored. */
lateinit var webFiles: WebFiles
