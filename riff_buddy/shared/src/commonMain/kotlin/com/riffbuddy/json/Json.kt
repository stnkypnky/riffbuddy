package com.riffbuddy.json

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray as KxArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject as KxObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * JSON objects with the API of org.json, the library Android has built in,
 * written for every edition: the browser has no org.json. The app reads and
 * writes JSON in the org.json style, a mutable object with typed getters, and
 * keeping that style kept every caller and every stored format as it was.
 * Parsing and string quoting are kotlinx.serialization's.
 *
 * Where Android's and the desktop's org.json differ, this follows the more
 * forgiving reading: an optString of an explicit null gives the fallback,
 * not the word "null", and numbers written as text read as numbers.
 */

class JSONException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class JSONObject() {

    private val map = LinkedHashMap<String, Any>()

    /** Parses [text], which must be a JSON object. */
    constructor(text: String) : this() {
        val element = parse(text) as? KxObject ?: throw JSONException("Not a JSON object")
        element.forEach { (k, v) -> map[k] = fromElement(v) }
    }

    /** Sets [key]; a null [value] removes it, as org.json does. */
    fun put(key: String, value: Any?): JSONObject {
        if (value == null) map.remove(key) else map[key] = checked(value)
        return this
    }

    fun opt(key: String): Any? = map[key]
    fun has(key: String): Boolean = key in map
    fun isNull(key: String): Boolean = map[key].let { it == null || it === NULL }
    fun length(): Int = map.size
    fun keys(): Iterator<String> = map.keys.toList().iterator()
    fun remove(key: String): Any? = map.remove(key)

    fun get(key: String): Any = map[key] ?: throw JSONException("No value for $key")
    fun getString(key: String): String = get(key).let { if (it is String) it else it.toString() }
    fun getInt(key: String): Int = toNumber(get(key))?.toInt() ?: throw JSONException("Not a number: $key")
    fun getLong(key: String): Long = toNumber(get(key))?.let(::toLong) ?: throw JSONException("Not a number: $key")
    fun getDouble(key: String): Double = toNumber(get(key))?.toDouble() ?: throw JSONException("Not a number: $key")
    fun getBoolean(key: String): Boolean = toBoolean(get(key)) ?: throw JSONException("Not a boolean: $key")
    fun getJSONObject(key: String): JSONObject = get(key) as? JSONObject ?: throw JSONException("Not an object: $key")
    fun getJSONArray(key: String): JSONArray = get(key) as? JSONArray ?: throw JSONException("Not an array: $key")

    fun optString(key: String, fallback: String = ""): String = stringOf(map[key]) ?: fallback
    fun optInt(key: String, fallback: Int = 0): Int = toNumber(map[key])?.toInt() ?: fallback
    fun optLong(key: String, fallback: Long = 0L): Long = toNumber(map[key])?.let(::toLong) ?: fallback
    fun optDouble(key: String, fallback: Double = Double.NaN): Double = toNumber(map[key])?.toDouble() ?: fallback
    fun optBoolean(key: String, fallback: Boolean = false): Boolean = toBoolean(map[key]) ?: fallback
    fun optJSONObject(key: String): JSONObject? = map[key] as? JSONObject
    fun optJSONArray(key: String): JSONArray? = map[key] as? JSONArray

    override fun toString(): String = buildString { write(this, this@JSONObject) }

    companion object {
        /** An explicit null, as distinct from a key that is not there. */
        val NULL: Any = Null
    }

    internal fun entries(): Set<Map.Entry<String, Any>> = map.entries
}

class JSONArray() {

    private val list = ArrayList<Any>()

    /** Parses [text], which must be a JSON array. */
    constructor(text: String) : this() {
        val element = parse(text) as? KxArray ?: throw JSONException("Not a JSON array")
        element.forEach { list += fromElement(it) }
    }

    constructor(values: Collection<*>) : this() {
        values.forEach { put(it) }
    }

    constructor(values: Array<*>) : this(values.toList())

    /** Appends [value]; null is kept, as an explicit null. */
    fun put(value: Any?): JSONArray {
        list += if (value == null) NULL_VALUE else checked(value)
        return this
    }

    fun length(): Int = list.size
    fun opt(index: Int): Any? = list.getOrNull(index)
    fun isNull(index: Int): Boolean = opt(index).let { it == null || it === NULL_VALUE }

    fun get(index: Int): Any = opt(index) ?: throw JSONException("Index $index out of range")
    fun getString(index: Int): String = get(index).let { if (it is String) it else it.toString() }
    fun getInt(index: Int): Int = toNumber(get(index))?.toInt() ?: throw JSONException("Not a number at $index")
    fun getJSONObject(index: Int): JSONObject = get(index) as? JSONObject ?: throw JSONException("Not an object at $index")
    fun getJSONArray(index: Int): JSONArray = get(index) as? JSONArray ?: throw JSONException("Not an array at $index")

    fun optString(index: Int, fallback: String = ""): String = stringOf(opt(index)) ?: fallback
    fun optInt(index: Int, fallback: Int = 0): Int = toNumber(opt(index))?.toInt() ?: fallback
    fun optDouble(index: Int, fallback: Double = Double.NaN): Double = toNumber(opt(index))?.toDouble() ?: fallback
    fun optJSONObject(index: Int): JSONObject? = opt(index) as? JSONObject
    fun optJSONArray(index: Int): JSONArray? = opt(index) as? JSONArray

    override fun toString(): String = buildString { write(this, this@JSONArray) }

    internal fun items(): List<Any> = list
}

private object Null {
    override fun toString() = "null"
}

private val NULL_VALUE: Any = Null

private fun parse(text: String): JsonElement =
    try {
        Json.parseToJsonElement(text)
    } catch (e: Exception) {
        throw JSONException(e.message ?: "Bad JSON", e)
    }

private fun fromElement(e: JsonElement): Any = when (e) {
    is JsonNull -> Null
    is KxObject -> JSONObject().also { o -> e.forEach { (k, v) -> o.put(k, fromElement(v)) } }
    is KxArray -> JSONArray().also { a -> e.forEach { a.put(fromElement(it)) } }
    is JsonPrimitive -> when {
        e.isString -> e.content
        e.content == "true" -> true
        e.content == "false" -> false
        else -> numberOf(e.content)
    }
}

/** Whole numbers come back as Int when they fit, else Long; the rest as Double. */
private fun numberOf(text: String): Number {
    if (text.none { it == '.' || it == 'e' || it == 'E' }) {
        text.toLongOrNull()?.let { return if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it }
    }
    return text.toDoubleOrNull() ?: throw JSONException("Bad number $text")
}

private fun checked(value: Any): Any = when (value) {
    is String, is Boolean, is Int, is Long, is Short, is Byte, is JSONObject, is JSONArray, Null -> value
    is Double -> if (value.isNaN() || value.isInfinite()) throw JSONException("Not a finite number") else value
    is Float -> if (value.isNaN() || value.isInfinite()) throw JSONException("Not a finite number") else value
    else -> throw JSONException("Can't put a ${value::class.simpleName} in JSON")
}

private fun stringOf(v: Any?): String? = when (v) {
    null, Null -> null
    is String -> v
    is Number -> numberText(v)
    else -> v.toString()
}

private fun toNumber(v: Any?): Number? = when (v) {
    is Number -> v
    is String -> v.trim().toDoubleOrNull()?.let { d -> v.trim().toLongOrNull() ?: d }
    else -> null
}

private fun toLong(n: Number): Long = if (n is Double || n is Float) n.toDouble().toLong() else n.toLong()

private fun toBoolean(v: Any?): Boolean? = when (v) {
    is Boolean -> v
    is String -> when (v.lowercase()) { "true" -> true; "false" -> false; else -> null }
    else -> null
}

/** A whole double is written without ".0", as org.json writes it. */
private fun numberText(n: Number): String {
    if (n !is Double && n !is Float) return n.toString()
    val s = n.toString()
    return if ('.' in s && 'E' !in s && 'e' !in s) s.trimEnd('0').trimEnd('.') else s
}

private fun write(out: StringBuilder, v: Any) {
    when (v) {
        is JSONObject -> {
            out.append('{')
            v.entries().forEachIndexed { i, (k, value) ->
                if (i > 0) out.append(',')
                out.append(JsonPrimitive(k).toString()).append(':')
                write(out, value)
            }
            out.append('}')
        }
        is JSONArray -> {
            out.append('[')
            v.items().forEachIndexed { i, value ->
                if (i > 0) out.append(',')
                write(out, value)
            }
            out.append(']')
        }
        is String -> out.append(JsonPrimitive(v).toString())
        is Number -> out.append(numberText(v))
        else -> out.append(v.toString())
    }
}
