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
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.transdroid.data.ThemeMode
import org.transdroid.ui.TransdroidApp
import org.transdroid.ui.theme.TransdroidTheme

class MainActivity : ComponentActivity() {

    private var pendingTorrentUrl by mutableStateOf<String?>(null)

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only read the launch intent on a fresh start; on recreation, restore the (possibly
        // already consumed) pending value so an added torrent isn't offered again on rotate
        pendingTorrentUrl = if (savedInstanceState == null) {
            extractTorrentUrl(intent)
        } else {
            savedInstanceState.getString(STATE_PENDING_TORRENT_URL)
        }
        // A synchronous first read (bounded, a few ms from disk) so the first frame is
        // already in the chosen theme instead of flashing the system one
        val initialThemeMode = runBlocking {
            withTimeoutOrNull(THEME_READ_TIMEOUT_MILLIS) { appContainer.settingsRepository.themeMode.first() }
        } ?: ThemeMode.SYSTEM
        setContent {
            val themeMode by appContainer.settingsRepository.themeMode
                .collectAsStateWithLifecycle(initialValue = initialThemeMode)
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // The default edge-to-edge style picks status/navigation bar icon colors from
            // the *system* dark mode; with the in-app override they must follow our theme,
            // or dark icons land on a dark bar (and vice versa)
            LaunchedEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = if (darkTheme) {
                        SystemBarStyle.dark(Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    },
                    navigationBarStyle = if (darkTheme) {
                        SystemBarStyle.dark(Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    },
                )
            }
            TransdroidTheme(darkTheme = darkTheme) {
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PENDING_TORRENT_URL, pendingTorrentUrl)
    }

    /** Pulls a magnet link, torrent URL or .torrent content URI out of VIEW/SEND intents. */
    private fun extractTorrentUrl(intent: Intent?): String? {
        // A task relaunched from Recents replays its original intent; the torrent it
        // carried was already offered once
        if (intent == null || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
        return when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString?.takeIf {
                it.startsWith("magnet:") || it.startsWith("content:") || it.startsWith("file:")
            }
            // Shared text is commonly "Title\nhttps://…"; take the first link wherever it sits
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
                ?.let { LINK_PATTERN.find(it)?.value }
            else -> null
        }
    }

    private companion object {
        const val STATE_PENDING_TORRENT_URL = "pending_torrent_url"
        const val THEME_READ_TIMEOUT_MILLIS = 250L
        val LINK_PATTERN = Regex("""(magnet:\?[^\s]+|https?://[^\s]+)""")
    }
}
