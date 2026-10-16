package com.riffbuddy

import android.app.Application
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
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
            deviceName = deviceName(),
            holdNetwork = ::holdMulticast,
        )
    }

    /**
     * The name set in the phone's About screen, such as "Sam's phone". The Fire
     * fills that with its model code ("KFMAWI"), so a name that is just the
     * model gives way to the Bluetooth name ("Fire Tablet"), and the model is
     * the last resort. Bluetooth's is read defensively: newer Android may not
     * let an app see it.
     */
    private fun deviceName(): String {
        fun usable(name: String?) = name?.trim()?.takeIf { it.isNotEmpty() && !it.equals(Build.MODEL, ignoreCase = true) }
        return usable(runCatching { Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull())
            ?: usable(runCatching { Settings.Secure.getString(contentResolver, "bluetooth_name") }.getOrNull())
            ?: Build.MODEL
    }

    /**
     * Wi-Fi drivers drop multicast and broadcast packets, even with the
     * screen on, to save power, unless an app holds this lock; sync's "here
     * I am" travel that way. Held only while the app is open.
     */
    private fun holdMulticast(): AutoCloseable {
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("riffbuddy-sync").apply { setReferenceCounted(false); acquire() }
        return AutoCloseable { if (lock.isHeld) lock.release() }
    }

    override fun onTerminate() {
        super.onTerminate()
        services.stopAll()
    }
}
