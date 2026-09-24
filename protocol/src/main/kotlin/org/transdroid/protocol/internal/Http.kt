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
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.transdroid.protocol.DaemonException

/** Largest reply accepted; bigger is a wrong URL (a download, a captive portal), not a daemon. */
internal const val MAX_RESPONSE_BYTES = 32L * 1024 * 1024

/**
 * Executes [request] off the calling thread, translating transport failures to
 * [DaemonException.Connection] and TLS trust failures to [DaemonException.UntrustedServer].
 * Cancelling the calling coroutine cancels the HTTP call.
 *
 * The returned response's body is fully buffered in memory: callers read it on their own
 * dispatcher (typically Main), and an OkHttp body that still streams from the socket
 * would then throw NetworkOnMainThreadException on Android — silently fine on a LAN,
 * where small replies already sit in the socket buffer, and broken over HTTPS through a
 * tunnel. The caller still owns closing the returned response.
 */
internal suspend fun OkHttpClient.executeOnIo(
    request: Request,
    maxBytes: Long = MAX_RESPONSE_BYTES,
): Response {
    val call = newCall(request)
    try {
        val response = call.await()
        val body = response.body ?: return response
        return withContext(Dispatchers.IO) {
            val bytes = try {
                body.source().use { source ->
                    val buffer = Buffer()
                    while (buffer.size <= maxBytes) {
                        // A blocking read cannot be interrupted; checking between chunks
                        // lets a cancelled poll drop the connection instead of draining it
                        if (!coroutineContext.isActiveOrCancelCall(call)) throw CancellationException()
                        if (source.read(buffer, CHUNK_BYTES) == -1L) break
                    }
                    if (buffer.size > maxBytes) {
                        call.cancel()
                        throw DaemonException.UnexpectedResponse(
                            "${request.url.host} answered with more than ${maxBytes / (1024 * 1024)} MB — not a torrent client reply"
                        )
                    }
                    buffer.readByteArray()
                }
            } finally {
                body.close()
            }
            response.newBuilder().body(bytes.toResponseBody(body.contentType())).build()
        }
    } catch (e: SSLException) {
        throw DaemonException.UntrustedServer(
            "TLS to ${request.url.host}:${request.url.port} failed; the certificate may be self-signed", e,
        )
    } catch (e: IOException) {
        throw DaemonException.Connection("Cannot reach ${request.url.host}:${request.url.port}", e)
    }
}

private const val CHUNK_BYTES = 64L * 1024

private fun kotlin.coroutines.CoroutineContext.isActiveOrCancelCall(call: Call): Boolean {
    return try {
        ensureActive()
        true
    } catch (e: CancellationException) {
        call.cancel()
        false
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, _, _ -> response.close() }
        }
    })
}

/** Joins an optional base path and an endpoint into a normalized absolute path. */
internal fun joinPath(basePath: String?, endpoint: String): String {
    val base = basePath.orEmpty().trim('/')
    val end = endpoint.trim('/')
    return if (base.isEmpty()) "/$end" else "/$base/$end"
}
