package com.riffbuddy.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.riffbuddy.AppServices

/**
 * The whole app on screen: the shell with its five tabs, and the celebrations
 * that play above it. Both platforms show exactly this; [overlay] is for
 * anything one platform draws on top, such as a launch animation.
 */
@Composable
fun RiffBuddyContent(app: AppServices, overlay: @Composable () -> Unit = {}) {
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
            syncService = app.sync,
        )
        com.riffbuddy.ui.settings.SyncHost(app.sync)
        // Song-mastered moments and milestone banners, above the app.
        CelebrationHost()
        ShortcutList()
        overlay()
    }
}
