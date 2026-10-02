package com.riffbuddy

import android.app.Application
import android.util.Log
import com.riffbuddy.ai.connectivityCheck
import com.riffbuddy.platform.AndroidAudioInputs
import com.riffbuddy.platform.AndroidAudioOutputs
import com.riffbuddy.platform.AndroidDb
import com.riffbuddy.platform.AndroidSecureStore
import com.riffbuddy.platform.AppLog

/**
 * The Android app object: makes the Android pieces — the framework database,
 * AudioTrack and the raw microphone, the Keystore, the connectivity check —
 * and hands them to the shared [AppServices], which wires everything else the
 * same way on every platform.
 */
class RiffBuddyApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Shared code logs through AppLog; on Android that is logcat.
        AppLog.sink = AppLog.Sink { level, tag, message, error ->
            if (level == 'W') Log.w(tag, message, error) else Log.i(tag, message)
        }
    }

    val services by lazy {
        AppServices(
            db = AndroidDb(this),
            audioOutputs = AndroidAudioOutputs(),
            audioInputs = AndroidAudioInputs(this),
            keys = AndroidSecureStore(this),
            isOnline = connectivityCheck(this),
            filesDir = filesDir,
            cacheDir = cacheDir,
            appVersion = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "unknown",
        )
    }

    override fun onTerminate() {
        super.onTerminate()
        services.stopAll()
    }
}
