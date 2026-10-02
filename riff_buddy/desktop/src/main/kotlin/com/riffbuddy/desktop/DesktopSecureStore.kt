package com.riffbuddy.desktop

import com.github.javakeyring.Keyring
import com.riffbuddy.ai.ProviderId
import com.riffbuddy.data.SecureStore
import com.riffbuddy.platform.AppLog

/**
 * API keys in the operating system's own keychain — the Secret Service on
 * Linux (GNOME Keyring, KWallet), Credential Manager on Windows, the Keychain
 * on macOS — which encrypts them with the user's login and keeps them out of
 * every file the app writes.
 *
 * Where there is no keychain at all (a bare Linux session with no Secret
 * Service running), keys are held in memory for this run only and the log
 * says so: a key that has to be typed again next time is better than one
 * written to disk in the clear.
 */
class DesktopSecureStore : SecureStore {

    private val keyring: Keyring? = runCatching { Keyring.create() }
        .onFailure { AppLog.w(TAG, "no system keychain; API keys will last only until the app closes", it) }
        .getOrNull()

    private val memory = mutableMapOf<ProviderId, String>()

    override fun put(provider: ProviderId, apiKey: String) {
        val key = apiKey.trim()
        val ring = keyring
        if (ring == null) memory[provider] = key
        else ring.setPassword(SERVICE, provider.name, key)
    }

    override fun get(provider: ProviderId): String? {
        val ring = keyring ?: return memory[provider]
        // The keychain throws for an entry that does not exist; that is "no key".
        return runCatching { ring.getPassword(SERVICE, provider.name) }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    override fun remove(provider: ProviderId) {
        memory.remove(provider)
        keyring?.let { ring -> runCatching { ring.deletePassword(SERVICE, provider.name) } }
    }

    override fun clear() = ProviderId.entries.forEach(::remove)

    private companion object {
        const val SERVICE = "Riff Buddy"
        const val TAG = "RiffKeys"
    }
}
