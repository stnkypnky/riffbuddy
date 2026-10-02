package com.riffbuddy.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Whether a request has any chance of reaching a provider.
 *
 * [AiCoach] has taken an `isOnline` hook since M3 and nothing ever supplied one,
 * so it defaulted to `{ true }` — which meant `AiResult.Offline` could not
 * happen, the message written for it was unreachable, and a request with no
 * connection spent a usage count, timed out, spent another on the fallback and
 * then reported "Network error". The spec promises a cached answer or a clear
 * message instead.
 *
 * `NET_CAPABILITY_VALIDATED` is the useful question. `INTERNET` only means the
 * network claims to offer it; VALIDATED means Android has checked and something
 * answered. The difference is a hotel wifi that has not been logged into yet —
 * the case where "no connection" is exactly the right thing to say.
 *
 * Read on demand rather than held: a callback would need registering and
 * unregistering against a lifecycle, and this is asked once per request, which
 * is nowhere near often enough to be worth it.
 */
fun connectivityCheck(context: Context): OnlineCheck {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    return OnlineCheck {
        val network = manager?.activeNetwork ?: return@OnlineCheck false
        val capabilities = manager.getNetworkCapabilities(network) ?: return@OnlineCheck false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
