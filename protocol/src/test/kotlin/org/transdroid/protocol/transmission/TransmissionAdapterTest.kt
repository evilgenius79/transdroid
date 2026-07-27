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
package org.transdroid.protocol.transmission

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
import org.transdroid.protocol.FilePriority
import org.transdroid.protocol.TorrentStatus

class TransmissionAdapterTest {

    private lateinit var server: MockWebServer
    private lateinit var adapter: TransmissionAdapter

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        adapter = TransmissionAdapter(
            DaemonConfig(
                type = DaemonType.TRANSMISSION,
                host = server.hostName,
                port = server.port,
                username = "user",
                password = "secret",
            ),
            OkHttpClient(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/transmission/$name")) { "Missing fixture $name" }
            .bufferedReader().readText()

    @Test
    fun `session id handshake retries once after 409`() = runTest {
        server.enqueue(MockResponse().setResponseCode(409).setHeader("X-Transmission-Session-Id", "abc123"))
        server.enqueue(
            MockResponse().setBody("""{"result":"success","arguments":{"version":"4.0.5","rpc-version":"17"}}""")
        )

        val version = adapter.testConnection()

        assertEquals("Transmission 4.0.5 (RPC v17)", version)
        val first = server.takeRequest()
        assertNull(first.getHeader("X-Transmission-Session-Id"))
        val second = server.takeRequest()
        assertEquals("abc123", second.getHeader("X-Transmission-Session-Id"))
        assertTrue(second.getHeader("Authorization")!!.startsWith("Basic "))
    }

    @Test
    fun `list torrents parses and normalizes fixture`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("torrent-get.json")))

        val torrents = adapter.listTorrents()

        assertEquals(4, torrents.size)
        val downloading = torrents[0]
        assertEquals("1", downloading.id)
        assertEquals("ubuntu-24.04.2-desktop-amd64.iso", downloading.name)
        assertEquals(TorrentStatus.DOWNLOADING, downloading.status)
        assertEquals(0.4266f, downloading.progress, 0.0001f)
        assertEquals(1250000L, downloading.downloadRate)
        assertEquals(1220L, downloading.etaSeconds)
        assertEquals(34, downloading.peersConnected)
        assertEquals(listOf("isos"), downloading.labels)

        val seeding = torrents[1]
        assertEquals(TorrentStatus.SEEDING, seeding.status)
        assertNull("eta -1 must normalize to null", seeding.etaSeconds)
        assertEquals(2.0f, seeding.ratio, 0.0001f)
        assertTrue(seeding.isFinished)

        val paused = torrents[2]
        assertEquals(TorrentStatus.PAUSED, paused.status)
        assertEquals("negative ratio must clamp to 0", 0f, paused.ratio, 0.0001f)

        val errored = torrents[3]
        assertEquals(TorrentStatus.ERROR, errored.status)
        assertEquals("Unregistered torrent", errored.error)
    }

    @Test
    fun `list files maps priorities and unwanted flag`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("torrent-get-files.json")))

        val files = adapter.listFiles("1")

        assertEquals(3, files.size)
        assertEquals(FilePriority.HIGH, files[0].priority)
        assertEquals(1f, files[0].progress, 0.0001f)
        assertEquals(FilePriority.NORMAL, files[1].priority)
        assertEquals(0.25f, files[1].progress, 0.0001f)
        assertEquals(FilePriority.OFF, files[2].priority)
    }

    @Test
    fun `add by url sends torrent-add with filename`() = runTest {
        server.enqueue(MockResponse().setBody("""{"result":"success","arguments":{}}"""))

        adapter.addByUrl("magnet:?xt=urn:btih:abcdef")

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"method\":\"torrent-add\""))
        assertTrue(body.contains("magnet:?xt=urn:btih:abcdef"))
    }

    @Test
    fun `remove with data sends delete-local-data`() = runTest {
        server.enqueue(MockResponse().setBody("""{"result":"success","arguments":{}}"""))

        adapter.remove("7", deleteData = true)

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"method\":\"torrent-remove\""))
        assertTrue(body.contains("\"ids\":[7]"))
        assertTrue(body.contains("\"delete-local-data\":true"))
    }

    @Test
    fun `401 maps to authentication error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        try {
            adapter.listTorrents()
            fail("Expected DaemonException.Authentication")
        } catch (expected: DaemonException.Authentication) {
        }
    }

    @Test
    fun `rpc-level failure maps to unexpected response`() = runTest {
        server.enqueue(MockResponse().setBody("""{"result":"invalid or corrupt torrent file","arguments":{}}"""))

        try {
            adapter.addByUrl("http://example.com/not-a-torrent")
            fail("Expected DaemonException.UnexpectedResponse")
        } catch (expected: DaemonException.UnexpectedResponse) {
            assertTrue(expected.message!!.contains("invalid or corrupt torrent file"))
        }
    }
}
