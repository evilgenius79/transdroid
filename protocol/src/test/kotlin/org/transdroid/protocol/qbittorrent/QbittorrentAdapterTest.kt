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
package org.transdroid.protocol.qbittorrent

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.transdroid.protocol.DaemonConfig
import org.transdroid.protocol.DaemonException
import org.transdroid.protocol.DaemonType
import org.transdroid.protocol.TorrentStatus
import org.transdroid.protocol.TrackerInfo

class QbittorrentAdapterTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun adapter(
        username: String? = "admin",
        password: String? = "adminadmin",
        apiKey: String? = null,
    ) = QbittorrentAdapter(
        DaemonConfig(
            type = DaemonType.QBITTORRENT,
            host = server.hostName,
            port = server.port,
            username = username,
            password = password,
            apiKey = apiKey,
        ),
        OkHttpClient(),
    )

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/qbittorrent/$name")) { "Missing fixture $name" }
            .bufferedReader().readText()

    private fun loginOk() = MockResponse()
        .setBody("Ok.")
        .setHeader("Set-Cookie", "SID=sessionIdHere; HttpOnly; path=/")

    @Test
    fun `logs in and passes session cookie to api calls`() = runTest {
        server.enqueue(loginOk())
        server.enqueue(MockResponse().setBody(fixture("torrents-info.json")))

        adapter().listTorrents()

        val login = server.takeRequest()
        assertEquals("/api/v2/auth/login", login.path)
        assertEquals("username=admin&password=adminadmin", login.body.readUtf8())
        val info = server.takeRequest()
        assertEquals("/api/v2/torrents/info", info.path)
        assertEquals("SID=sessionIdHere", info.getHeader("Cookie"))
    }

    @Test
    fun `accepts qbittorrent 5_1 QBT_SID cookies`() = runTest {
        server.enqueue(
            MockResponse()
                .setBody("Ok.")
                .setHeader("Set-Cookie", "QBT_SID_ab12cd=tokenValue; HttpOnly; path=/")
        )
        server.enqueue(MockResponse().setBody(fixture("torrents-info.json")))

        adapter().listTorrents()

        server.takeRequest() // login
        assertEquals("QBT_SID_ab12cd=tokenValue", server.takeRequest().getHeader("Cookie"))
    }

    @Test
    fun `rejected login maps to authentication error and is not retried on the next poll`() = runTest {
        server.enqueue(MockResponse().setBody("Fails."))
        val shared = adapter()

        try {
            shared.listTorrents()
            fail("Expected DaemonException.Authentication")
        } catch (expected: DaemonException.Authentication) {
        }
        try {
            shared.listTorrents()
            fail("Expected DaemonException.Authentication")
        } catch (expected: DaemonException.Authentication) {
        }

        assertEquals("re-sending bad credentials every poll gets the address banned", 1, server.requestCount)
    }

    @Test
    fun `401 is explained as a host header rejection`() = runTest {
        server.enqueue(loginOk())
        server.enqueue(MockResponse().setResponseCode(401))

        try {
            adapter().listTorrents()
            fail("Expected DaemonException.Authentication")
        } catch (expected: DaemonException.Authentication) {
            assertTrue(expected.message!!.contains("Host header"))
        }
    }

    @Test
    fun `list torrents parses and normalizes fixture`() = runTest {
        server.enqueue(loginOk())
        server.enqueue(MockResponse().setBody(fixture("torrents-info.json")))

        val torrents = adapter().listTorrents()

        assertEquals(4, torrents.size)
        val downloading = torrents[0]
        assertEquals("8c212779b4abde7c6bc608063a0d008b7e40ce32", downloading.id)
        assertEquals(TorrentStatus.DOWNLOADING, downloading.status)
        assertEquals(1220L, downloading.etaSeconds)
        assertEquals("seeds and leeches sum to connected peers", 34, downloading.peersConnected)
        assertEquals("category maps to a label", listOf("linux"), downloading.labels)

        val seeding = torrents[1]
        assertEquals(TorrentStatus.SEEDING, seeding.status)
        assertNull("eta 8640000 must normalize to null", seeding.etaSeconds)

        assertEquals("qBittorrent 5 stoppedDL state", TorrentStatus.PAUSED, torrents[2].status)
        assertEquals(TorrentStatus.ERROR, torrents[3].status)
    }

    @Test
    fun `anonymous config skips login`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("torrents-info.json")))

        val torrents = adapter(username = null, password = null).listTorrents()

        assertEquals(4, torrents.size)
        assertEquals("/api/v2/torrents/info", server.takeRequest().path)
    }

    @Test
    fun `api key skips login and sends bearer header`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("torrents-info.json")))

        val torrents = adapter(apiKey = "qbt_abcdefghijklmnopqrstuvwxyz12").listTorrents()

        assertEquals(4, torrents.size)
        val request = server.takeRequest()
        assertEquals("no login call must precede the api call", "/api/v2/torrents/info", request.path)
        assertEquals("Bearer qbt_abcdefghijklmnopqrstuvwxyz12", request.getHeader("Authorization"))
        assertNull("cookie auth must not be mixed in", request.getHeader("Cookie"))
    }

    @Test
    fun `api key takes precedence over username and password`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("torrents-info.json")))

        adapter(username = "admin", password = "adminadmin", apiKey = "qbt_key").listTorrents()

        assertEquals(1, server.requestCount)
        assertEquals("Bearer qbt_key", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `rejected api key maps to authentication error without login retry`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))

        try {
            adapter(apiKey = "qbt_expiredKey").listTorrents()
            fail("Expected DaemonException.Authentication")
        } catch (expected: DaemonException.Authentication) {
            assertTrue("message should name the API key", expected.message!!.contains("API key"))
        }
        assertEquals("a bad key must not trigger a cookie login retry", 1, server.requestCount)
    }

    @Test
    fun `blank api key falls back to cookie login`() = runTest {
        server.enqueue(loginOk())
        server.enqueue(MockResponse().setBody(fixture("torrents-info.json")))

        adapter(apiKey = "  ").listTorrents()

        assertEquals("/api/v2/auth/login", server.takeRequest().path)
    }

    @Test
    fun `pause falls back to legacy endpoint on 404`() = runTest {
        server.enqueue(loginOk())
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setBody(""))

        adapter().pause("abcdef")

        server.takeRequest() // login
        assertEquals("/api/v2/torrents/stop", server.takeRequest().path)
        val fallback = server.takeRequest()
        assertEquals("/api/v2/torrents/pause", fallback.path)
        assertEquals("hashes=abcdef", fallback.body.readUtf8())
    }

    @Test
    fun `expired session re-authenticates once`() = runTest {
        server.enqueue(loginOk())
        server.enqueue(MockResponse().setResponseCode(403))
        server.enqueue(loginOk())
        server.enqueue(MockResponse().setBody(fixture("torrents-info.json")))

        val torrents = adapter().listTorrents()

        assertEquals(4, torrents.size)
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `add paused sends both pause field spellings`() = runTest {
        server.enqueue(loginOk())
        server.enqueue(MockResponse().setBody("Ok."))

        adapter().addByUrl("magnet:?xt=urn:btih:abcdef", startPaused = true)

        server.takeRequest() // login
        val add = server.takeRequest().body.readUtf8()
        assertTrue("4.x field", add.contains("paused=true"))
        assertTrue("5.x field", add.contains("stopped=true"))
    }

    @Test
    fun `failed add is surfaced as an error`() = runTest {
        server.enqueue(loginOk())
        server.enqueue(MockResponse().setBody("Fails."))

        try {
            adapter().addByUrl("http://example.com/broken.torrent")
            fail("Expected DaemonException.UnexpectedResponse")
        } catch (expected: DaemonException.UnexpectedResponse) {
        }
    }

    @Test
    fun `tracker list hides dht pseudo entries and maps statuses`() = runTest {
        server.enqueue(loginOk())
        server.enqueue(
            MockResponse().setBody(
                """[{"url":"** [DHT] **","status":2,"msg":""},
                    {"url":"** [PeX] **","status":2,"msg":""},
                    {"url":"https://tracker.example.org/announce","status":2,"msg":""},
                    {"url":"https://dead.example.net/announce","status":4,"msg":"Connection failed"}]"""
            )
        )

        val trackers = adapter().listTrackers("abcdef")

        server.takeRequest() // login
        assertEquals("/api/v2/torrents/trackers?hash=abcdef", server.takeRequest().path)
        assertEquals(2, trackers.size)
        assertEquals("Working", trackers[0].status)
        assertEquals("Connection failed", trackers[1].status)
    }

    @Test
    fun `remove tracker posts hash and url`() = runTest {
        server.enqueue(loginOk())
        server.enqueue(MockResponse().setBody(""))

        adapter().removeTracker("abcdef", TrackerInfo(id = "x", url = "https://tracker.example.org/announce"))

        server.takeRequest() // login
        val request = server.takeRequest()
        assertEquals("/api/v2/torrents/removeTrackers", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("hash=abcdef"))
        assertTrue(body.contains("urls=https%3A%2F%2Ftracker.example.org%2Fannounce"))
    }

    @Test
    fun `reannounce posts hashes`() = runTest {
        server.enqueue(loginOk())
        server.enqueue(MockResponse().setBody(""))

        adapter().forceReannounce("abcdef")

        server.takeRequest() // login
        val request = server.takeRequest()
        assertEquals("/api/v2/torrents/reannounce", request.path)
        assertEquals("hashes=abcdef", request.body.readUtf8())
    }

    @Test
    fun `remove sends hashes and deleteFiles`() = runTest {
        server.enqueue(loginOk())
        server.enqueue(MockResponse().setBody(""))

        adapter().remove("abcdef", deleteData = true)

        server.takeRequest() // login
        val delete = server.takeRequest()
        assertEquals("/api/v2/torrents/delete", delete.path)
        assertEquals("hashes=abcdef&deleteFiles=true", delete.body.readUtf8())
    }
}
