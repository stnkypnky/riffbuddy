package com.riffbuddy.web

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeViewport
import com.riffbuddy.AppServices
import com.riffbuddy.ai.OnlineCheck
import com.riffbuddy.platform.AppLog
import com.riffbuddy.platform.LocalPlatform
import com.riffbuddy.ui.Intro
import com.riffbuddy.ui.RiffBuddyContent
import com.riffbuddy.ui.theme.RiffBuddyTheme
import com.riffbuddy.platform.toByteArray
import kotlinx.browser.document
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.js.Promise

/** Bundled files the app opens by name: the sample tabs and recordings, and its icon. */
private val ASSETS = listOf(
    "samples/pentatonic-study.gp", "samples/pentatonic-study.alphatex", "samples/a-minor-blues.musicxml",
    "samples/pentatonic-riff.wav", "samples/a440-test-tone.wav", "icons/foreground.png",
)

private class Started(val services: AppServices, val platform: WebPlatform, val condensed: FontFamily, val text: FontFamily)

/**
 * Riff Buddy in a browser. The page shows the app's ground at once; storage,
 * the database and the keys are read in (each is asynchronous in a browser),
 * and then the same app every edition shows takes over.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    AppLog.sink = AppLog.Sink { level, tag, message, error ->
        val line = "$level/$tag: $message" + (error?.let { " — ${it.message}" } ?: "")
        if (level == 'W') consoleWarn(line) else consoleLog(line)
    }
    com.riffbuddy.domain.Device.inBrowser = true
    var started by mutableStateOf<Started?>(null)
    var problem by mutableStateOf<String?>(null)
    val scope = MainScope()

    scope.launch {
        runCatching { start() }
            .onSuccess { started = it }
            .onFailure {
                AppLog.w("RiffWeb", "could not start", it)
                problem = "Riff Buddy could not start in this browser: ${it.message}. " +
                    "It needs a current Chrome, Edge, Firefox or Safari, with site storage allowed."
            }
    }

    // What changed in the app's files reaches the browser's storage every few
    // seconds, and as the page is hidden, the last moment a browser promises.
    scope.launch {
        while (true) {
            delay(3_000)
            if (started != null) webFiles.flush()
        }
    }
    onPageHidden { if (started != null) scope.launch { webFiles.flush() } }

    ComposeViewport(document.body!!) {
        val s = started
        if (s == null) {
            // The page's splash covers this until the app takes over (index.html).
            // Leaving it up for a problem would hide the one thing worth reading,
            // so a problem takes it down too.
            DisposableEffect(Unit) { onDispose { splashDone() } }
            Box(Modifier.fillMaxSize().background(Color(0xFF241F1B)), contentAlignment = Alignment.Center) {
                problem?.let {
                    LaunchedEffect(it) { splashDone() }
                    Text(it, color = Color(0xFFF7F0E4), modifier = Modifier.padding(24.dp))
                }
            }
        } else {
            RiffBuddyTheme(condensedFont = s.condensed, textFont = s.text, systemReducedMotion = prefersReducedMotion()) {
                CompositionLocalProvider(LocalPlatform provides s.platform) {
                    RiffBuddyContent(s.services, syncSection = { NoNearbySync() }, overlay = { Intro() })
                }
            }
        }
    }
}

private suspend fun start(): Started {
    jsDbOpen(sqlite3InitModule()).await<JsAny?>()
    webFiles = WebFiles.restore()
    webFiles.preload(ASSETS)
    val keys = WebSecureStore.load()
    // The bundled faces, the same files every edition ships: a browser's own
    // fonts differ from one computer to the next, and few have a condensed one.
    suspend fun face(file: String) = jsFetch("fonts/$file.ttf").await<JsAny>().toByteArray()
    val barlowMedium = face("BarlowCondensed-Medium")
    val barlowSemi = face("BarlowCondensed-SemiBold")
    val barlowBold = face("BarlowCondensed-Bold")
    val condensed = FontFamily(
        Font("BarlowCondensed", barlowMedium, FontWeight.Normal),
        Font("BarlowCondensed-Medium", barlowMedium, FontWeight.Medium),
        Font("BarlowCondensed-SemiBold", barlowSemi, FontWeight.SemiBold),
        Font("BarlowCondensed-Bold", barlowBold, FontWeight.Bold),
    )
    val interRegular = face("Inter-Regular")
    val interMedium = face("Inter-Medium")
    val interSemi = face("Inter-SemiBold")
    val text = FontFamily(
        Font("Inter", interRegular, FontWeight.Normal),
        Font("Inter-Medium", interMedium, FontWeight.Medium),
        Font("Inter-SemiBold", interSemi, FontWeight.SemiBold),
        Font("Inter-Bold", interSemi, FontWeight.Bold),
    )
    val services = AppServices(
        db = WebDb(),
        audioOutputs = WebAudioOutputs(),
        audioInputs = WebAudioInputs(),
        keys = keys,
        isOnline = OnlineCheck { navigatorOnline() },
        filesDir = WebFiles.FILES,
        cacheDir = WebFiles.CACHE,
        appVersion = appVersion(),
    )
    return Started(services, WebPlatform(services), condensed, text)
}

/** Settings' sync section in a browser, which can't reach other devices directly. */
@Composable
private fun NoNearbySync() {
    Text(
        "Syncing with your other devices needs the phone or computer app. In a browser, " +
            "move your practice across with Export below, then Import on the other device.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun jsDbOpen(sqlite3: Promise<JsAny>): Promise<JsAny?> = js("sqlite3.then(s => rbDb.open(s))")
private fun jsFetch(url: String): Promise<JsAny> = js("rbFiles.fetchBytes(url)")
private fun navigatorOnline(): Boolean = js("navigator.onLine")
private fun prefersReducedMotion(): Boolean = js("window.matchMedia('(prefers-reduced-motion: reduce)').matches")
private fun appVersion(): String = js("(window.RIFFBUDDY_VERSION || 'web')")
private fun onPageHidden(action: () -> Unit): Unit =
    js("document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'hidden') action(); })")
private fun splashDone(): Unit = js("window.rbSplash && window.rbSplash.done()")
private fun consoleLog(line: String): Unit = js("console.log(line)")
private fun consoleWarn(line: String): Unit = js("console.warn(line)")
