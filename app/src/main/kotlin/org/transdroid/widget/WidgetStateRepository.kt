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
package org.transdroid.widget

import android.content.Context
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.transdroid.protocol.Torrent
import org.transdroid.protocol.TorrentStatus

private val Context.widgetDataStore by preferencesDataStore(name = "widget_state")

/** One row of the list widget; a trimmed-down torrent snapshot. */
@Serializable
data class WidgetTorrent(
    val id: String = "",
    val name: String,
    val progress: Float,
    val status: TorrentStatus,
    val downloadRate: Long,
    val uploadRate: Long,
    val etaSeconds: Long? = null,
)

data class WidgetState(
    val profileId: String? = null,
    val serverName: String? = null,
    val downloadingCount: Int = 0,
    val seedingCount: Int = 0,
    val pausedCount: Int = 0,
    val downloadRate: Long = 0,
    val uploadRate: Long = 0,
    val updatedAtMillis: Long? = null,
    val torrents: List<WidgetTorrent> = emptyList(),
)

/**
 * A small snapshot of the last successful torrent list, written on every refresh (foreground
 * or background) and read by the home screen widgets. Holds torrent names and progress but
 * no credentials; it is unencrypted preference data.
 */
class WidgetStateRepository(private val context: Context) {

    private val serverKey = stringPreferencesKey("server_name")
    private val profileKey = stringPreferencesKey("profile_id")
    private val downloadingKey = intPreferencesKey("downloading_count")
    private val seedingKey = intPreferencesKey("seeding_count")
    private val pausedKey = intPreferencesKey("paused_count")
    private val downRateKey = longPreferencesKey("download_rate")
    private val upRateKey = longPreferencesKey("upload_rate")
    private val updatedKey = longPreferencesKey("updated_at")
    private val torrentsKey = stringPreferencesKey("torrents_snapshot")

    private val json = Json { ignoreUnknownKeys = true }

    val state: Flow<WidgetState> = context.widgetDataStore.data.map { prefs ->
        WidgetState(
            profileId = prefs[profileKey],
            serverName = prefs[serverKey],
            downloadingCount = prefs[downloadingKey] ?: 0,
            seedingCount = prefs[seedingKey] ?: 0,
            pausedCount = prefs[pausedKey] ?: 0,
            downloadRate = prefs[downRateKey] ?: 0,
            uploadRate = prefs[upRateKey] ?: 0,
            updatedAtMillis = prefs[updatedKey],
            torrents = prefs[torrentsKey]?.let {
                try {
                    json.decodeFromString<List<WidgetTorrent>>(it)
                } catch (e: Exception) {
                    emptyList()
                }
            } ?: emptyList(),
        )
    }

    suspend fun current(): WidgetState = state.first()

    @Volatile
    private var lastWritten: WidgetState? = null

    suspend fun update(profileId: String, serverName: String, torrents: List<Torrent>) {
        val snapshot = WidgetState(
            profileId = profileId,
            serverName = serverName,
            downloadingCount = torrents.count { it.status == TorrentStatus.DOWNLOADING },
            seedingCount = torrents.count { it.status == TorrentStatus.SEEDING },
            pausedCount = torrents.count { it.status == TorrentStatus.PAUSED },
            downloadRate = torrents.sumOf { it.downloadRate },
            uploadRate = torrents.sumOf { it.uploadRate },
            torrents = torrents
                .sortedWith(
                    compareByDescending<Torrent> { it.status.isActive }
                        .thenByDescending { it.downloadRate }
                        .thenBy { it.name.lowercase() }
                )
                .take(MAX_LIST_ROWS)
                .map {
                    WidgetTorrent(
                        id = it.id,
                        name = it.name,
                        progress = it.displayProgress,
                        status = it.status,
                        downloadRate = it.downloadRate,
                        uploadRate = it.uploadRate,
                        etaSeconds = it.etaSeconds,
                    )
                },
        )
        // The list refreshes every few seconds; skip the write when nothing changed
        if (snapshot == lastWritten) return
        lastWritten = snapshot
        context.widgetDataStore.edit { prefs ->
            prefs[serverKey] = snapshot.serverName!!
            prefs[profileKey] = snapshot.profileId!!
            prefs[downloadingKey] = snapshot.downloadingCount
            prefs[seedingKey] = snapshot.seedingCount
            prefs[pausedKey] = snapshot.pausedCount
            prefs[downRateKey] = snapshot.downloadRate
            prefs[upRateKey] = snapshot.uploadRate
            prefs[updatedKey] = System.currentTimeMillis()
            prefs[torrentsKey] = json.encodeToString(snapshot.torrents)
        }
        TransdroidWidget().updateAll(context)
        TransdroidListWidget().updateAll(context)
    }

    private companion object {
        const val MAX_LIST_ROWS = 10
    }
}
