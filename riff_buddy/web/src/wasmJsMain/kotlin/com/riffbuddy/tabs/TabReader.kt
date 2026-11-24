package com.riffbuddy.tabs

import com.riffbuddy.json.JSONObject
import com.riffbuddy.platform.toUint8Array

/** A tab file read by alphaTab's JavaScript build, the same version the other editions run. */
internal fun readTab(bytes: ByteArray, fileName: String): TabSummary {
    val o = JSONObject(summaryJson(bytes.toUint8Array(), fileName.endsWith(".alphatex", ignoreCase = true)))
    val error = o.optString("error")
    if (error.isNotEmpty()) {
        if (o.optBoolean("unplayable")) throw TabParser.unplayable(fileName, IllegalStateException(error))
        throw IllegalStateException(error)
    }
    val tracks = o.optJSONArray("tracks")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
    return TabParser.summary(o.optString("title"), o.optString("artist"), tracks, o.optInt("bars"), o.optInt("notes"), o.optInt("tempo"))
}

private fun summaryJson(bytes: JsAny, isTex: Boolean): String = js("rbTabFiles.summary(bytes, isTex)")
