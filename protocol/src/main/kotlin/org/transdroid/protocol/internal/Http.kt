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
package org.transdroid.protocol.internal

import java.io.IOException
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.transdroid.protocol.DaemonException

/**
 * Executes [request] on the IO dispatcher, translating transport failures to
 * [DaemonException.Connection] and TLS trust failures to [DaemonException.UntrustedServer].
 * The caller owns closing the returned response.
 */
internal suspend fun OkHttpClient.executeOnIo(request: Request): Response =
    withContext(Dispatchers.IO) {
        try {
            newCall(request).execute()
        } catch (e: SSLException) {
            throw DaemonException.UntrustedServer(
                "TLS to ${request.url.host}:${request.url.port} failed; the certificate may be self-signed", e,
            )
        } catch (e: IOException) {
            throw DaemonException.Connection("Cannot reach ${request.url.host}:${request.url.port}", e)
        }
    }

/** Joins an optional base path and an endpoint into a normalized absolute path. */
internal fun joinPath(basePath: String?, endpoint: String): String {
    val base = basePath.orEmpty().trim('/')
    val end = endpoint.trim('/')
    return if (base.isEmpty()) "/$end" else "/$base/$end"
}
