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
package org.transdroid.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/** In-app theme override; SYSTEM follows the device dark-mode setting. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** What a horizontal swipe on a torrent row does. */
enum class SwipeAction { NONE, PAUSE_RESUME, REANNOUNCE, REMOVE }

/** Non-sensitive app preferences. */
class SettingsRepository(private val context: Context) {

    private val activeServerKey = stringPreferencesKey("active_server_id")
    private val notifyFinishedKey = booleanPreferencesKey("notify_finished")
    private val pollIntervalKey = intPreferencesKey("poll_interval_seconds")
    private val themeModeKey = stringPreferencesKey("theme_mode")
    private val swipeRightKey = stringPreferencesKey("swipe_right_action")
    private val swipeLeftKey = stringPreferencesKey("swipe_left_action")

    val activeServerId: Flow<String?> = context.settingsDataStore.data.map { it[activeServerKey] }

    suspend fun setActiveServer(profileId: String?) {
        context.settingsDataStore.edit { settings ->
            if (profileId == null) settings.remove(activeServerKey) else settings[activeServerKey] = profileId
        }
    }

    /** How often the torrents list refreshes while on screen, in seconds. */
    val pollIntervalSeconds: Flow<Int> =
        context.settingsDataStore.data.map { it[pollIntervalKey] ?: DEFAULT_POLL_INTERVAL_SECONDS }

    suspend fun setPollIntervalSeconds(seconds: Int) {
        context.settingsDataStore.edit { it[pollIntervalKey] = seconds.coerceIn(POLL_INTERVAL_OPTIONS.first(), POLL_INTERVAL_OPTIONS.last()) }
    }

    val themeMode: Flow<ThemeMode> = context.settingsDataStore.data.map {
        parseEnum(it[themeModeKey], ThemeMode.SYSTEM)
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { it[themeModeKey] = mode.name }
    }

    /** Action for swiping a torrent row left-to-right (start side to end side). */
    val swipeRightAction: Flow<SwipeAction> = context.settingsDataStore.data.map {
        parseEnum(it[swipeRightKey], SwipeAction.PAUSE_RESUME)
    }

    /** Action for swiping a torrent row right-to-left. */
    val swipeLeftAction: Flow<SwipeAction> = context.settingsDataStore.data.map {
        parseEnum(it[swipeLeftKey], SwipeAction.REMOVE)
    }

    suspend fun setSwipeRightAction(action: SwipeAction) {
        context.settingsDataStore.edit { it[swipeRightKey] = action.name }
    }

    suspend fun setSwipeLeftAction(action: SwipeAction) {
        context.settingsDataStore.edit { it[swipeLeftKey] = action.name }
    }

    private inline fun <reified T : Enum<T>> parseEnum(raw: String?, default: T): T =
        raw?.let { value -> enumValues<T>().firstOrNull { it.name == value } } ?: default

    /** Whether the background finished-torrent check and its notifications are enabled. */
    val notifyFinished: Flow<Boolean> = context.settingsDataStore.data.map { it[notifyFinishedKey] ?: false }

    suspend fun setNotifyFinished(enabled: Boolean) {
        context.settingsDataStore.edit { it[notifyFinishedKey] = enabled }
    }

    /** Ids of torrents seen unfinished on the last background check, per server profile. */
    fun unfinishedTorrentIds(profileId: String): Flow<Set<String>> =
        context.settingsDataStore.data.map { it[unfinishedKey(profileId)] ?: emptySet() }

    suspend fun setUnfinishedTorrentIds(profileId: String, ids: Set<String>) {
        context.settingsDataStore.edit { it[unfinishedKey(profileId)] = ids }
    }

    private fun unfinishedKey(profileId: String) = stringSetPreferencesKey("unfinished_ids_$profileId")

    companion object {
        const val DEFAULT_POLL_INTERVAL_SECONDS = 5
        val POLL_INTERVAL_OPTIONS = listOf(3, 5, 10, 30, 60)
    }
}
