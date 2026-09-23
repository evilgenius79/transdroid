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

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.transdroid.R
import org.transdroid.protocol.TorrentStatus
import org.transdroid.ui.torrents.UiError

@Composable
fun UiError.message(): String = when (this) {
    is UiError.Connection -> stringResource(R.string.error_connection, host)
    is UiError.Authentication -> stringResource(R.string.error_authentication)
    is UiError.Ssl -> stringResource(R.string.error_ssl)
    is UiError.Unexpected ->
        detail ?: stringResource(R.string.error_unexpected)
}

/**
 * Likely causes for this error, as string resources, picked by the error kind and by
 * patterns (HTTP status codes included) in the exact underlying error text.
 */
fun UiError.causeHints(): List<Int> {
    val text = detail?.lowercase().orEmpty()
    val httpCode = Regex("http (\\d{3})").find(text)?.groupValues?.get(1)?.toIntOrNull()
    return when (this) {
        is UiError.Authentication -> buildList {
            if ("api key" in text) add(R.string.cause_api_key)
            add(R.string.cause_credentials)
            if ("blocked" in text || "ban" in text) add(R.string.cause_banned)
            add(R.string.cause_proxy_auth)
        }
        is UiError.Ssl -> listOf(R.string.cause_ssl_selfsigned, R.string.cause_ssl_pin)
        is UiError.Connection -> buildList {
            when {
                "unable to resolve host" in text || "unknownhost" in text -> add(R.string.cause_dns)
                "timeout" in text || "timed out" in text -> add(R.string.cause_timeout)
                "refused" in text -> add(R.string.cause_refused)
                "reset" in text || "closed" in text -> add(R.string.cause_reset)
                else -> {
                    add(R.string.cause_refused)
                    add(R.string.cause_timeout)
                }
            }
            add(R.string.cause_offline)
        }
        is UiError.Unexpected -> buildList {
            when {
                httpCode == 404 -> add(R.string.cause_http_404)
                httpCode == 405 || httpCode == 400 -> add(R.string.cause_http_400)
                httpCode == 429 -> add(R.string.cause_http_429)
                (httpCode ?: 0) in 500..504 -> add(R.string.cause_http_5xx)
                (httpCode ?: 0) in 520..530 -> add(R.string.cause_http_cloudflare)
                httpCode != null -> add(R.string.cause_http_other)
                "web page" in text || "portal" in text -> add(R.string.cause_portal)
                "parse" in text || "not a" in text -> add(R.string.cause_wrong_service)
                else -> add(R.string.cause_generic)
            }
            if (httpCode != null) add(R.string.cause_check_path)
        }
    }
}

@Composable
fun TorrentStatus.label(): String = stringResource(
    when (this) {
        TorrentStatus.DOWNLOADING -> R.string.status_downloading
        TorrentStatus.SEEDING -> R.string.status_seeding
        TorrentStatus.PAUSED -> R.string.status_paused
        TorrentStatus.CHECKING -> R.string.status_checking
        TorrentStatus.QUEUED -> R.string.status_queued
        TorrentStatus.ERROR -> R.string.status_error
        TorrentStatus.UNKNOWN -> R.string.status_unknown
    }
)

/**
 * The status label, with the magnet metadata-fetch phase surfaced explicitly — but only
 * while actually downloading; a paused or errored magnet keeps its real status visible.
 */
@Composable
fun org.transdroid.protocol.Torrent.statusLabel(): String =
    if (metadataProgress != null && status == TorrentStatus.DOWNLOADING) {
        stringResource(R.string.status_metadata)
    } else {
        status.label()
    }
