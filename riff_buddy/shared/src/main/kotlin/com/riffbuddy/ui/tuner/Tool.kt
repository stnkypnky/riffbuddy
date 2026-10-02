package com.riffbuddy.ui.tuner

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.AvTimer
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.ui.graphics.vector.ImageVector

/** The Tools tab's tools, each with the icon its chip shows. */
enum class Tool(val label: String, val icon: ImageVector) {
    METRONOME("Metronome", Icons.Filled.AvTimer),
    TUNER("Tuner", Icons.Filled.GraphicEq),
    THEORY("Theory", Icons.AutoMirrored.Filled.MenuBook),
    TABS("Tabs", Icons.Filled.LibraryMusic),
    LOOPER("Looper", Icons.Filled.Repeat),
    RECORDER("Recorder", Icons.Filled.Mic),
    BACKING("Backing", Icons.AutoMirrored.Filled.QueueMusic),
}
