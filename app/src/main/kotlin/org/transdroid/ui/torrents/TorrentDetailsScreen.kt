/*
 * Copyright 2010-2026 Eric Kok et al.
 *
 * Transdroid is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Transdroid is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Transdroid. If not, see <https://www.gnu.org/licenses/>.
 */
package org.transdroid.ui.torrents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date
import org.transdroid.R
import org.transdroid.protocol.FilePriority
import org.transdroid.protocol.Torrent
import org.transdroid.protocol.TorrentStatus
import org.transdroid.protocol.TrackerInfo
import org.transdroid.ui.FlatProgressBar
import org.transdroid.ui.label
import org.transdroid.ui.statusLabel
import org.transdroid.ui.theme.accentColor
import org.transdroid.util.formatBytes
import org.transdroid.util.formatEta
import org.transdroid.util.formatRatio
import org.transdroid.util.formatSpeed

/** Full-screen torrent details for compact widths; two-pane layouts embed the content directly. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TorrentDetailsScreen(
    viewModel: TorrentsViewModel,
    torrentId: String,
    onBack: () -> Unit,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val torrent = ui.torrents.firstOrNull { it.id == torrentId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        torrent?.name ?: "",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.details_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (torrent == null) {
                Text(
                    stringResource(R.string.details_not_found),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                TorrentDetailsContent(viewModel = viewModel, torrent = torrent, onRemoved = onBack)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TorrentDetailsContent(
    viewModel: TorrentsViewModel,
    torrent: Torrent,
    onRemoved: (() -> Unit)? = null,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    // Saveable so a rotation mid-confirmation keeps the dialog (and its checkbox) up
    var showRemoveDialog by rememberSaveable(torrent.id) { mutableStateOf(false) }
    var trackerToRemoveUrl by rememberSaveable(torrent.id) { mutableStateOf<String?>(null) }
    val trackerToRemove = trackerToRemoveUrl?.let { url -> ui.trackers[torrent.id]?.firstOrNull { it.url == url } }

    LaunchedEffect(torrent.id) {
        viewModel.loadFiles(torrent.id)
        viewModel.loadTrackers(torrent.id)
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        ui.actionError?.let { error ->
            ActionErrorBanner(error = error, onDismiss = { viewModel.clearActionError() })
            Spacer(Modifier.height(8.dp))
        }
        Text(torrent.name, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        FlatProgressBar(
            progress = torrent.displayProgress,
            color = torrent.status.accentColor,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Row {
            Text(
                torrent.statusLabel(),
                style = MaterialTheme.typography.labelLarge,
                color = torrent.status.accentColor,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${(torrent.displayProgress * 100).toInt()}%",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        torrent.error?.let { error ->
            Spacer(Modifier.height(8.dp))
            Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(16.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val paused = torrent.status.isStopped
            Button(onClick = { viewModel.toggleStartPause(torrent) }) {
                Icon(
                    if (paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                    contentDescription = null,
                )
                Text(
                    " " + stringResource(if (paused) R.string.details_start else R.string.details_pause)
                )
            }
            OutlinedButton(onClick = { viewModel.forceReannounce(torrent) }) {
                Icon(Icons.Default.Campaign, contentDescription = null)
                Text(" " + stringResource(R.string.details_reannounce))
            }
            OutlinedButton(onClick = { showRemoveDialog = true }) {
                Icon(Icons.Default.Delete, contentDescription = null)
                Text(" " + stringResource(R.string.details_remove))
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(R.string.details_section_transfer),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        DetailRow(stringResource(R.string.details_size), formatBytes(torrent.sizeBytes))
        DetailRow(
            stringResource(R.string.details_downloaded),
            formatBytes(torrent.downloadedBytes) +
                (if (torrent.downloadRate > 0) "  ↓ ${formatSpeed(torrent.downloadRate)}" else ""),
        )
        DetailRow(
            stringResource(R.string.details_uploaded),
            formatBytes(torrent.uploadedBytes) +
                (if (torrent.uploadRate > 0) "  ↑ ${formatSpeed(torrent.uploadRate)}" else ""),
        )
        DetailRow(stringResource(R.string.details_ratio), formatRatio(torrent.ratio))
        DetailRow(stringResource(R.string.details_peers), torrent.peersConnected.toString())
        if (torrent.labels.isNotEmpty()) {
            DetailRow(stringResource(R.string.details_labels), torrent.labels.joinToString())
        }
        formatEta(torrent.etaSeconds)?.let { DetailRow(stringResource(R.string.details_eta), it) }
        torrent.addedTimestamp?.let {
            DetailRow(
                stringResource(R.string.details_added),
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it * 1000)),
            )
        }
        torrent.downloadDir?.let { DetailRow(stringResource(R.string.details_location), it) }

        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(R.string.details_section_trackers),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        val trackers = ui.trackers[torrent.id]
        if (trackers.isNullOrEmpty()) {
            Text(
                stringResource(R.string.details_trackers_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            trackers.forEachIndexed { index, tracker ->
                if (index > 0) HorizontalDivider(Modifier.padding(vertical = 2.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            tracker.url,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        tracker.status?.let { status ->
                            Text(
                                status,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(onClick = { trackerToRemoveUrl = tracker.url }) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.details_tracker_remove),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(R.string.details_section_files),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        val files = ui.files[torrent.id]
        if (files.isNullOrEmpty()) {
            Text(
                stringResource(R.string.details_files_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            files.forEachIndexed { index, file ->
                if (index > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp))
                var priorityMenuOpen by remember(file.index) { mutableStateOf(false) }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { priorityMenuOpen = true },
                ) {
                    Text(
                        file.path,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${formatBytes(file.downloadedBytes)} / ${formatBytes(file.sizeBytes)} · " +
                            file.priority.label(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    DropdownMenu(
                        expanded = priorityMenuOpen,
                        onDismissRequest = { priorityMenuOpen = false },
                    ) {
                        FilePriority.entries.forEach { priority ->
                            DropdownMenuItem(
                                text = { Text(priority.label()) },
                                leadingIcon = { RadioButton(selected = priority == file.priority, onClick = null) },
                                onClick = {
                                    priorityMenuOpen = false
                                    if (priority != file.priority) {
                                        viewModel.setFilePriority(torrent.id, file, priority)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }

    if (showRemoveDialog) {
        RemoveTorrentDialog(
            torrent = torrent,
            onDismiss = { showRemoveDialog = false },
            onConfirm = { alsoDeleteData ->
                showRemoveDialog = false
                viewModel.remove(torrent, alsoDeleteData)
                onRemoved?.invoke()
            },
        )
    }

    trackerToRemove?.let { tracker ->
        AlertDialog(
            onDismissRequest = { trackerToRemoveUrl = null },
            title = { Text(stringResource(R.string.details_tracker_remove_title)) },
            text = { Text(stringResource(R.string.details_tracker_remove_message, tracker.url)) },
            confirmButton = {
                TextButton(onClick = {
                    trackerToRemoveUrl = null
                    viewModel.removeTracker(torrent.id, tracker)
                }) {
                    Text(stringResource(R.string.details_remove_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { trackerToRemoveUrl = null }) {
                    Text(stringResource(R.string.details_cancel))
                }
            },
        )
    }
}

/** Shared removal confirmation, used from the details actions and the list swipe gesture. */
@Composable
fun RemoveTorrentDialog(
    torrent: Torrent,
    onDismiss: () -> Unit,
    onConfirm: (alsoDeleteData: Boolean) -> Unit,
) {
    var alsoDeleteData by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.details_remove_title)) },
        text = {
            Column {
                Text(stringResource(R.string.details_remove_message, torrent.name))
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = alsoDeleteData, onCheckedChange = { alsoDeleteData = it })
                    Text(stringResource(R.string.details_remove_also_data))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(alsoDeleteData) }) {
                Text(stringResource(R.string.details_remove_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.details_cancel))
            }
        },
    )
}

@Composable
private fun FilePriority.label(): String = stringResource(
    when (this) {
        FilePriority.OFF -> R.string.priority_off
        FilePriority.LOW -> R.string.priority_low
        FilePriority.NORMAL -> R.string.priority_normal
        FilePriority.HIGH -> R.string.priority_high
    }
)

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(0.35f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
