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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.booleanResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import org.transdroid.R
import org.transdroid.data.SwipeAction
import org.transdroid.protocol.Torrent
import org.transdroid.protocol.TorrentStatus
import org.transdroid.ui.FlatProgressBar
import org.transdroid.ui.message
import org.transdroid.ui.label
import org.transdroid.ui.statusLabel
import org.transdroid.ui.theme.accentColor
import org.transdroid.util.formatBytes
import org.transdroid.util.formatEta
import org.transdroid.util.formatRatio
import org.transdroid.util.formatSpeed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TorrentsScreen(
    viewModel: TorrentsViewModel,
    useTwoPane: Boolean,
    onOpenDetails: (String) -> Unit,
    onAddTorrent: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenRss: () -> Unit,
    onOpenSearch: () -> Unit,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val rssAvailable = booleanResource(R.bool.rss_available)
    val searchAvailable = booleanResource(R.bool.search_available)

    // Poll the daemon while this screen is started; stops automatically when backgrounded
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.pollLoop()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.torrents_title))
                        ui.activeProfile?.let { profile ->
                            val transferring = ui.hasLoaded &&
                                (ui.totalDownloadRate > 0 || ui.totalUploadRate > 0)
                            Text(
                                if (transferring) {
                                    "${profile.displayName} · ↓ ${formatSpeed(ui.totalDownloadRate)} " +
                                        "↑ ${formatSpeed(ui.totalUploadRate)}"
                                } else {
                                    profile.displayName
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    if (searchAvailable) {
                        IconButton(onClick = onOpenSearch) {
                            Icon(Icons.Default.Search, contentDescription = stringResource(R.string.search_title))
                        }
                    }
                    if (rssAvailable) {
                        IconButton(onClick = onOpenRss) {
                            Icon(Icons.Default.RssFeed, contentDescription = stringResource(R.string.rss_title))
                        }
                    }
                    SortMenuButton(current = ui.sort, onSelect = { viewModel.setSort(it) })
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.torrents_settings))
                    }
                },
            )
        },
        floatingActionButton = {
            if (ui.activeProfile != null) {
                FloatingActionButton(onClick = onAddTorrent) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.torrents_add))
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                !ui.profilesLoaded -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                ui.activeProfile == null -> {
                    WelcomeContent(onOpenSettings = onOpenSettings, modifier = Modifier.align(Alignment.Center))
                }
                useTwoPane -> {
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(0.42f)) {
                            TorrentListContent(ui, viewModel, onOpenDetails)
                        }
                        VerticalDivider()
                        Box(Modifier.weight(0.58f)) {
                            val selected = ui.selectedTorrent
                            if (selected != null) {
                                TorrentDetailsContent(viewModel = viewModel, torrent = selected)
                            } else {
                                Text(
                                    stringResource(R.string.torrents_empty),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.align(Alignment.Center),
                                )
                            }
                        }
                    }
                }
                else -> TorrentListContent(ui, viewModel, onOpenDetails)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TorrentListContent(
    ui: TorrentsUiState,
    viewModel: TorrentsViewModel,
    onOpenDetails: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        ui.error?.let { error ->
            ErrorBanner(message = error.message(), onRetry = { viewModel.refresh() })
        }
        var showNameFilter by remember { mutableStateOf(ui.nameFilter.isNotBlank()) }
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {
                showNameFilter = !showNameFilter
                if (!showNameFilter) viewModel.setNameFilter("")
            }) {
                Icon(
                    Icons.Default.FilterList,
                    contentDescription = stringResource(R.string.filter_by_name),
                    tint = if (showNameFilter) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TorrentFilter.entries.forEach { filter ->
                FilterChip(
                    selected = ui.filter == filter,
                    onClick = { viewModel.setFilter(filter) },
                    label = { Text(filter.label()) },
                )
            }
        }
        if (showNameFilter) {
            OutlinedTextField(
                value = ui.nameFilter,
                onValueChange = { viewModel.setNameFilter(it) },
                placeholder = { Text(stringResource(R.string.filter_by_name)) },
                trailingIcon = {
                    if (ui.nameFilter.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setNameFilter("") }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.details_cancel))
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        if (ui.availableLabels.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ui.availableLabels.forEach { label ->
                    FilterChip(
                        selected = ui.labelFilter == label,
                        onClick = { viewModel.setLabelFilter(label) },
                        leadingIcon = {
                            Icon(
                                Icons.AutoMirrored.Filled.Label,
                                contentDescription = null,
                                modifier = Modifier.height(16.dp),
                            )
                        },
                        label = { Text(label) },
                    )
                }
            }
        }
        PullToRefreshBox(
            isRefreshing = ui.refreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize(),
        ) {
            if (ui.hasLoaded && ui.visibleTorrents.isEmpty()) {
                Box(Modifier.fillMaxSize()) {
                    Text(
                        stringResource(R.string.torrents_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            } else {
                var pendingRemove by remember { mutableStateOf<Torrent?>(null) }
                val onSwipeAction: (SwipeAction, Torrent) -> Unit = { action, torrent ->
                    when (action) {
                        SwipeAction.NONE -> {}
                        SwipeAction.PAUSE_RESUME -> viewModel.toggleStartPause(torrent)
                        SwipeAction.REANNOUNCE -> viewModel.forceReannounce(torrent)
                        SwipeAction.REMOVE -> pendingRemove = torrent
                    }
                }
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(ui.visibleTorrents, key = { it.id }) { torrent ->
                        SwipeableTorrentCard(
                            torrent = torrent,
                            selected = torrent.id == ui.selectedTorrentId,
                            rightAction = ui.swipeRightAction,
                            leftAction = ui.swipeLeftAction,
                            onClick = { onOpenDetails(torrent.id) },
                            onSwipeAction = onSwipeAction,
                        )
                    }
                }
                pendingRemove?.let { torrent ->
                    RemoveTorrentDialog(
                        torrent = torrent,
                        onDismiss = { pendingRemove = null },
                        onConfirm = { alsoDeleteData ->
                            pendingRemove = null
                            viewModel.remove(torrent, alsoDeleteData)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SwipeableTorrentCard(
    torrent: Torrent,
    selected: Boolean,
    rightAction: SwipeAction,
    leftAction: SwipeAction,
    onClick: () -> Unit,
    onSwipeAction: (SwipeAction, Torrent) -> Unit,
) {
    // The state's confirmValueChange closure is created once per row; read everything
    // through rememberUpdatedState so later recompositions (refreshes, settings changes)
    // are seen inside it
    val currentTorrent by rememberUpdatedState(torrent)
    val currentRight by rememberUpdatedState(rightAction)
    val currentLeft by rememberUpdatedState(leftAction)
    val currentOnSwipeAction by rememberUpdatedState(onSwipeAction)
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> currentOnSwipeAction(currentRight, currentTorrent)
                SwipeToDismissBoxValue.EndToStart -> currentOnSwipeAction(currentLeft, currentTorrent)
                SwipeToDismissBoxValue.Settled -> {}
            }
            // Always snap back; the action's outcome shows through the next refresh
            // (removal asks for confirmation first), never by dismissing the card
            false
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = rightAction != SwipeAction.NONE,
        enableDismissFromEndToStart = leftAction != SwipeAction.NONE,
        backgroundContent = {
            SwipeActionBackground(
                state = dismissState,
                rightAction = rightAction,
                leftAction = leftAction,
                torrent = torrent,
            )
        },
    ) {
        TorrentCard(torrent = torrent, selected = selected, onClick = onClick)
    }
}

@Composable
private fun SwipeActionBackground(
    state: SwipeToDismissBoxState,
    rightAction: SwipeAction,
    leftAction: SwipeAction,
    torrent: Torrent,
) {
    val direction = state.dismissDirection
    val action = when (direction) {
        SwipeToDismissBoxValue.StartToEnd -> rightAction
        SwipeToDismissBoxValue.EndToStart -> leftAction
        else -> SwipeAction.NONE
    }
    val paused = torrent.status == TorrentStatus.PAUSED
    val (icon, tint) = when (action) {
        SwipeAction.NONE -> return
        SwipeAction.PAUSE_RESUME ->
            (if (paused) Icons.Default.PlayArrow else Icons.Default.Pause) to MaterialTheme.colorScheme.primary
        SwipeAction.REANNOUNCE -> Icons.Default.Campaign to MaterialTheme.colorScheme.primary
        SwipeAction.REMOVE -> Icons.Default.Delete to MaterialTheme.colorScheme.error
    }
    Box(
        Modifier
            .fillMaxSize()
            .clip(CardDefaults.shape)
            .background(tint.copy(alpha = 0.15f)),
        contentAlignment = if (direction == SwipeToDismissBoxValue.StartToEnd) {
            Alignment.CenterStart
        } else {
            Alignment.CenterEnd
        },
    ) {
        Icon(
            icon,
            contentDescription = action.label(),
            tint = tint,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

@Composable
fun SwipeAction.label(): String = stringResource(
    when (this) {
        SwipeAction.NONE -> R.string.swipe_action_none
        SwipeAction.PAUSE_RESUME -> R.string.swipe_action_pause_resume
        SwipeAction.REANNOUNCE -> R.string.details_reannounce
        SwipeAction.REMOVE -> R.string.details_remove
    }
)

@Composable
private fun SortMenuButton(current: TorrentSort, onSelect: (TorrentSort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }) {
        Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(R.string.sort_title))
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        TorrentSort.entries.forEach { sort ->
            DropdownMenuItem(
                text = { Text(sort.label()) },
                leadingIcon = {
                    RadioButton(selected = sort == current, onClick = null)
                },
                onClick = {
                    onSelect(sort)
                    expanded = false
                },
            )
        }
    }
}

@Composable
private fun TorrentSort.label(): String = stringResource(
    when (this) {
        TorrentSort.DATE_ADDED -> R.string.sort_date_added
        TorrentSort.NAME -> R.string.sort_name
        TorrentSort.DOWNLOAD_SPEED -> R.string.sort_download_speed
        TorrentSort.UPLOAD_SPEED -> R.string.sort_upload_speed
        TorrentSort.RATIO -> R.string.sort_ratio
    }
)

@Composable
private fun TorrentFilter.label(): String = stringResource(
    when (this) {
        TorrentFilter.ALL -> R.string.filter_all
        TorrentFilter.DOWNLOADING -> R.string.filter_downloading
        TorrentFilter.SEEDING -> R.string.filter_seeding
        TorrentFilter.PAUSED -> R.string.filter_paused
        TorrentFilter.ERROR -> R.string.filter_error
    }
)

@Composable
private fun TorrentCard(torrent: Torrent, selected: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = if (selected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                torrent.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            FlatProgressBar(
                progress = torrent.displayProgress,
                color = torrent.status.accentColor,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    torrent.statusLabel(),
                    style = MaterialTheme.typography.labelMedium,
                    color = torrent.status.accentColor,
                )
                Text(
                    " · ${(torrent.displayProgress * 100).toInt()}% · ${formatBytes(torrent.sizeBytes)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                if (torrent.status.isActive) {
                    Text(
                        "↓ ${formatSpeed(torrent.downloadRate)}  ↑ ${formatSpeed(torrent.uploadRate)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        stringResource(R.string.details_ratio) + " " + formatRatio(torrent.ratio),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            formatEta(torrent.etaSeconds)?.let { eta ->
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(R.string.details_eta) + " " + eta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onRetry: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.torrents_retry))
            }
        }
    }
}

@Composable
private fun WelcomeContent(onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            Icons.Default.Dns,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(56.dp).height(56.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.torrents_no_server_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.torrents_no_server_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onOpenSettings) {
            Text(stringResource(R.string.torrents_no_server_button))
        }
    }
}
