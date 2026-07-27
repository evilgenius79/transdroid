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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.transdroid.AppContainer
import org.transdroid.appContainer
import org.transdroid.data.ServerProfile
import org.transdroid.protocol.DaemonException
import org.transdroid.protocol.Torrent
import org.transdroid.protocol.TorrentFile
import org.transdroid.protocol.TorrentStatus

/** User-facing error kinds; mapped to localized strings in the UI layer. */
sealed class UiError {
    data class Connection(val host: String) : UiError()
    data object Authentication : UiError()
    data object Unexpected : UiError()
}

internal fun Throwable.toUiError(host: String): UiError = when (this) {
    is DaemonException.Connection -> UiError.Connection(host)
    is DaemonException.Authentication -> UiError.Authentication
    else -> UiError.Unexpected
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

data class TorrentsUiState(
    val activeProfile: ServerProfile? = null,
    /** False until the profile store has emitted, so we don't flash the welcome screen. */
    val profilesLoaded: Boolean = false,
    val torrents: List<Torrent> = emptyList(),
    /** True after the first successful load for the active profile. */
    val hasLoaded: Boolean = false,
    val refreshing: Boolean = false,
    val error: UiError? = null,
    val filter: TorrentFilter = TorrentFilter.ALL,
    val selectedTorrentId: String? = null,
    val files: Map<String, List<TorrentFile>> = emptyMap(),
) {
    val visibleTorrents: List<Torrent>
        get() = torrents.filter(filter::matches)

    val selectedTorrent: Torrent?
        get() = torrents.firstOrNull { it.id == selectedTorrentId }
}

class TorrentsViewModel(private val container: AppContainer) : ViewModel() {

    private val _ui = MutableStateFlow(TorrentsUiState())
    val ui: StateFlow<TorrentsUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            container.activeProfile.collect { profile ->
                _ui.update { state ->
                    val switched = profile?.id != state.activeProfile?.id
                    state.copy(
                        activeProfile = profile,
                        profilesLoaded = true,
                        torrents = if (switched) emptyList() else state.torrents,
                        hasLoaded = if (switched) false else state.hasLoaded,
                        files = if (switched) emptyMap() else state.files,
                        error = if (switched) null else state.error,
                    )
                }
                if (profile != null) refresh(showSpinner = false)
            }
        }
    }

    /** Runs while the torrents UI is started; cancellation stops the polling. */
    suspend fun pollLoop() {
        while (currentCoroutineContext().isActive) {
            refreshNow(showSpinner = false)
            delay(POLL_INTERVAL_MS)
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
                .sortedWith(compareByDescending<Torrent> { it.addedTimestamp ?: Long.MIN_VALUE }.thenBy { it.name })
            _ui.update {
                if (it.activeProfile?.id != profile.id) it
                else it.copy(torrents = torrents, hasLoaded = true, refreshing = false, error = null)
            }
        } catch (e: DaemonException) {
            _ui.update { it.copy(refreshing = false, error = e.toUiError(profile.host)) }
        }
    }

    fun setFilter(filter: TorrentFilter) {
        _ui.update { it.copy(filter = filter) }
    }

    fun select(torrentId: String?) {
        _ui.update { it.copy(selectedTorrentId = torrentId) }
    }

    fun toggleStartPause(torrent: Torrent) {
        runAction { adapter ->
            if (torrent.status == TorrentStatus.PAUSED) adapter.start(torrent.id) else adapter.pause(torrent.id)
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
                _ui.update { it.copy(files = it.files + (torrentId to files)) }
            } catch (e: DaemonException) {
                // Leave the files section empty; the list-level error banner covers connectivity
            }
        }
    }

    /** Adds a torrent by magnet/URL; invokes [onResult] with null on success. */
    fun add(url: String, onResult: (UiError?) -> Unit) {
        val profile = _ui.value.activeProfile ?: return
        viewModelScope.launch {
            try {
                container.adapterFor(profile).addByUrl(url)
                refreshNow(showSpinner = false)
                onResult(null)
            } catch (e: DaemonException) {
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
            } catch (e: DaemonException) {
                _ui.update { it.copy(error = e.toUiError(profile.host)) }
            }
        }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 5_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { TorrentsViewModel(checkNotNull(this[APPLICATION_KEY]).appContainer) }
        }
    }
}
