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

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.transdroid.protocol.DaemonException

class HttpTest {

    @Test
    fun `response body is fully buffered on the IO dispatcher before returning`() = runTest {
        val server = MockWebServer()
        // A large, slowly trickled body cannot possibly sit in the initial socket read;
        // reading it lazily on the caller's thread is what threw NetworkOnMainThreadException
        val payload = "x".repeat(200_000)
        server.enqueue(MockResponse().setBody(payload).throttleBody(16_384, 5, TimeUnit.MILLISECONDS))
        server.start()

        val response = OkHttpClient().executeOnIo(Request.Builder().url(server.url("/")).build())
        // Once the call has returned, the network must no longer be needed at all
        server.shutdown()

        response.use {
            assertEquals(200, it.code)
            assertEquals(payload, it.body?.string())
        }
    }

    @Test
    fun `buffered body keeps its content type`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("{}").setHeader("Content-Type", "application/json; charset=utf-8"))
        server.start()

        val response = OkHttpClient().executeOnIo(Request.Builder().url(server.url("/")).build())
        server.shutdown()

        response.use {
            assertEquals("application", it.body?.contentType()?.type)
            assertEquals("json", it.body?.contentType()?.subtype)
        }
    }

    @Test
    fun `empty bodies and error statuses pass through`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(409).setHeader("X-Transmission-Session-Id", "abc"))
        server.start()

        val response = OkHttpClient().executeOnIo(Request.Builder().url(server.url("/")).build())
        server.shutdown()

        response.use {
            assertEquals(409, it.code)
            assertEquals("abc", it.header("X-Transmission-Session-Id"))
            assertEquals("", it.body?.string())
        }
    }

    @Test
    fun `unreachable server maps to connection error`() = runTest {
        val server = MockWebServer()
        server.start()
        val url = server.url("/")
        server.shutdown()

        try {
            OkHttpClient().executeOnIo(Request.Builder().url(url).build())
            fail("Expected DaemonException.Connection")
        } catch (expected: DaemonException.Connection) {
            assertTrue(expected.message!!.contains("Cannot reach"))
        }
    }
}
