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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import com.riffbuddy.ui.PageHeader
import com.riffbuddy.ui.theme.Space
import com.riffbuddy.ui.theme.Widths
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
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

/** A part of the Me tab another screen can open it on. */
enum class MeSection { LOG, STATS, GEAR, SETTINGS }

private enum class MeTab(val label: String, val help: HelpTopic, val icon: ImageVector) {
    LOG("Log", HelpTopic.LOG, Icons.AutoMirrored.Filled.EventNote),
    STATS("Stats", HelpTopic.STATS, Icons.Filled.Insights),
    GEAR("Gear", HelpTopic.GEAR, Icons.Filled.Speaker),
    SETTINGS("Settings", HelpTopic.SETTINGS, Icons.Filled.Settings),
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
    /** The section to open on, when another screen sent the player here. */
    start: MeSection? = null,
    /** Whether paid AI providers are tried after the free ones. */
    paidFallback: Boolean = false,
    onPaidFallback: ((Boolean) -> Unit)? = null,
    /** Goal and streak threshold, and the section that sets them. */
    practice: PracticeSettings = PracticeSettings(),
    practiceSection: @Composable () -> Unit = {},
    /** The gear editor. */
    gear: @Composable () -> Unit = {},
    /** Syncing with the player's other devices. */
    sync: @Composable () -> Unit = {},
) {
    var section by remember(openStats, start) {
        mutableStateOf(
            when {
                start != null -> MeTab.entries[start.ordinal]
                openStats > 0 -> MeTab.STATS
                else -> MeTab.LOG
            }
        )
    }
    // A backup waiting to be confirmed is shown where its dialog lives, even
    // when it arrived from elsewhere, such as a file dropped on the window.
    androidx.compose.runtime.LaunchedEffect(dataState.pendingImport) {
        if (dataState.pendingImport != null) section = MeTab.SETTINGS
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
    // The same edge the Log, Stats and Gear screens choose from this width, so
    // the header's left edge meets theirs.
    val edge = if (maxWidth >= 760.dp) Space.screenWide else Space.screen
    // The header sits in the same box as the section below it, centered the
    // same way, so the control's left edge and the help button line up with
    // the content's edges: the reading column for Settings, the width the
    // shell gives for the rest. The padding is even on both sides so the
    // centered box sits exactly over the content; the help button is nudged
    // a step outward instead, so its icon rather than its touch area lines
    // up with the content's right edge.
    val headerWidth = if (section == MeTab.SETTINGS) Modifier.widthIn(max = Widths.reading) else Modifier
    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxWidth().padding(start = edge, end = edge, top = Space.md),
            contentAlignment = Alignment.TopCenter,
        ) {
            Row(
                headerWidth.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    Segmented(section, Modifier.widthIn(max = 560.dp).fillMaxWidth()) { section = it }
                }
                Spacer(Modifier.width(Space.xs))
                HelpButton(section.help, Modifier.offset(x = Space.sm))
            }
        }

        when (section) {
            MeTab.LOG -> LogScreen(sessions, songs, weeklyGoalMinutes = practice.weeklyGoalMinutes, edits = sessionEdits, streakMinutes = practice.streakMinutes, parts = parts)
            MeTab.STATS -> stats()
            MeTab.GEAR -> gear()
            // Settings stays one column at reading width on any window: it is
            // read top to bottom, and form fields stretched across a desktop
            // are harder to scan, not easier.
            MeTab.SETTINGS -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(start = edge, end = edge, top = Space.lg, bottom = Space.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    Modifier.widthIn(max = Widths.reading).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Space.lg),
                ) {
                PageHeader("Settings", eyebrow = "Make it yours", subtitle = "Look, coach, practice goals, reminders and your data.")
                AppearanceSection()
                AiSettingsSection(
                    providers, primary, onSaveKey, onRemoveKey, onTest, onSetPrimary,
                    onPickModel, onRefreshModels,
                    paidFallback = paidFallback, onPaidFallback = onPaidFallback,
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
    }
}


@Composable
private fun AboutCard() {
    com.riffbuddy.ui.Panel(Modifier.fillMaxWidth(), contentPadding = Space.panel) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            Label("What leaves this device")
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

/**
 * The four sections as one segmented control: a recessed track with the
 * chosen segment raised in the finish's accent. Each segment carries its
 * name as well as its icon, and is announced as a tab with its selected state.
 */
@Composable
private fun Segmented(current: MeTab, modifier: Modifier = Modifier, onPick: (MeTab) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    // Icons only where there's room: on a phone the four names alone fill the
    // row, and a size smaller so "Settings" keeps clear of its pill's edge
    // down to a 360 dp screen rather than being cut off.
    val compact = com.riffbuddy.ui.widthClass() == com.riffbuddy.ui.WidthClass.COMPACT
    val icons = !compact
    val labelStyle = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge
    Row(
        modifier
            .background(scheme.surfaceContainerHigh, CircleShape)
            .border(1.dp, scheme.outlineVariant, CircleShape)
            .padding(Space.xs)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        MeTab.entries.forEach { t ->
            val selected = t == current
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(CircleShape)
                    .background(if (selected) scheme.primary else Color.Transparent)
                    .selectable(selected = selected, role = Role.Tab, onClick = { onPick(t) })
                    .padding(horizontal = if (compact) Space.xs else Space.sm),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icons) {
                    Icon(
                        t.icon,
                        contentDescription = null,
                        tint = if (selected) scheme.onPrimary else scheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(Space.xs + Space.xxs))
                }
                Text(
                    t.label,
                    style = labelStyle,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = if (selected) scheme.onPrimary else scheme.onSurface,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}
