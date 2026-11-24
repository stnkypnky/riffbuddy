package com.riffbuddy.data

import com.riffbuddy.ai.ProviderId

/**
 * API keys, kept in the platform's own encrypted store and nowhere else.
 *
 * Not in the database, ever: the backup export is built from the database, so
 * a key kept there would leave in every backup no matter how carefully the
 * exporter was written. `SchemaTest` fails the build if a key-shaped column
 * appears. Each platform encrypts with a key the operating system guards —
 * Android's hardware Keystore, the desktop's keychain.
 */
interface SecureStore {
    fun put(provider: ProviderId, apiKey: String)
    fun get(provider: ProviderId): String?
    fun remove(provider: ProviderId)
    fun clear()

    /**
     * Other secrets, by name: the ones a paired device shares with this one
     * for syncing. Kept the same way as API keys, and like them never in
     * the database, so never in a backup or a sync.
     */
    fun putSecret(name: String, value: String)
    fun getSecret(name: String): String?
    fun removeSecret(name: String)

    fun has(provider: ProviderId): Boolean = get(provider) != null

    fun providersWithKeys(): Set<ProviderId> = ProviderId.entries.filter { has(it) }.toSet()

    /** Last four characters, so Settings can show which key is stored without revealing it. */
    fun hint(provider: ProviderId): String? = get(provider)?.takeLast(4)?.let { "…$it" }
}
