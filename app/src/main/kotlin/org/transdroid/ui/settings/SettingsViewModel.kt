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
import javax.crypto.AEADBadTagException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.transdroid.AppContainer
import org.transdroid.appContainer
import org.transdroid.background.FinishedTorrentsWorker
import org.transdroid.data.BackupCrypto
import org.transdroid.data.ProfilesData
import org.transdroid.data.SearchProviderConfig
import org.transdroid.data.ServerProfile
import org.transdroid.protocol.CertificateFingerprint
import org.transdroid.protocol.Tls
import org.transdroid.protocol.discovery.DiscoveredDaemon
import org.transdroid.ui.torrents.UiError
import org.transdroid.ui.torrents.detailChain
import org.transdroid.ui.torrents.toUiError

sealed class TestState {
    data object Idle : TestState()
    data object Testing : TestState()
    data class Success(val versionInfo: String) : TestState()
    data class Failure(val error: UiError) : TestState()
}

sealed class CertificateState {
    data object Idle : CertificateState()
    data object Fetching : CertificateState()
    data class Fetched(val fingerprint: CertificateFingerprint) : CertificateState()
    data class Failed(val error: UiError) : CertificateState()
}

data class DiscoveryState(
    val scanning: Boolean = false,
    val scanned: Boolean = false,
    val found: List<DiscoveredDaemon> = emptyList(),
)

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    val profiles: StateFlow<List<ServerProfile>> = container.profilesRepository.profiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeServerId: StateFlow<String?> = container.settingsRepository.activeServerId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _testState = MutableStateFlow<TestState>(TestState.Idle)
    val testState: StateFlow<TestState> = _testState.asStateFlow()

    /** A failed settings write (Keystore or disk), shown once by the settings screen. */
    private val _writeError = MutableStateFlow<UiError?>(null)
    val writeError: StateFlow<UiError?> = _writeError.asStateFlow()

    fun clearWriteError() {
        _writeError.value = null
    }

    /**
     * Every store write goes through here: the Keystore can fail on a write just as it can
     * on a read, and an unhandled exception in viewModelScope takes the process down.
     */
    private fun launchWrite(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _writeError.value = UiError.Unexpected(e.detailChain() ?: e.javaClass.simpleName)
            }
        }
    }

    fun newProfileId(): String = UUID.randomUUID().toString()

    fun save(profile: ServerProfile) {
        launchWrite {
            // Read from the repository, not the StateFlow, which may not have emitted yet
            val firstServer = container.profilesRepository.profiles.first().isEmpty()
            container.profilesRepository.save(profile)
            if (firstServer) container.settingsRepository.setActiveServer(profile.id)
        }
    }

    fun delete(profileId: String) {
        launchWrite {
            val wasActive = container.settingsRepository.activeServerId.first() == profileId
            container.profilesRepository.delete(profileId)
            if (wasActive) container.settingsRepository.setActiveServer(null)
        }
    }

    fun setActive(profileId: String) {
        launchWrite { container.settingsRepository.setActiveServer(profileId) }
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
        _certificateState.value = CertificateState.Idle
    }

    private val _certificateState = MutableStateFlow<CertificateState>(CertificateState.Idle)
    val certificateState: StateFlow<CertificateState> = _certificateState.asStateFlow()

    /** Reads the server's certificate fingerprint so the user can decide to trust it. */
    fun fetchCertificate(host: String, port: Int) {
        _certificateState.value = CertificateState.Fetching
        viewModelScope.launch {
            _certificateState.value = try {
                CertificateState.Fetched(Tls.fetchCertificate(host, port))
            } catch (e: Exception) {
                CertificateState.Failed(e.toUiError(host))
            }
        }
    }

    fun dismissCertificate() {
        _certificateState.value = CertificateState.Idle
    }

    private val _discovery = MutableStateFlow(DiscoveryState())
    val discovery: StateFlow<DiscoveryState> = _discovery.asStateFlow()
    private var scanJob: Job? = null

    /** Scans the local network once per settings session; no-op while already scanning. */
    fun startLanScan() {
        if (_discovery.value.scanning) return
        _discovery.value = DiscoveryState(scanning = true)
        scanJob = viewModelScope.launch {
            val found = try {
                container.lanDiscovery.scan()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            _discovery.value = DiscoveryState(scanning = false, scanned = true, found = found)
        }
    }

    /** Leaving the add-server screen: a sweep of 700+ sockets must not outlive it. */
    fun stopLanScan() {
        scanJob?.cancel()
        scanJob = null
        _discovery.value = DiscoveryState()
    }

    val searchProviders: StateFlow<List<SearchProviderConfig>> = container.profilesRepository.searchProviders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun saveSearchProvider(provider: SearchProviderConfig) {
        launchWrite { container.profilesRepository.saveSearchProvider(provider) }
    }

    fun deleteSearchProvider(providerId: String) {
        launchWrite { container.profilesRepository.deleteSearchProvider(providerId) }
    }

    val notifyFinished: StateFlow<Boolean> = container.settingsRepository.notifyFinished
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val pollIntervalSeconds: StateFlow<Int> = container.settingsRepository.pollIntervalSeconds
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            org.transdroid.data.SettingsRepository.DEFAULT_POLL_INTERVAL_SECONDS,
        )

    fun setPollInterval(seconds: Int) {
        launchWrite { container.settingsRepository.setPollIntervalSeconds(seconds) }
    }

    val themeMode: StateFlow<org.transdroid.data.ThemeMode> = container.settingsRepository.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), org.transdroid.data.ThemeMode.SYSTEM)

    fun setThemeMode(mode: org.transdroid.data.ThemeMode) {
        launchWrite { container.settingsRepository.setThemeMode(mode) }
    }

    val swipeRightAction: StateFlow<org.transdroid.data.SwipeAction> = container.settingsRepository.swipeRightAction
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), org.transdroid.data.SwipeAction.PAUSE_RESUME)

    val swipeLeftAction: StateFlow<org.transdroid.data.SwipeAction> = container.settingsRepository.swipeLeftAction
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), org.transdroid.data.SwipeAction.REMOVE)

    fun setSwipeRightAction(action: org.transdroid.data.SwipeAction) {
        launchWrite { container.settingsRepository.setSwipeRightAction(action) }
    }

    fun setSwipeLeftAction(action: org.transdroid.data.SwipeAction) {
        launchWrite { container.settingsRepository.setSwipeLeftAction(action) }
    }

    /** Persists the toggle and (un)schedules the background check accordingly. */
    fun setNotifyFinished(context: android.content.Context, enabled: Boolean) {
        launchWrite {
            container.settingsRepository.setNotifyFinished(enabled)
            if (enabled) {
                FinishedTorrentsWorker.schedule(context.applicationContext)
            } else {
                FinishedTorrentsWorker.cancel(context.applicationContext)
            }
        }
    }

    sealed class BackupState {
        data object Idle : BackupState()
        data object Creating : BackupState()
        data class Ready(val bytes: ByteArray) : BackupState()
        data object Failed : BackupState()
    }

    /**
     * Exposed as state rather than a callback: key derivation takes up to a second, during
     * which the screen can be recreated, and a callback into a dead composition would
     * launch the document picker from an unregistered launcher.
     */
    private val _backupState = MutableStateFlow<BackupState>(BackupState.Idle)
    val backupState: StateFlow<BackupState> = _backupState.asStateFlow()

    /** Serializes and encrypts the whole settings store with the given passphrase. */
    fun createBackup(passphrase: String) {
        _backupState.value = BackupState.Creating
        viewModelScope.launch {
            _backupState.value = try {
                val data = container.profilesRepository.currentData()
                val bytes = withContext(Dispatchers.Default) {
                    BackupCrypto.encrypt(
                        backupJson.encodeToString(ProfilesData.serializer(), data).encodeToByteArray(),
                        passphrase.toCharArray(),
                    )
                }
                BackupState.Ready(bytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BackupState.Failed
            }
        }
    }

    fun consumeBackup() {
        _backupState.value = BackupState.Idle
    }

    fun restoreBackup(bytes: ByteArray, passphrase: String) {
        viewModelScope.launch {
            val result = try {
                val plaintext = withContext(Dispatchers.Default) {
                    BackupCrypto.decrypt(bytes, passphrase.toCharArray())
                }
                val data = backupJson.decodeFromString(ProfilesData.serializer(), plaintext.decodeToString())
                container.profilesRepository.replaceData(data)
                // The restored profiles carry their own ids; reset the selection if stale
                val activeId = container.settingsRepository.activeServerId.first()
                if (data.profiles.none { it.id == activeId }) {
                    container.settingsRepository.setActiveServer(data.profiles.firstOrNull()?.id)
                }
                RestoreResult.Success(data.profiles.size)
            } catch (e: AEADBadTagException) {
                RestoreResult.WrongPassphrase
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RestoreResult.InvalidFile
            }
            _restoreResult.value = result
        }
    }

    /** Same reasoning as [backupState]: survives the screen being recreated mid-restore. */
    private val _restoreResult = MutableStateFlow<RestoreResult?>(null)
    val restoreResult: StateFlow<RestoreResult?> = _restoreResult.asStateFlow()

    fun consumeRestoreResult() {
        _restoreResult.value = null
    }

    sealed class RestoreResult {
        data class Success(val serverCount: Int) : RestoreResult()
        data object WrongPassphrase : RestoreResult()
        data object InvalidFile : RestoreResult()
    }

    companion object {
        private val backupJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { SettingsViewModel(checkNotNull(this[APPLICATION_KEY]).appContainer) }
        }
    }
}
