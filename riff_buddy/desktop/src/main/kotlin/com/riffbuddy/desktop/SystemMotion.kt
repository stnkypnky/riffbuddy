package com.riffbuddy.desktop

import java.util.concurrent.TimeUnit

/**
 * Whether the person has asked their computer for less motion, so the app's
 * transitions follow the same choice. Each system keeps it somewhere different,
 * and none of them offers it to Java directly, so it is read the way their own
 * settings tools read it. Anything unreadable counts as "no preference".
 */
object SystemMotion {

    fun reduced(): Boolean {
        val os = System.getProperty("os.name").lowercase()
        return when {
            // Windows' "Animation effects" switch; 0 means off.
            "win" in os -> run("reg", "query", """HKCU\Control Panel\Desktop\WindowMetrics""", "/v", "MinAnimate")
                ?.lineSequence()?.firstOrNull { "MinAnimate" in it }?.trim()?.endsWith("0") == true
            "mac" in os -> run("defaults", "read", "com.apple.universalaccess", "reduceMotion")?.trim() == "1"
            // GNOME and the desktops built on it (Zorin, Ubuntu, Debian's default).
            else -> run("gsettings", "get", "org.gnome.desktop.interface", "enable-animations")?.trim() == "false"
        }
    }

    private fun run(vararg command: String): String? = runCatching {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.destroy()
            return null
        }
        if (process.exitValue() != 0) null else process.inputStream.bufferedReader().readText()
    }.getOrNull()
}
