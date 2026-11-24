package com.riffbuddy.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.riffbuddy.AppServices
import com.riffbuddy.data.Repo
import com.riffbuddy.ui.theme.Finish
import com.riffbuddy.ui.theme.FinishTheme
import com.riffbuddy.ui.theme.Finishes
import kotlinx.coroutines.launch

/**
 * The whole app on screen: the shell with its five tabs, and the celebrations
 * that play above it. Every edition shows exactly this; [overlay] is for
 * anything one draws on top, such as a launch animation. Syncing with nearby
 * devices is the platform's to supply: [syncSection] in Settings, and
 * [syncHost] for its prompts, wherever it is open. A browser has neither.
 * [onFinish] hears each finish as it is shown, for what the platform draws
 * outside the app: a phone's status and navigation bar icons have to turn
 * dark on a light finish, or they vanish into it.
 */
@Composable
fun RiffBuddyContent(
    app: AppServices,
    syncSection: @Composable () -> Unit = {},
    syncHost: @Composable () -> Unit = {},
    /** Goes up each time sync brings in another device's changes. */
    dataChanges: () -> Int = { 0 },
    overlay: @Composable () -> Unit = {},
    onFinish: (Finish) -> Unit = {},
) {
    var finish by remember { mutableStateOf(Finishes.DEFAULT) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { finish = Finishes.byId(app.repo.setting(Repo.FINISH)) }
    val choose: (Finish) -> Unit = remember {
        { f -> finish = f; scope.launch { app.repo.putSetting(Repo.FINISH, f.id) } }
    }
    LaunchedEffect(finish) { onFinish(finish) }
    FinishTheme(finish) {
        CompositionLocalProvider(LocalChooseFinish provides choose) {
            AppRoot(app, syncSection, syncHost, dataChanges, overlay)
        }
    }
}

/** Sets the color scheme for the whole app and saves it. Settings' Appearance panel uses it. */
val LocalChooseFinish = staticCompositionLocalOf<(Finish) -> Unit> { {} }

@Composable
private fun AppRoot(
    app: AppServices,
    syncSection: @Composable () -> Unit,
    syncHost: @Composable () -> Unit,
    dataChanges: () -> Int,
    overlay: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize().then(shortcutRoot())) {
        RiffBuddyShell(
            backupRepo = app.backup,
            settingsRepo = app.repo,
            toneCardRepo = app.toneCards,
            tunerEngine = app.tuner,
            referenceTone = app.referenceTone,
            phrasePlayer = app.phrasePlayer,
            engine = app.metronome,
            presetRepo = app.presets,
            songRepo = app.songs,
            sessionRepo = app.sessions,
            practice = app.practice,
            gearRepo = app.gear,
            keys = app.keys,
            coach = app.coach,
            tabRepo = app.tabs,
            audioRepo = app.audio,
            looper = app.looper,
            recordingRepo = app.recordings,
            recorder = app.recorder,
            backingRepo = app.backing,
            planRepo = app.plan,
            tuningRepo = app.tunings,
            chatRepo = app.chat,
            routineRunner = app.routineRunner,
            partRepo = app.parts,
            milestoneRepo = app.milestones,
            customShapeRepo = app.customShapes,
            syncSection = syncSection,
            dataChanges = dataChanges,
        )
        syncHost()
        // Song-mastered moments and milestone banners, above the app.
        CelebrationHost()
        ShortcutList()
        overlay()
    }
}
