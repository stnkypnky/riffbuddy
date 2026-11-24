package com.riffbuddy.web

import com.riffbuddy.ai.ProviderId
import com.riffbuddy.data.SecureStore
import com.riffbuddy.json.JSONObject
import kotlinx.coroutines.await
import kotlin.js.Promise

/**
 * API keys in the browser, encrypted by keys-bridge.js. Decrypting is
 * asynchronous in a browser and this interface is not, so [load] reads them
 * all into memory once, before the app starts; a change is kept here at once
 * and written, encrypted, a moment later.
 */
class WebSecureStore private constructor(private val values: MutableMap<String, String>) : SecureStore {

    override fun put(provider: ProviderId, apiKey: String) = putSecret(nameOf(provider), apiKey)
    override fun get(provider: ProviderId): String? = getSecret(nameOf(provider))
    override fun remove(provider: ProviderId) = removeSecret(nameOf(provider))
    override fun clear() = values.keys.toList().forEach(::removeSecret)

    override fun putSecret(name: String, value: String) {
        values[name] = value
        jsPut(name, value)
    }

    override fun getSecret(name: String): String? = values[name]

    override fun removeSecret(name: String) {
        values.remove(name)
        jsRemove(name)
    }

    private fun nameOf(provider: ProviderId) = "provider." + provider.name

    companion object {
        suspend fun load(): WebSecureStore {
            val o = JSONObject(jsLoad().await<JsString>().toString())
            val values = mutableMapOf<String, String>()
            o.keys().forEach { values[it] = o.getString(it) }
            return WebSecureStore(values)
        }
    }
}

private fun jsLoad(): Promise<JsString> = js("rbKeys.load()")
private fun jsPut(name: String, value: String): Unit = js("{ rbKeys.put(name, value); }")
private fun jsRemove(name: String): Unit = js("rbKeys.remove(name)")
