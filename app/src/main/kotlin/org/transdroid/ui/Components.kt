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
package org.transdroid.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.transdroid.R
import org.transdroid.ui.torrents.UiError

/**
 * A classic flat, edge-to-edge progress bar in the legacy Transdroid 2 style: no rounded
 * caps, no track gap, no stop dot — just a straight filled line on its track.
 */
@Composable
fun FlatProgressBar(progress: Float, color: Color, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = { progress },
        color = color,
        strokeCap = StrokeCap.Butt,
        gapSize = 0.dp,
        drawStopIndicator = {},
        modifier = modifier,
    )
}

/**
 * Shows the exact underlying server error (HTTP codes and all) with likely causes, so
 * a failure like "returned HTTP 502" can be diagnosed instead of guessed at.
 */
@Composable
fun ErrorDetailsDialog(error: UiError, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.error_details_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(error.message(), style = MaterialTheme.typography.bodyMedium)
                error.detail?.let { detail ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.error_details_exact),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                val causes = error.causeHints()
                if (causes.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.error_details_causes),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(4.dp))
                    causes.forEach { cause ->
                        Row(Modifier.padding(vertical = 2.dp)) {
                            Text("•  ", style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(cause), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_help_close)) }
        },
    )
}
