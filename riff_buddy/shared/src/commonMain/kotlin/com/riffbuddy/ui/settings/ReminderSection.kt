package com.riffbuddy.ui.settings

import com.riffbuddy.ui.theme.Space
import com.riffbuddy.ui.ValueReadout
import com.riffbuddy.ui.TimeEntryDialog
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.riffbuddy.data.ReminderStore
import com.riffbuddy.data.Repo
import com.riffbuddy.domain.Device
import com.riffbuddy.domain.ReminderCalendar
import com.riffbuddy.domain.ReminderSettings
import com.riffbuddy.domain.format
import com.riffbuddy.domain.localZone
import com.riffbuddy.domain.shortName
import com.riffbuddy.ui.tactile.FilterChip
import com.riffbuddy.ui.tactile.TextButton
import kotlin.time.Clock
import kotlinx.coroutines.launch
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime

/**
 * A practice reminder at a chosen time on chosen days.
 *
 * The notification permission (Android 13 and later) is asked for only when
 * the switch is turned on: a permission prompt at any other moment would be
 * one nobody could connect to anything they did.
 */
@Composable
fun ReminderSection(repo: Repo) {
    val platform = com.riffbuddy.platform.LocalPlatform.current
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(ReminderSettings()) }
    var next by remember { mutableStateOf<String?>(null) }
    var denied by remember { mutableStateOf(false) }
    var typingTime by remember { mutableStateOf(false) }

    fun apply(s: ReminderSettings) {
        settings = s
        scope.launch {
            ReminderStore.save(repo, s)
            next = platform.scheduleReminder(s)?.let { it.format(FORMAT) }
        }
    }

    LaunchedEffect(Unit) {
        settings = ReminderStore.load(repo)
        next = platform.scheduleReminder(settings)?.let { it.format(FORMAT) }
    }

    val askToNotify = platform.rememberPermissionRequest(com.riffbuddy.platform.Permission.NOTIFICATIONS) { granted ->
        denied = !granted
        if (granted) apply(settings.copy(enabled = true))
    }

    com.riffbuddy.ui.Panel(contentPadding = Space.panel) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Practice reminder", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (settings.enabled && next != null) "Next: $next" else "Off",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.enabled,
                    onCheckedChange = { on ->
                        val needsPermission = !platform.hasPermission(com.riffbuddy.platform.Permission.NOTIFICATIONS)
                        if (on && needsPermission) askToNotify()
                        else apply(settings.copy(enabled = on))
                    },
                    modifier = Modifier.semantics { contentDescription = "Practice reminder" },
                )
            }
            if (denied) {
                Text("Notifications are off for Riff Buddy, so a reminder cannot show. Turn them on in Android's settings.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            platform.reminderNote?.let { note ->
                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (settings.enabled) {
                // Tap the time to type any minute; the quarter-hour buttons
                // stay for a quick nudge.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Text("At", modifier = Modifier.width(32.dp))
                    TextButton(
                        onClick = { apply(settings.copy(time = settings.time.shiftedBy(-15))) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("−15") }
                    ValueReadout(
                        text = settings.time.format("h:mm a"),
                        enabled = true,
                        label = "Reminder time",
                        onClick = { typingTime = true },
                    )
                    TextButton(
                        onClick = { apply(settings.copy(time = settings.time.shiftedBy(15))) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("+15") }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DayOfWeek.entries.forEach { d ->
                        val on = d in settings.days
                        FilterChip(
                            selected = on,
                            onClick = { apply(settings.copy(days = if (on) settings.days - d else settings.days + d)) },
                            label = { Text(d.shortName) },
                            modifier = Modifier.heightIn(min = 40.dp),
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Not on days I've already practiced", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = settings.skipIfPracticed, onCheckedChange = { apply(settings.copy(skipIfPracticed = it)) })
                }
                if (platform.canExportCalendar) {
                    // The calendar reminds with the app closed, which a web page can't.
                    TextButton(onClick = {
                        ReminderCalendar.ics(settings, Clock.System.now(), localZone())?.let(platform::exportCalendar)
                    }) { Text("Add to my calendar") }
                }
                Text(
                    (if (Device.onComputer || platform.reminderNote != null) "" else "Android may deliver it a few minutes late to save battery. ") +
                        "If it's more than a month since your last backup, a second notification says so — no more " +
                        "than once a week.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (typingTime) {
        TimeEntryDialog(
            title = "Remind me at",
            hour = settings.time.hour,
            minute = settings.time.minute,
            onTime = { h, m -> apply(settings.copy(time = LocalTime(h, m))) },
            onDismiss = { typingTime = false },
        )
    }
}

private const val FORMAT = "EEE, MMM d, h:mm a"

/** The time [minutes] later, wrapping around midnight. */
private fun LocalTime.shiftedBy(minutes: Int): LocalTime =
    LocalTime.fromSecondOfDay((toSecondOfDay() + minutes * 60).mod(86_400))
