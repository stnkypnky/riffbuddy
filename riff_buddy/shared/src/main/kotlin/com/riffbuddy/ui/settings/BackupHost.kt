package com.riffbuddy.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.riffbuddy.data.BackupFormat
import com.riffbuddy.data.BackupRepo
import com.riffbuddy.data.Repo
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Wires backup and restore to the system file picker.
 *
 * Deliberately the *system* picker rather than a storage permission: the
 * player chooses where a backup lives — Downloads, a cloud drive, a home
 * server — and the app never needs to see the rest of their files to do it.
 */
class BackupController(
    private val repo: BackupRepo,
    private val settings: Repo,
) {
    var state by mutableStateOf(DataState())
        private set

    private var pending: BackupRepo.Contents? = null

    suspend fun refresh() {
        val last = settings.setting(Repo.LAST_BACKUP_AT)?.toLongOrNull()?.let(Instant::ofEpochMilli)
        state = state.copy(rowCounts = repo.rowCounts(), lastBackup = last)
    }

    suspend fun export(output: java.io.OutputStream) {
        state = state.copy(busy = "Writing your backup…", message = null, problem = null)
        runCatching { repo.exportTo(output) }.fold(
            onSuccess = { rows ->
                val now = Instant.now()
                settings.putSetting(Repo.LAST_BACKUP_AT, now.toEpochMilli().toString())
                state = state.copy(busy = null, message = "Backed up $rows rows.", lastBackup = now)
            },
            onFailure = { state = state.copy(busy = null, problem = "Could not write the backup: ${it.message}") },
        )
        refresh()
    }

    /** Reads and summarizes without applying anything — the stop in the middle. */
    suspend fun inspect(input: java.io.InputStream) {
        state = state.copy(busy = "Reading that file…", message = null, problem = null)
        repo.inspect(input).fold(
            onSuccess = { contents ->
                val root = contents.root
                val problem = BackupFormat.validate(root)
                if (problem != null) {
                    // The copy in the cache is useless now; do not leave it there.
                    repo.discard(contents)
                    state = state.copy(busy = null, problem = problem.message)
                    return
                }
                pending = contents
                state = state.copy(
                    busy = null,
                    pendingImport = PendingImport(
                        summary = BackupFormat.summarize(root),
                        createdAt = BackupFormat.createdAt(root),
                    ),
                )
            },
            onFailure = {
                state = state.copy(
                    busy = null,
                    problem = BackupFormat.Problem.Damaged(it.message.orEmpty()).message,
                )
            },
        )
    }

    suspend fun applyImport(mode: BackupFormat.Mode) {
        val contents = pending ?: return
        state = state.copy(busy = "Restoring…", pendingImport = null)
        repo.restore(contents, mode).fold(
            onSuccess = { restored ->
                val total = restored.counts.values.sum()
                val files = when (restored.files) {
                    0 -> ""
                    1 -> " and 1 file"
                    else -> " and ${restored.files} files"
                }
                // Say what a merge did, not just a total: an edit taken from
                // the backup, or an item left out, is something to know.
                val extra = buildList {
                    if (restored.updated > 0) add("${restored.updated} updated to the backup's newer version")
                    if (restored.renumbered > 0) add(
                        "${restored.renumbered} added on both devices kept side by side"
                    )
                    if (restored.skipped > 0) add("${restored.skipped} could not be added")
                }.joinToString(". ")
                state = state.copy(busy = null, message = "Restored $total rows$files." + if (extra.isNotEmpty()) " $extra." else "")
            },
            onFailure = { state = state.copy(busy = null, problem = "Restore failed: ${it.message}") },
        )
        pending = null
        refresh()
    }

    fun cancelImport() {
        pending?.let(repo::discard)
        pending = null
        state = state.copy(pendingImport = null)
    }

    suspend fun clearAll() {
        state = state.copy(busy = "Clearing…")
        repo.clearAll()
        state = state.copy(busy = null, message = "Everything cleared.")
        refresh()
    }
}

/** Gives the Me screen its file-picker callbacks. */
@Composable
fun rememberBackupCallbacks(controller: BackupController): BackupCallbacks {
    val platform = com.riffbuddy.platform.LocalPlatform.current
    val scope = rememberCoroutineScope()

    val createFile = platform.rememberCreateFile("application/octet-stream") { out -> controller.export(out) }

    // Any file type: some file managers do not report a custom extension as a
    // recognized MIME type, and a picker that hides the backup being looked
    // for is worse than one that shows too much.
    val openFile = platform.rememberOpenFile(listOf("*/*"), keepAccess = false) { picked ->
        if (picked != null) scope.launch {
            runCatching { platform.openInput(picked.ref) }.getOrNull()?.use { controller.inspect(it) }
        }
    }

    return remember(controller) {
        BackupCallbacks(
            onExport = { createFile(BackupFormat.suggestedFileName()) },
            onImport = openFile,
        )
    }
}

data class BackupCallbacks(val onExport: () -> Unit, val onImport: () -> Unit)
