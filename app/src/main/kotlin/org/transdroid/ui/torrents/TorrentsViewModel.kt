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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.transdroid.AppContainer
import org.transdroid.appContainer
import org.transdroid.data.ServerProfile
import org.transdroid.data.SwipeAction
import org.transdroid.protocol.DaemonException
import org.transdroid.protocol.FilePriority
import org.transdroid.protocol.Torrent
import org.transdroid.protocol.TorrentFile
import org.transdroid.protocol.TorrentStatus
import org.transdroid.protocol.TrackerInfo

/**
 * User-facing error kinds; mapped to localized strings in the UI layer. [detail] carries
 * the exact underlying error text (HTTP codes included) for the error-details dialog.
 */
sealed class UiError {
    abstract val detail: String?

    data class Connection(val host: String, override val detail: String? = null) : UiError()
    data class Authentication(override val detail: String? = null) : UiError()
    data class Ssl(override val detail: String? = null) : UiError()
    data class Unexpected(override val detail: String? = null) : UiError()
}

/** The exception's own message plus any distinct cause messages, oldest last. */
internal fun Throwable.detailChain(): String? =
    generateSequence(this as Throwable?) { it.cause }
        .mapNotNull { it.message?.trim()?.takeIf(String::isNotEmpty) }
        .distinct()
        .joinToString(" — ")
        .takeIf { it.isNotEmpty() }

internal fun Throwable.toUiError(host: String): UiError = when (this) {
    is DaemonException.Connection -> UiError.Connection(host, detailChain())
    is DaemonException.Authentication -> UiError.Authentication(detailChain())
    is DaemonException.UntrustedServer -> UiError.Ssl(detailChain())
    is DaemonException.UnexpectedResponse -> UiError.Unexpected(detailChain())
    else -> UiError.Unexpected(detailChain() ?: this::class.simpleName)
}

enum class TorrentFilter {
    ALL, DOWNLOADING, SEEDING, PAUSED, ERROR;

    fun matches(torrent: Torrent): Boolean = when (this) {
        ALL -> true
        DOWNLOADING -> torrent.status == TorrentStatus.DOWNLOADING || torrent.status == TorrentStatus.QUEUED ||
            torrent.status == TorrentStatus.CHECKING
        SEEDING -> torrent.status == TorrentStatus.SEEDING
        PAUSED -> torrent.status == TorrentStatus.PAUSED
        ERROR -> torrent.status == TorrentStatus.ERROR
    }
}

enum class TorrentSort {
    DATE_ADDED, NAME, DOWNLOAD_SPEED, UPLOAD_SPEED, RATIO;

    fun comparator(): Comparator<Torrent> = when (this) {
        DATE_ADDED -> compareByDescending { it.addedTimestamp ?: Long.MIN_VALUE }
        NAME -> compareBy { it.name.lowercase() }
        DOWNLOAD_SPEED -> compareByDescending { it.downloadRate }
        UPLOAD_SPEED -> compareByDescending { it.uploadRate }
        RATIO -> compareByDescending { it.ratio }
    }
}

data class TorrentsUiState(
    val activeProfile: ServerProfile? = null,
    /** False until the profile store has emitted, so we don't flash the welcome screen. */
    val profilesLoaded: Boolean = false,
    /** Number of configured servers; single-server flows can skip confirmation steps. */
    val profileCount: Int = 0,
    val torrents: List<Torrent> = emptyList(),
    /** True after the first successful load for the active profile. */
    val hasLoaded: Boolean = false,
    val refreshing: Boolean = false,
    val error: UiError? = null,
    val filter: TorrentFilter = TorrentFilter.ALL,
    val labelFilter: String? = null,
    val nameFilter: String = "",
    val sort: TorrentSort = TorrentSort.DATE_ADDED,
    val selectedTorrentId: String? = null,
    val files: Map<String, List<TorrentFile>> = emptyMap(),
    val trackers: Map<String, List<TrackerInfo>> = emptyMap(),
    val swipeRightAction: SwipeAction = SwipeAction.PAUSE_RESUME,
    val swipeLeftAction: SwipeAction = SwipeAction.REMOVE,
    /**
     * Failure of an explicit user action (pause, remove, priority…). Kept apart from
     * [error] because the poll loop clears that one on its next success, which would
     * erase an action failure before the user could see it.
     */
    val actionError: UiError? = null,
) {
    // Derived once per state instance rather than on every read: composition reads
    // these several times per frame and the list can hold thousands of torrents
    val availableLabels: List<String> by lazy { torrents.flatMap { it.labels }.distinct().sorted() }

    val totalDownloadRate: Long
        get() = torrents.sumOf { it.downloadRate }

    val totalUploadRate: Long
        get() = torrents.sumOf { it.uploadRate }

    val visibleTorrents: List<Torrent> by lazy {
        val query = nameFilter.trim()
        torrents
            .filter {
                filter.matches(it) &&
                    (labelFilter == null || labelFilter in it.labels) &&
                    (query.isEmpty() || it.name.contains(query, ignoreCase = true))
            }
            .sortedWith(sort.comparator().thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    val selectedTorrent: Torrent?
        get() = torrents.firstOrNull { it.id == selectedTorrentId }
}

class TorrentsViewModel(private val container: AppContainer) : ViewModel() {

    private val _ui = MutableStateFlow(TorrentsUiState())
    val ui: StateFlow<TorrentsUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            combine(container.activeProfile, container.profilesRepository.profiles) { active, all ->
                active to all.size
            }.collect { (profile, count) ->
                _ui.update { state ->
                    val switched = profile?.id != state.activeProfile?.id
                    if (!switched) {
                        state.copy(activeProfile = profile, profilesLoaded = true, profileCount = count)
                    } else {
                        // Nothing loaded for the previous server may survive the switch
                        state.copy(
                            activeProfile = profile,
                            profilesLoaded = true,
                            profileCount = count,
                            torrents = emptyList(),
                            hasLoaded = false,
                            files = emptyMap(),
                            trackers = emptyMap(),
                            selectedTorrentId = null,
                            error = null,
                            actionError = null,
                        )
                    }
                }
                if (profile != null) refresh(showSpinner = false)
            }
        }
        viewModelScope.launch {
            combine(
                container.settingsRepository.swipeRightAction,
                container.settingsRepository.swipeLeftAction,
            ) { right, left -> right to left }.collect { (right, left) ->
                _ui.update { it.copy(swipeRightAction = right, swipeLeftAction = left) }
            }
        }
    }

    /** Runs while the torrents UI is started; cancellation stops the polling. */
    suspend fun pollLoop() {
        while (currentCoroutineContext().isActive) {
            refreshNow(showSpinner = false)
            delay(container.settingsRepository.pollIntervalSeconds.first() * 1000L)
        }
    }

    fun refresh(showSpinner: Boolean = true) {
        viewModelScope.launch { refreshNow(showSpinner) }
    }

    private suspend fun refreshNow(showSpinner: Boolean) {
        val profile = _ui.value.activeProfile ?: return
        if (showSpinner) _ui.update { it.copy(refreshing = true) }
        try {
            val torrents = container.adapterFor(profile).listTorrents()
            // A late result from a server the user already switched away from is stale
            // for the list and for the widget alike
            if (_ui.value.activeProfile?.id != profile.id) return
            _ui.update { it.copy(torrents = torrents, hasLoaded = true, refreshing = false, error = null) }
            container.widgetStateRepository.update(profile.displayName, torrents)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _ui.update {
                // A late failure from a server the user already switched away from is stale
                if (it.activeProfile?.id != profile.id) it
                else it.copy(refreshing = false, error = e.toUiError(profile.host))
            }
        }
    }

    fun setFilter(filter: TorrentFilter) {
        _ui.update { it.copy(filter = filter) }
    }

    fun setSort(sort: TorrentSort) {
        _ui.update { it.copy(sort = sort) }
    }

    fun setLabelFilter(label: String?) {
        _ui.update { it.copy(labelFilter = if (it.labelFilter == label) null else label) }
    }

    fun setNameFilter(query: String) {
        _ui.update { it.copy(nameFilter = query) }
    }

    fun setFilePriority(torrentId: String, file: TorrentFile, priority: FilePriority) {
        val profile = _ui.value.activeProfile ?: return
        viewModelScope.launch {
            try {
                val adapter = container.adapterFor(profile)
                adapter.setFilePriority(torrentId, file.index, priority)
                val files = adapter.listFiles(torrentId)
                _ui.update {
                    if (it.activeProfile?.id != profile.id) it else it.copy(files = it.files + (torrentId to files))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(actionError = e.toUiError(profile.host)) }
            }
        }
    }

    fun clearActionError() {
        _ui.update { it.copy(actionError = null) }
    }

    fun select(torrentId: String?) {
        _ui.update { it.copy(selectedTorrentId = torrentId) }
    }

    /** Start when stopped — including errored torrents, which every client leaves stopped. */
    fun toggleStartPause(torrent: Torrent) {
        runAction { adapter ->
            if (torrent.status.isStopped) adapter.start(torrent.id) else adapter.pause(torrent.id)
        }
    }

    fun forceReannounce(torrent: Torrent) {
        runAction { adapter -> adapter.forceReannounce(torrent.id) }
    }

    fun loadTrackers(torrentId: String) {
        val profile = _ui.value.activeProfile ?: return
        viewModelScope.launch {
            try {
                val trackers = container.adapterFor(profile).listTrackers(torrentId)
                _ui.update {
                    if (it.activeProfile?.id != profile.id) it else it.copy(trackers = it.trackers + (torrentId to trackers))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Leave the trackers section empty; the list-level error banner covers connectivity
            }
        }
    }

    fun removeTracker(torrentId: String, tracker: TrackerInfo) {
        val profile = _ui.value.activeProfile ?: return
        viewModelScope.launch {
            try {
                val adapter = container.adapterFor(profile)
                adapter.removeTracker(torrentId, tracker)
                val trackers = adapter.listTrackers(torrentId)
                _ui.update {
                    if (it.activeProfile?.id != profile.id) it else it.copy(trackers = it.trackers + (torrentId to trackers))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(actionError = e.toUiError(profile.host)) }
            }
        }
    }

    fun remove(torrent: Torrent, deleteData: Boolean) {
        runAction { adapter -> adapter.remove(torrent.id, deleteData) }
    }

    fun loadFiles(torrentId: String) {
        val profile = _ui.value.activeProfile ?: return
        viewModelScope.launch {
            try {
                val files = container.adapterFor(profile).listFiles(torrentId)
                _ui.update {
                    if (it.activeProfile?.id != profile.id) it else it.copy(files = it.files + (torrentId to files))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Leave the files section empty; the list-level error banner covers connectivity
            }
        }
    }

    /** Adds a torrent by magnet/URL; invokes [onResult] with null on success. */
    fun add(url: String, startPaused: Boolean = false, onResult: (UiError?) -> Unit) {
        val profile = _ui.value.activeProfile ?: return
        viewModelScope.launch {
            try {
                container.adapterFor(profile).addByUrl(url, startPaused)
                refreshNow(showSpinner = false)
                onResult(null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onResult(e.toUiError(profile.host))
            }
        }
    }

    /** Adds a torrent from the raw contents of a .torrent file. */
    fun addFile(fileName: String, contents: ByteArray, startPaused: Boolean = false, onResult: (UiError?) -> Unit) {
        val profile = _ui.value.activeProfile ?: return
        viewModelScope.launch {
            try {
                container.adapterFor(profile).addByFile(fileName, contents, startPaused)
                refreshNow(showSpinner = false)
                onResult(null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onResult(e.toUiError(profile.host))
            }
        }
    }

    private fun runAction(action: suspend (org.transdroid.protocol.DaemonAdapter) -> Unit) {
        val profile = _ui.value.activeProfile ?: return
        viewModelScope.launch {
            try {
                action(container.adapterFor(profile))
                refreshNow(showSpinner = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(actionError = e.toUiError(profile.host)) }
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { TorrentsViewModel(checkNotNull(this[APPLICATION_KEY]).appContainer) }
        }
    }
}
