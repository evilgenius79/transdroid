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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import org.transdroid.MainActivity
import org.transdroid.R
import org.transdroid.appContainer
import org.transdroid.util.formatSpeed

/** Home screen widget: torrent counts and total transfer rates of the active server. */
class TransdroidWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = context.appContainer.widgetStateRepository.current()
        val strings = WidgetStrings(
            appName = context.getString(R.string.app_name),
            noData = context.getString(R.string.widget_no_data),
        )
        provideContent {
            GlanceTheme {
                WidgetContent(state, strings)
            }
        }
    }

    private data class WidgetStrings(val appName: String, val noData: String)

    @Composable
    private fun WidgetContent(state: WidgetState, strings: WidgetStrings) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .appWidgetBackground()
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(16.dp)
                .padding(12.dp)
                .clickable(actionStartActivity<MainActivity>()),
        ) {
            Text(
                text = state.serverName ?: strings.appName,
                style = TextStyle(
                    color = GlanceTheme.colors.primary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                ),
            )
            Spacer(GlanceModifier.height(6.dp))
            if (state.updatedAtMillis == null) {
                Text(
                    text = strings.noData,
                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp),
                )
            } else {
                Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Stat("↓ ${state.downloadingCount}")
                    Stat("↑ ${state.seedingCount}")
                    Stat("⏸ ${state.pausedCount}")
                }
                Spacer(GlanceModifier.height(4.dp))
                Text(
                    text = "↓ ${formatSpeed(state.downloadRate)}   ↑ ${formatSpeed(state.uploadRate)}",
                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp),
                )
            }
        }
    }

    @Composable
    private fun Stat(text: String) {
        Text(
            text = text,
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp),
            modifier = GlanceModifier.padding(end = 12.dp),
        )
    }

}

class TransdroidWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TransdroidWidget()
}
