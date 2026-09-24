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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import org.transdroid.MainActivity
import org.transdroid.R
import org.transdroid.appContainer
import org.transdroid.protocol.TorrentStatus
import org.transdroid.util.formatEta
import org.transdroid.util.formatSpeed

/**
 * Home screen widget: the most active torrents with progress, speeds and a play/pause
 * button per row. Tapping elsewhere opens the app; the header has a manual refresh.
 */
class TransdroidListWidget : GlanceAppWidget() {

    /** Everything a row needs, resolved with context strings before composition. */
    private data class RowUi(
        val id: String,
        val name: String,
        val percentText: String,
        val detailText: String,
        val progress: Float,
        val status: TorrentStatus,
        val paused: Boolean,
        val showToggle: Boolean,
    )

    private data class WidgetUi(
        val title: String,
        val speeds: String?,
        val emptyText: String?,
        val rows: List<RowUi>,
        val refreshDescription: String,
        val startDescription: String,
        val pauseDescription: String,
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        refreshSnapshotIfStale(context, STALE_AFTER_MILLIS)
        val repository = context.appContainer.widgetStateRepository
        val initial = repository.current()
        // Glance keeps this session alive for a while after rendering; collecting the
        // snapshot flow (rather than reading it once) makes every write show up at once
        provideContent {
            val state by repository.state.collectAsState(initial)
            GlanceTheme {
                WidgetContent(buildUi(context, state))
            }
        }
    }

    private fun buildUi(context: Context, state: WidgetState): WidgetUi {
        val rows = state.torrents.map { torrent ->
            val active = torrent.downloadRate > 0 || torrent.uploadRate > 0
            val detail = if (active) {
                buildString {
                    append("↓ ${formatSpeed(torrent.downloadRate)} · ↑ ${formatSpeed(torrent.uploadRate)}")
                    formatEta(torrent.etaSeconds)?.let { append(" · $it") }
                }
            } else {
                context.getString(torrent.status.labelRes())
            }
            RowUi(
                id = torrent.id,
                name = torrent.name,
                percentText = "${(torrent.progress * 100).toInt()}%",
                detailText = detail,
                progress = torrent.progress,
                status = torrent.status,
                paused = torrent.status.isStopped,
                showToggle = torrent.id.isNotBlank() && torrent.status in TOGGLABLE_STATUSES,
            )
        }
        return WidgetUi(
            title = state.serverName ?: context.getString(R.string.app_name),
            speeds = state.updatedAtMillis?.let {
                "↓ ${formatSpeed(state.downloadRate)}  ↑ ${formatSpeed(state.uploadRate)}"
            },
            emptyText = when {
                state.updatedAtMillis == null -> context.getString(R.string.widget_no_data)
                state.torrents.isEmpty() -> context.getString(R.string.torrents_empty)
                else -> null
            },
            rows = rows,
            refreshDescription = context.getString(R.string.torrents_refresh),
            startDescription = context.getString(R.string.details_start),
            pauseDescription = context.getString(R.string.details_pause),
        )
    }

    @Composable
    private fun WidgetContent(ui: WidgetUi) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .appWidgetBackground()
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(16.dp)
                .padding(12.dp),
        ) {
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = GlanceModifier.defaultWeight().clickable(actionStartActivity<MainActivity>())) {
                    Text(
                        text = ui.title,
                        style = TextStyle(
                            color = GlanceTheme.colors.primary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                        ),
                    )
                    ui.speeds?.let {
                        Text(
                            text = it,
                            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
                        )
                    }
                }
                Image(
                    provider = ImageProvider(R.drawable.ic_widget_refresh),
                    contentDescription = ui.refreshDescription,
                    colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
                    modifier = GlanceModifier
                        .size(28.dp)
                        .padding(4.dp)
                        .clickable(actionRunCallback<RefreshWidgetAction>()),
                )
            }
            Spacer(GlanceModifier.height(6.dp))
            val emptyText = ui.emptyText
            if (emptyText != null) {
                Text(
                    text = emptyText,
                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp),
                    modifier = GlanceModifier.clickable(actionStartActivity<MainActivity>()),
                )
            } else {
                LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                    items(ui.rows) { row ->
                        TorrentRow(row, ui)
                    }
                }
            }
        }
    }

    @Composable
    private fun TorrentRow(row: RowUi, ui: WidgetUi) {
        Row(
            modifier = GlanceModifier.fillMaxWidth().padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = GlanceModifier.defaultWeight().clickable(actionStartActivity<MainActivity>())) {
                Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = row.name,
                        style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp),
                        maxLines = 1,
                        modifier = GlanceModifier.defaultWeight(),
                    )
                    Text(
                        text = " ${row.percentText}",
                        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
                    )
                }
                Spacer(GlanceModifier.height(2.dp))
                LinearProgressIndicator(
                    progress = row.progress,
                    modifier = GlanceModifier.fillMaxWidth().height(4.dp),
                    color = row.status.widgetColor(),
                    backgroundColor = GlanceTheme.colors.surfaceVariant,
                )
                Spacer(GlanceModifier.height(2.dp))
                Text(
                    text = row.detailText,
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
                    maxLines = 1,
                )
            }
            if (row.showToggle) {
                Image(
                    provider = ImageProvider(
                        if (row.paused) R.drawable.ic_widget_play else R.drawable.ic_widget_pause
                    ),
                    contentDescription = if (row.paused) ui.startDescription else ui.pauseDescription,
                    colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
                    modifier = GlanceModifier
                        .size(32.dp)
                        .padding(6.dp)
                        .clickable(
                            actionRunCallback<ToggleTorrentAction>(
                                actionParametersOf(
                                    TorrentIdParam to row.id,
                                    TorrentPausedParam to row.paused,
                                )
                            )
                        ),
                )
            }
        }
    }

    /** Mirrors the in-app status accent colors (fixed values; Glance has no app theme). */
    private fun TorrentStatus.widgetColor(): androidx.glance.unit.ColorProvider = when (this) {
        TorrentStatus.DOWNLOADING -> ColorProvider(Color(0xFF1E88E5), Color(0xFF1E88E5))
        TorrentStatus.SEEDING -> ColorProvider(Color(0xFF66BB6A), Color(0xFF66BB6A))
        TorrentStatus.PAUSED -> ColorProvider(Color(0xFFAB47BC), Color(0xFFAB47BC))
        TorrentStatus.ERROR -> ColorProvider(Color(0xFFE57373), Color(0xFFE57373))
        else -> ColorProvider(Color(0xFF9E9E9E), Color(0xFF9E9E9E))
    }

    private fun TorrentStatus.labelRes(): Int = when (this) {
        TorrentStatus.DOWNLOADING -> R.string.status_downloading
        TorrentStatus.SEEDING -> R.string.status_seeding
        TorrentStatus.PAUSED -> R.string.status_paused
        TorrentStatus.CHECKING -> R.string.status_checking
        TorrentStatus.QUEUED -> R.string.status_queued
        TorrentStatus.ERROR -> R.string.status_error
        TorrentStatus.UNKNOWN -> R.string.status_unknown
    }

    private companion object {
        val TOGGLABLE_STATUSES = setOf(
            TorrentStatus.DOWNLOADING,
            TorrentStatus.SEEDING,
            TorrentStatus.QUEUED,
            TorrentStatus.PAUSED,
            TorrentStatus.ERROR,
        )
        const val STALE_AFTER_MILLIS = 10L * 60 * 1000
    }
}

class TransdroidListWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TransdroidListWidget()
}
