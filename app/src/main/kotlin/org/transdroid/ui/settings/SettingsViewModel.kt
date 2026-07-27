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
package org.transdroid.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.transdroid.AppContainer
import org.transdroid.appContainer
import org.transdroid.background.FinishedTorrentsWorker
import org.transdroid.data.SearchProviderConfig
import org.transdroid.data.ServerProfile
import org.transdroid.ui.torrents.UiError
import org.transdroid.ui.torrents.toUiError

sealed class TestState {
    data object Idle : TestState()
    data object Testing : TestState()
    data class Success(val versionInfo: String) : TestState()
    data class Failure(val error: UiError) : TestState()
}

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    val profiles: StateFlow<List<ServerProfile>> = container.profilesRepository.profiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeServerId: StateFlow<String?> = container.settingsRepository.activeServerId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _testState = MutableStateFlow<TestState>(TestState.Idle)
    val testState: StateFlow<TestState> = _testState.asStateFlow()

    fun newProfileId(): String = UUID.randomUUID().toString()

    fun save(profile: ServerProfile) {
        viewModelScope.launch {
            // Read from the repository, not the StateFlow, which may not have emitted yet
            val firstServer = container.profilesRepository.profiles.first().isEmpty()
            container.profilesRepository.save(profile)
            if (firstServer) container.settingsRepository.setActiveServer(profile.id)
        }
    }

    fun delete(profileId: String) {
        viewModelScope.launch {
            val wasActive = container.settingsRepository.activeServerId.first() == profileId
            container.profilesRepository.delete(profileId)
            if (wasActive) container.settingsRepository.setActiveServer(null)
        }
    }

    fun setActive(profileId: String) {
        viewModelScope.launch { container.settingsRepository.setActiveServer(profileId) }
    }

    fun testConnection(profile: ServerProfile) {
        _testState.value = TestState.Testing
        viewModelScope.launch {
            _testState.value = try {
                TestState.Success(container.adapterForTest(profile).testConnection())
            } catch (e: Exception) {
                TestState.Failure(e.toUiError(profile.host))
            }
        }
    }

    fun resetTestState() {
        _testState.value = TestState.Idle
    }

    val searchProviders: StateFlow<List<SearchProviderConfig>> = container.profilesRepository.searchProviders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun saveSearchProvider(provider: SearchProviderConfig) {
        viewModelScope.launch { container.profilesRepository.saveSearchProvider(provider) }
    }

    fun deleteSearchProvider(providerId: String) {
        viewModelScope.launch { container.profilesRepository.deleteSearchProvider(providerId) }
    }

    val notifyFinished: StateFlow<Boolean> = container.settingsRepository.notifyFinished
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Persists the toggle and (un)schedules the background check accordingly. */
    fun setNotifyFinished(context: android.content.Context, enabled: Boolean) {
        viewModelScope.launch {
            container.settingsRepository.setNotifyFinished(enabled)
            if (enabled) {
                FinishedTorrentsWorker.schedule(context.applicationContext)
            } else {
                FinishedTorrentsWorker.cancel(context.applicationContext)
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { SettingsViewModel(checkNotNull(this[APPLICATION_KEY]).appContainer) }
        }
    }
}
