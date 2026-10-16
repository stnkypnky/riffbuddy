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

    private val memory = mutableMapOf<String, String>()

    override fun put(provider: ProviderId, apiKey: String) = write(provider.name, apiKey.trim())

    override fun get(provider: ProviderId): String? = read(provider.name)

    override fun remove(provider: ProviderId) = delete(provider.name)

    // Other secrets (a paired device's, for syncing), under a prefix no provider name has.
    override fun putSecret(name: String, value: String) = write(SECRET_PREFIX + name, value)
    override fun getSecret(name: String): String? = read(SECRET_PREFIX + name)
    override fun removeSecret(name: String) = delete(SECRET_PREFIX + name)

    private fun write(account: String, value: String) {
        val ring = keyring
        if (ring == null) memory[account] = value
        else ring.setPassword(SERVICE, account, value)
    }

    private fun read(account: String): String? {
        val ring = keyring ?: return memory[account]
        // The keychain throws for an entry that does not exist; that is "no key".
        return runCatching { ring.getPassword(SERVICE, account) }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun delete(account: String) {
        memory.remove(account)
        keyring?.let { ring -> runCatching { ring.deletePassword(SERVICE, account) } }
    }

    override fun clear() = ProviderId.entries.forEach(::remove)

    private companion object {
        const val SERVICE = "Riff Buddy"
        const val TAG = "RiffKeys"
        const val SECRET_PREFIX = "secret:"
    }
}
