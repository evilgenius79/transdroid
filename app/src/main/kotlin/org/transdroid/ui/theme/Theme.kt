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
package org.transdroid.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import org.transdroid.protocol.TorrentStatus

// The classic Transdroid grey-green identity, carried over from v2
val Green = Color(0xFF388E3C)
val GreenLight = Color(0xFF66BB6A)
val GreenPale = Color(0xFFA5D6A7)
val GreenDark = Color(0xFF1B5E20)
val LedGreen = Color(0xFF7DBB21)
val Red = Color(0xFFC62828)

// Status accents (shared between themes)
val StatusDownloading = Color(0xFF1E88E5)
val StatusSeeding = Color(0xFF66BB6A)
val StatusPaused = Color(0xFFAB47BC)
val StatusError = Color(0xFFE57373)
val StatusOther = Color(0xFF9E9E9E)

private val LightColors = lightColorScheme(
    primary = Green,
    onPrimary = Color.White,
    primaryContainer = GreenPale,
    onPrimaryContainer = GreenDark,
    secondary = GreenDark,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD7E8D8),
    onSecondaryContainer = GreenDark,
    tertiary = LedGreen,
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF1A1C1A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1C1A),
    surfaceVariant = Color(0xFFE4E9E3),
    onSurfaceVariant = Color(0xFF44483F),
    outline = Color(0xFF75796F),
    error = Red,
    onError = Color.White,
)

// Grey-green tinted dark theme: dark greys with a hint of green, green accents
private val DarkColors = darkColorScheme(
    primary = GreenPale,
    onPrimary = Color(0xFF0E3311),
    primaryContainer = Color(0xFF2E5B31),
    onPrimaryContainer = Color(0xFFC6EFC8),
    secondary = Color(0xFFB9CCB8),
    onSecondary = Color(0xFF243425),
    secondaryContainer = Color(0xFF3A4B3A),
    onSecondaryContainer = Color(0xFFD5E8D4),
    tertiary = LedGreen,
    background = Color(0xFF0F1410),
    onBackground = Color(0xFFE0E4DD),
    surface = Color(0xFF151A16),
    onSurface = Color(0xFFE0E4DD),
    surfaceVariant = Color(0xFF232A24),
    onSurfaceVariant = Color(0xFFC2C9BD),
    outline = Color(0xFF8C9388),
    error = Color(0xFFEF9A9A),
    onError = Color(0xFF400C0C),
)

val TorrentStatus.accentColor: Color
    get() = when (this) {
        TorrentStatus.DOWNLOADING -> StatusDownloading
        TorrentStatus.SEEDING -> StatusSeeding
        TorrentStatus.PAUSED -> StatusPaused
        TorrentStatus.ERROR -> StatusError
        TorrentStatus.CHECKING, TorrentStatus.QUEUED, TorrentStatus.UNKNOWN -> StatusOther
    }

@Composable
fun TransdroidTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
