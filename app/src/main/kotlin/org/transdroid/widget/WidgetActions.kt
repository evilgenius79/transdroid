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
import android.util.Log
import android.widget.Toast
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.transdroid.R
import org.transdroid.appContainer

private const val TAG = "TransdroidWidget"

val TorrentIdParam = ActionParameters.Key<String>("torrent_id")
val TorrentPausedParam = ActionParameters.Key<Boolean>("torrent_paused")

/**
 * Widgets have no room for error banners, so actions confirm receipt with a toast and
 * report failures the same way instead of silently doing nothing.
 */
private suspend fun toast(context: Context, text: String) = withContext(Dispatchers.Main) {
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}

/** Refreshes the widget snapshot straight from the active daemon. */
class RefreshWidgetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val failure = refreshSnapshot(context)
        if (failure != null) {
            toast(context, context.getString(R.string.widget_action_failed, failure))
        }
    }
}

/** Starts a paused torrent or pauses a running one, then refreshes the snapshot. */
class ToggleTorrentAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val torrentId = parameters[TorrentIdParam] ?: return
        val paused = parameters[TorrentPausedParam] ?: return
        val container = context.appContainer
        val profile = container.activeProfile.first()
        if (profile == null) {
            toast(context, context.getString(R.string.widget_no_server))
            return
        }
        toast(
            context,
            context.getString(if (paused) R.string.widget_action_starting else R.string.widget_action_pausing),
        )
        try {
            val adapter = container.adapterFor(profile)
            if (paused) adapter.start(torrentId) else adapter.pause(torrentId)
        } catch (e: Exception) {
            Log.e(TAG, "Widget toggle for $torrentId failed", e)
            toast(context, context.getString(R.string.widget_action_failed, e.message ?: e.javaClass.simpleName))
        }
        refreshSnapshot(context)
    }
}

/**
 * Refreshes the snapshot when it is older than [maxAgeMillis]; used from the widgets'
 * periodic system update so the home screen stays current even when the app is never
 * opened and finished-notifications (the only other background fetch) are off.
 */
internal suspend fun refreshSnapshotIfStale(context: Context, maxAgeMillis: Long) {
    val updatedAt = context.appContainer.widgetStateRepository.current().updatedAtMillis ?: 0L
    if (System.currentTimeMillis() - updatedAt > maxAgeMillis) refreshSnapshot(context)
}

/** Returns null on success, or a short failure description. */
private suspend fun refreshSnapshot(context: Context): String? {
    val container = context.appContainer
    val profile = container.activeProfile.first()
        ?: return context.getString(R.string.widget_no_data)
    return try {
        val torrents = container.adapterFor(profile).listTorrents()
        container.widgetStateRepository.update(profile.displayName, torrents)
        null
    } catch (e: Exception) {
        Log.e(TAG, "Widget refresh failed", e)
        e.message ?: e.javaClass.simpleName
    }
}
