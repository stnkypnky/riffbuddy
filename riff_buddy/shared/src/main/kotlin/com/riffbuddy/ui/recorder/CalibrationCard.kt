package com.riffbuddy.ui.recorder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.riffbuddy.ui.tactile.OutlinedButton
import androidx.compose.material3.Text
import com.riffbuddy.ui.tactile.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.riffbuddy.audio.CalibrationRun
import com.riffbuddy.audio.LatencyCalibration
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** The calibration in force. */
data class LatencyInfo(val offsetMs: Int, val spreadMs: Int, val calibratedAt: Instant?)

/**
 * Latency calibration: measure, save, and check.
 *
 * "Check" is the exit criterion made visible: it measures again and says how
 * far a recorded click lands from its source *with the saved offset applied* —
 * the number that should be within ±15 ms, shown on the device it is about.
 */
@Composable
fun CalibrationCard(
    info: LatencyInfo,
    run: () -> CalibrationRun,
    onSave: (LatencyCalibration.Result) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var explaining by remember { mutableStateOf<Boolean?>(null) }   // true = calibrate, false = check
    var outcome by remember { mutableStateOf<Pair<Boolean, CalibrationRun.Outcome>?>(null) }

    com.riffbuddy.ui.Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            com.riffbuddy.ui.SectionHeader("TIMING FOR PLAY-ALONG")
            Text(
                if (info.calibratedAt == null) {
                    "Not calibrated yet. Takes recorded along with the metronome or looper may sit a " +
                        "few milliseconds off the backing until you do."
                } else {
                    "Calibrated ${DATE.format(info.calibratedAt.atZone(ZoneId.systemDefault()))}: " +
                        "takes are moved ${info.offsetMs} ms, and the clicks agreed within ±${info.spreadMs} ms."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { explaining = true }, enabled = !busy) {
                    Text(if (info.calibratedAt == null) "Calibrate" else "Calibrate again")
                }
                if (info.calibratedAt != null) TextButton(onClick = { explaining = false }, enabled = !busy) { Text("Check it") }
                if (busy) CircularProgressIndicator(Modifier.padding(start = 4.dp))
            }
        }
    }

    explaining?.let { calibrating ->
        AlertDialog(
            onDismissRequest = { explaining = null },
            title = { Text(if (calibrating) "Calibrate timing" else "Check timing") },
            text = {
                Text(
                    "Unplug headphones and turn the volume up. Riff Buddy plays ten clicks through the " +
                        "speaker and listens for them — about six seconds. Keep the room quiet and don't " +
                        "cover the microphone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    explaining = null
                    busy = true
                    scope.launch {
                        outcome = calibrating to run().run()
                        busy = false
                    }
                }) { Text("Start") }
            },
            dismissButton = { TextButton(onClick = { explaining = null }) { Text("Cancel") } },
        )
    }

    outcome?.let { (calibrating, o) ->
        AlertDialog(
            onDismissRequest = { outcome = null },
            title = { Text(if (calibrating) "Calibration" else "Timing check") },
            text = {
                Text(
                    when (o) {
                        is CalibrationRun.Outcome.Failed -> o.message
                        is CalibrationRun.Outcome.Measured -> describe(o, calibrating, info)
                    },
                )
            },
            confirmButton = {
                if (calibrating && o is CalibrationRun.Outcome.Measured && o.result.found >= LatencyCalibration.MIN_FOUND) {
                    TextButton(onClick = { onSave(o.result); outcome = null }) { Text("Use this") }
                } else {
                    TextButton(onClick = { outcome = null }) { Text("OK") }
                }
            },
            dismissButton = { TextButton(onClick = { outcome = null }) { Text(if (calibrating) "Discard" else "Close") } },
        )
    }
}

private fun describe(o: CalibrationRun.Outcome.Measured, calibrating: Boolean, info: LatencyInfo): String {
    val r = o.result
    val clock = if (o.hardwareClocks) "" else
        "\n\nThis device did not report hardware timing, so the result rests on the app's own clock — still usable, but check it now and then."
    val heard = "Heard ${r.found} of ${r.expected} clicks."
    if (calibrating) {
        val verdict = if (r.withinTolerance) "They agreed within ±${r.spreadMs} ms — inside the ±${LatencyCalibration.TOLERANCE_MS} ms target."
        else "They disagreed by up to ${r.spreadMs} ms, outside the ±${LatencyCalibration.TOLERANCE_MS} ms target. Try again somewhere quieter."
        return "Takes will be moved ${r.offsetMs} ms to line up with the backing. $heard $verdict$clock"
    }
    // A check: how far would a click land from its source with the saved offset?
    val worst = abs(r.offsetMs - info.offsetMs) + r.spreadMs
    val verdict = if (worst <= LatencyCalibration.TOLERANCE_MS && r.found >= LatencyCalibration.MIN_FOUND) {
        "With the saved calibration, a recorded click lands within ±$worst ms of its source. That meets the ±${LatencyCalibration.TOLERANCE_MS} ms target."
    } else {
        "With the saved calibration, a recorded click could land up to $worst ms from its source — outside the target. Calibrate again."
    }
    return "$verdict $heard$clock"
}

private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d")
