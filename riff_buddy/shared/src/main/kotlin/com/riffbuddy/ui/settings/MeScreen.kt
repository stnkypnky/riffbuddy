package com.riffbuddy.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import com.riffbuddy.ui.tactile.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.riffbuddy.ai.ProviderId
import com.riffbuddy.data.BackupFormat
import com.riffbuddy.data.Rig
import com.riffbuddy.domain.Session
import com.riffbuddy.domain.Song
import com.riffbuddy.domain.gear.ControlRole
import com.riffbuddy.domain.gear.Reach
import com.riffbuddy.ui.log.LogScreen
import com.riffbuddy.ui.log.SessionEdits
import com.riffbuddy.domain.PracticeSettings
import com.riffbuddy.domain.HelpTopic
import com.riffbuddy.ui.help.HelpButton

private enum class MeTab(val label: String, val help: HelpTopic) {
    LOG("Log", HelpTopic.LOG), STATS("Stats", HelpTopic.STATS), GEAR("Gear", HelpTopic.GEAR), SETTINGS("Settings", HelpTopic.SETTINGS),
}

/**
 * The Me tab: the practice log, the gear, and settings.
 *
 * Three sections behind chips rather than three more bottom tabs — the bottom
 * bar is already five wide, and these are the things looked at sitting down
 * rather than with a guitar on.
 */
@Composable
fun MeScreen(
    sessions: List<Session>,
    songs: List<Song>,
    rig: Rig?,
    providers: List<ProviderState>,
    primary: ProviderId,
    onSaveKey: (ProviderId, String) -> Unit,
    onRemoveKey: (ProviderId) -> Unit,
    onTest: (ProviderId) -> Unit,
    onSetPrimary: (ProviderId) -> Unit,
    onPickModel: (ProviderId, String) -> Unit,
    onRefreshModels: (ProviderId) -> Unit,
    dataState: DataState,
    onExport: () -> Unit,
    onPickBackupFile: () -> Unit,
    onConfirmImport: (BackupFormat.Mode) -> Unit,
    onCancelImport: () -> Unit,
    onClearAll: () -> Unit,
    /** The reminder settings. */
    reminders: @Composable () -> Unit = {},
    sessionEdits: SessionEdits? = null,
    /** Song parts, for tagging sessions and for Stats. */
    parts: List<com.riffbuddy.domain.SongPart> = emptyList(),
    /** The Stats tab, drawn by the shell, which holds its data. */
    stats: @Composable () -> Unit = {},
    /** Which tab to open on; changes each time something asks for Stats. */
    openStats: Int = 0,
    /** Goal and streak threshold, and the section that sets them. */
    practice: PracticeSettings = PracticeSettings(),
    practiceSection: @Composable () -> Unit = {},
    /** The gear editor. */
    gear: @Composable () -> Unit = {},
    /** Syncing with the player's other devices. */
    sync: @Composable () -> Unit = {},
) {
    var section by remember(openStats) { mutableStateOf(if (openStats > 0) MeTab.STATS else MeTab.LOG) }
    // A backup waiting to be confirmed is shown where its dialog lives, even
    // when it arrived from elsewhere, such as a file dropped on the window.
    androidx.compose.runtime.LaunchedEffect(dataState.pendingImport) {
        if (dataState.pendingImport != null) section = MeTab.SETTINGS
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 12.dp)) {
            com.riffbuddy.ui.ChipFlow(Modifier.weight(1f).padding(top = 4.dp)) {
                MeTab.entries.forEach { t ->
                    FilterChip(
                        selected = section == t,
                        onClick = { section = t },
                        label = { Text(t.label) },
                        modifier = Modifier.heightIn(min = 40.dp),
                    )
                }
            }
            HelpButton(section.help)
        }

        when (section) {
            MeTab.LOG -> LogScreen(sessions, songs, weeklyGoalMinutes = practice.weeklyGoalMinutes, edits = sessionEdits, streakMinutes = practice.streakMinutes, parts = parts)
            MeTab.STATS -> stats()
            MeTab.GEAR -> gear()
            MeTab.SETTINGS -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Settings", style = MaterialTheme.typography.headlineMedium)
                AiSettingsSection(
                    providers, primary, onSaveKey, onRemoveKey, onTest, onSetPrimary,
                    onPickModel, onRefreshModels,
                )
                practiceSection()
                reminders()
                sync()
                DataSection(
                    state = dataState,
                    onExport = onExport,
                    onPickFile = onPickBackupFile,
                    onConfirmImport = onConfirmImport,
                    onCancelImport = onCancelImport,
                    onClearAll = onClearAll,
                )
                AboutCard()
            }
        }
    }
}


@Composable
private fun AboutCard() {
    com.riffbuddy.ui.Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Label("WHAT LEAVES THIS DEVICE")
            Text(
                "Only what the coach needs: the song's title and artist, the part you asked " +
                    "about, your tuning and capo, and your gear's names and controls. Never your " +
                    "recordings, your tab files, your notes or your practice history.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Syncing sends your data only to devices you paired, on your own Wi-Fi, sealed so " +
                    "nothing else on the network can read it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Everything else — metronome, songbook, log — works with no network at all.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Label(text: String) = com.riffbuddy.ui.SectionHeader(text)
