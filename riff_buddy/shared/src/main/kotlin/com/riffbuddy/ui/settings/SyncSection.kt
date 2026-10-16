package com.riffbuddy.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.riffbuddy.sync.PairPrompt
import com.riffbuddy.sync.PairedDevice
import com.riffbuddy.sync.SyncDiscovery
import com.riffbuddy.sync.SyncService
import com.riffbuddy.ui.tactile.Button
import com.riffbuddy.ui.tactile.OutlinedButton
import com.riffbuddy.ui.tactile.TextButton
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Syncing with the player's other devices: this device's name, the devices
 * it is paired with, the ones heard nearby that it could pair with, and
 * the last sync's undo.
 */
@Composable
fun SyncSection(sync: SyncService) {
    val scope = rememberCoroutineScope()
    var renaming by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    var unpairing by remember { mutableStateOf<PairedDevice?>(null) }
    var confirmingUndo by remember { mutableStateOf(false) }
    val idle = sync.busy == null
    val nearbyIds = sync.nearby.map { it.peer.id }.toSet()
    val pairedIds = sync.devices.map { it.peer.id }.toSet()

    com.riffbuddy.ui.Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            com.riffbuddy.ui.SectionHeader("SYNC WITH YOUR OTHER DEVICES")
            Text(
                "Phone, tablet and computer, on the same Wi-Fi, with Riff Buddy open on both. " +
                    "Each gets what the other added, the newer edit wins, and a deletion carries " +
                    "across. Nothing goes over the internet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("This device", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(sync.me.name, style = MaterialTheme.typography.titleMedium)
                }
                TextButton(onClick = { renaming = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Rename") }
            }
            Text(
                if (sync.listening) {
                    val addresses = SyncDiscovery.localAddresses()
                    "Ready for your other devices" + (if (addresses.isEmpty()) "." else ", at ${addresses.joinToString(" or ")}.")
                } else "Not reachable from other devices right now. Another copy of Riff Buddy may be open on this computer.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (sync.devices.isNotEmpty()) {
                Text("Paired", style = MaterialTheme.typography.titleSmall)
                sync.devices.forEach { device ->
                    DeviceRow(
                        device = device,
                        nearby = device.peer.id in nearbyIds,
                        enabled = idle,
                        onSync = { scope.launch { sync.sync(device) } },
                        onUnpair = { unpairing = device },
                    )
                }
            }

            Text("Pair a new device", style = MaterialTheme.typography.titleSmall)
            val unpaired = sync.nearby.filter { it.peer.id !in pairedIds }
            if (unpaired.isEmpty()) {
                Text(
                    "Open Riff Buddy on the other device, on the same Wi-Fi, and it shows up here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            unpaired.forEach { found ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(found.peer.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    OutlinedButton(
                        onClick = { scope.launch { sync.pair(found.address) } },
                        enabled = idle,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Pair") }
                }
            }
            TextButton(onClick = { typing = true }, enabled = idle, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Not listed? Type its address")
            }

            Box(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    sync.busy?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    sync.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary) }
                    sync.problem?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
                }
            }

            sync.undo?.let { undo ->
                Text(
                    "Last sync here: with ${undo.peerName}, ${WHEN.format(Instant.ofEpochMilli(undo.at).atZone(ZoneId.systemDefault()))}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { confirmingUndo = true },
                    enabled = idle,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Undo last sync") }
            }

            Text(
                "Not synced: settings, gear, API keys, and the pairing itself. Your gear and settings " +
                    "go across once, to a device that doesn't have them yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (renaming) {
        NameDialog(sync.me.name, onCancel = { renaming = false }, onSave = { sync.rename(it); renaming = false })
    }
    if (typing) {
        AddressDialog(onCancel = { typing = false }, onPair = { address ->
            typing = false
            scope.launch { sync.pair(address) }
        })
    }
    unpairing?.let { device ->
        AlertDialog(
            onDismissRequest = { unpairing = null },
            title = { Text("Unpair ${device.peer.name}?") },
            text = { Text("Nothing is deleted on either device. To sync with it again, pair them again.") },
            confirmButton = { TextButton(onClick = { sync.unpair(device); unpairing = null }) { Text("Unpair") } },
            dismissButton = { TextButton(onClick = { unpairing = null }) { Text("Keep") } },
        )
    }
    if (confirmingUndo) {
        AlertDialog(
            onDismissRequest = { confirmingUndo = false },
            title = { Text("Undo the last sync?") },
            text = {
                Text(
                    "This device goes back to exactly how it was before it. The other device keeps " +
                        "what it took in, so the next sync brings it back here unless you change it there too.",
                )
            },
            confirmButton = { TextButton(onClick = { confirmingUndo = false; scope.launch { sync.undoLastSync() } }) { Text("Undo") } },
            dismissButton = { TextButton(onClick = { confirmingUndo = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DeviceRow(
    device: PairedDevice,
    nearby: Boolean,
    enabled: Boolean,
    onSync: () -> Unit,
    onUnpair: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(device.peer.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            // The dot says it at a glance; the words say it to everyone.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    Modifier.size(8.dp).background(
                        if (nearby) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline,
                        CircleShape,
                    ),
                )
                Text(
                    if (nearby) "On the network now" else "Not heard on the network",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                device.lastSynced?.let { "Last synced ${WHEN.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))}" }
                    ?: "Not synced yet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Button(onClick = onSync, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Sync now") }
            TextButton(onClick = onUnpair, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Unpair") }
        }
    }
}

@Composable
private fun NameDialog(current: String, onCancel: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Name this device") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(40) },
                singleLine = true,
                label = { Text("What your other devices call it") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun AddressDialog(onCancel: () -> Unit, onPair: (String) -> Unit) {
    var address by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Pair by address") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Some routers keep devices from hearing each other. The other device shows its " +
                        "address here in Settings, under this section: type it in.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it.filter { c -> c.isDigit() || c == '.' }.take(15) },
                    singleLine = true,
                    label = { Text("Address, like 192.168.1.23") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onPair(address) }, enabled = address.count { it == '.' } == 3) { Text("Pair") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/**
 * What sync needs at the root of the app, whatever screen is showing:
 * listening only while the app is in front, the pairing question (the
 * other device may ask while this one is on any screen), and a word when
 * another device has synced with this one.
 */
@Composable
fun SyncHost(sync: SyncService) {
    val scope = rememberCoroutineScope()
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> sync.start(scope)
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> sync.stop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); sync.stop() }
    }
    sync.prompt?.let { PairDialog(it) }
    sync.notice?.let { text ->
        LaunchedEffect(text) {
            kotlinx.coroutines.delay(6000)
            sync.notice = null
        }
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomCenter) {
            androidx.compose.material3.Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shadowElevation = 6.dp,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
                    TextButton(onClick = { sync.notice = null }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("OK", color = MaterialTheme.colorScheme.inversePrimary)
                    }
                }
            }
        }
    }
}

@Composable
private fun PairDialog(prompt: PairPrompt) {
    AlertDialog(
        onDismissRequest = { prompt.reply(false) },
        title = { Text("Pair with ${prompt.peer.name}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Check that ${prompt.peer.name} shows the same number:", style = MaterialTheme.typography.bodyMedium)
                Text(
                    prompt.code.chunked(3).joinToString(" "),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Same number: tap It matches on both. Different: tap Cancel; something else on " +
                        "the network got in between.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { prompt.reply(true) }) { Text("It matches") } },
        dismissButton = { TextButton(onClick = { prompt.reply(false) }) { Text("Cancel") } },
    )
}

private val WHEN = DateTimeFormatter.ofPattern("MMM d, h:mm a")
