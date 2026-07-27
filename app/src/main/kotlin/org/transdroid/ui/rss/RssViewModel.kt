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
package org.transdroid.ui.rss

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.transdroid.AppContainer
import org.transdroid.appContainer
import org.transdroid.data.RssFeed
import org.transdroid.protocol.rss.RssItem
import org.transdroid.ui.torrents.UiError
import org.transdroid.ui.torrents.toUiError

data class RssItemsUiState(
    val feed: RssFeed? = null,
    val loading: Boolean = false,
    val items: List<RssItem> = emptyList(),
    /** Items published after the feed was last opened are highlighted as new. */
    val newSinceTimestamp: Long? = null,
    val error: UiError? = null,
    /** Title of the item that was just sent to the server, for a confirmation message. */
    val addedItemTitle: String? = null,
    val addError: UiError? = null,
)

class RssViewModel(private val container: AppContainer) : ViewModel() {

    val feeds: StateFlow<List<RssFeed>> = container.profilesRepository.feeds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _items = MutableStateFlow(RssItemsUiState())
    val items: StateFlow<RssItemsUiState> = _items.asStateFlow()

    fun newFeedId(): String = UUID.randomUUID().toString()

    fun saveFeed(feed: RssFeed) {
        viewModelScope.launch { container.profilesRepository.saveFeed(feed) }
    }

    fun deleteFeed(feedId: String) {
        viewModelScope.launch { container.profilesRepository.deleteFeed(feedId) }
    }

    /** Loads a feed's items and remembers the previous last-viewed time for "new" badges. */
    fun openFeed(feedId: String) {
        val feed = feeds.value.firstOrNull { it.id == feedId }
            ?: run {
                // The feeds flow may not have emitted yet; resolve from the store
                viewModelScope.launch {
                    container.profilesRepository.feeds.first().firstOrNull { it.id == feedId }?.let { openFeed(it) }
                }
                return
            }
        openFeed(feed)
    }

    private fun openFeed(feed: RssFeed) {
        _items.value = RssItemsUiState(feed = feed, loading = true, newSinceTimestamp = feed.lastViewedTimestamp)
        viewModelScope.launch {
            try {
                val channel = container.rssFetcher.fetch(feed.url)
                val items = channel.items.sortedByDescending { it.timestamp ?: Long.MIN_VALUE }
                _items.update { it.copy(loading = false, items = items) }
                val newest = items.firstNotNullOfOrNull { item -> item.timestamp }
                if (newest != null && newest != feed.lastViewedTimestamp) {
                    container.profilesRepository.saveFeed(feed.copy(lastViewedTimestamp = newest))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _items.update { it.copy(loading = false, error = e.toUiError(feed.displayName)) }
            }
        }
    }

    /** Sends an item's torrent link to the active server. */
    fun addItem(item: RssItem) {
        val url = item.torrentUrl ?: return
        viewModelScope.launch {
            val profile = container.activeProfile.first()
            if (profile == null) {
                _items.update { it.copy(addError = UiError.Unexpected) }
                return@launch
            }
            try {
                container.adapterFor(profile).addByUrl(url)
                _items.update { it.copy(addedItemTitle = item.title, addError = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _items.update { it.copy(addError = e.toUiError(profile.host), addedItemTitle = null) }
            }
        }
    }

    fun clearAddResult() {
        _items.update { it.copy(addedItemTitle = null, addError = null) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { RssViewModel(checkNotNull(this[APPLICATION_KEY]).appContainer) }
        }
    }
}
