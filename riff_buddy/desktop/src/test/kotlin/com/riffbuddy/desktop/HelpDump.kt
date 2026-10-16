package com.riffbuddy.desktop

import com.riffbuddy.domain.Device
import com.riffbuddy.domain.HelpCatalog
import com.riffbuddy.domain.HelpTopic
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Writes the in-app help to a JSON file, as a phone or a computer shows it,
 * for the illustrated guide: the guide prints exactly what the "?" says,
 * and answers that differ by device come out the way that device words them.
 *
 * Usage: helpDump <out.json> [phone|computer]
 */
fun main(args: Array<String>) {
    // Set before the catalog is first read: its answers are made once, for this kind of device.
    Device.onComputer = args.getOrNull(1) == "computer"
    val out = JSONObject()
    HelpTopic.entries.forEach { topic ->
        out.put(topic.name, JSONArray(HelpCatalog.forTopic(topic).map { JSONArray(listOf(it.question, it.answer)) }))
    }
    File(args[0]).apply { parentFile?.mkdirs() }.writeText(out.toString(1))
    println("Wrote ${args[0]}")
}
