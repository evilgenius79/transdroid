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
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/** Non-sensitive app preferences. */
class SettingsRepository(private val context: Context) {

    private val activeServerKey = stringPreferencesKey("active_server_id")
    private val notifyFinishedKey = booleanPreferencesKey("notify_finished")

    val activeServerId: Flow<String?> = context.settingsDataStore.data.map { it[activeServerKey] }

    suspend fun setActiveServer(profileId: String?) {
        context.settingsDataStore.edit { settings ->
            if (profileId == null) settings.remove(activeServerKey) else settings[activeServerKey] = profileId
        }
    }

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
}
