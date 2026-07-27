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
package org.transdroid

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.transdroid.ui.TransdroidApp
import org.transdroid.ui.theme.TransdroidTheme

class MainActivity : ComponentActivity() {

    private var pendingTorrentUrl by mutableStateOf<String?>(null)

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingTorrentUrl = extractTorrentUrl(intent)
        setContent {
            TransdroidTheme {
                val windowSizeClass = calculateWindowSizeClass(this)
                TransdroidApp(
                    useTwoPane = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Expanded,
                    pendingTorrentUrl = pendingTorrentUrl,
                    onPendingTorrentUrlConsumed = { pendingTorrentUrl = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        extractTorrentUrl(intent)?.let { pendingTorrentUrl = it }
    }

    /** Pulls a magnet link, torrent URL or .torrent content URI out of VIEW/SEND intents. */
    private fun extractTorrentUrl(intent: Intent?): String? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.dataString?.takeIf {
            it.startsWith("magnet:") || it.startsWith("content:") || it.startsWith("file:")
        }
        Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()?.takeIf {
            it.startsWith("magnet:") || it.startsWith("http://") || it.startsWith("https://")
        }
        else -> null
    }
}
