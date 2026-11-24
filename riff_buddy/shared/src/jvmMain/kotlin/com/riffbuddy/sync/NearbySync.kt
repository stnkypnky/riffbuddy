package com.riffbuddy.sync

import com.riffbuddy.AppServices

/**
 * Syncing with the player's other devices on the same network, which only a
 * phone or computer can do: a browser can't open sockets. Each app makes one
 * and keeps it beside its services.
 */
fun AppServices.nearbySync(
    /** What other devices see this one called, until the player renames it. */
    deviceName: String = "This device",
    /** Held while syncing is open: a phone drops broadcasts without it. */
    holdNetwork: () -> AutoCloseable = { AutoCloseable {} },
    /** Where sync listens and announces; a tool rendering screens keeps off the real one. */
    port: Int = SyncService.PORT,
): SyncService = SyncService(backup, keys, filesDir.toFile(), cacheDir.toFile(), defaultName = deviceName, holdNetwork = holdNetwork, port = port)
