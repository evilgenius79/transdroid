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
import okhttp3.ResponseBody.Companion.toResponseBody
import org.transdroid.protocol.DaemonException

/**
 * Executes [request] on the IO dispatcher, translating transport failures to
 * [DaemonException.Connection] and TLS trust failures to [DaemonException.UntrustedServer].
 *
 * The returned response's body is fully buffered in memory: callers read it on their own
 * dispatcher (typically Main), and an OkHttp body that still streams from the socket
 * would then throw NetworkOnMainThreadException on Android — silently fine on a LAN,
 * where small replies already sit in the socket buffer, and broken over HTTPS through a
 * tunnel. The caller still owns closing the returned response.
 */
internal suspend fun OkHttpClient.executeOnIo(request: Request): Response =
    withContext(Dispatchers.IO) {
        try {
            val response = newCall(request).execute()
            val body = response.body ?: return@withContext response
            val bytes = try {
                body.bytes()
            } finally {
                body.close()
            }
            response.newBuilder().body(bytes.toResponseBody(body.contentType())).build()
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
