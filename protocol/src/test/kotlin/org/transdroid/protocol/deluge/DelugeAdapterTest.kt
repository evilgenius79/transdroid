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
package org.transdroid.protocol.deluge

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
import org.transdroid.protocol.TrackerInfo

class DelugeAdapterTest {

    private lateinit var server: MockWebServer
    private lateinit var adapter: DelugeAdapter

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        adapter = DelugeAdapter(
            DaemonConfig(
                type = DaemonType.DELUGE,
                host = server.hostName,
                port = server.port,
                password = "deluge",
            ),
            OkHttpClient(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/deluge/$name")) { "Missing fixture $name" }
            .bufferedReader().readText()

    private fun loginOk() = MockResponse()
        .setBody("""{"result": true, "error": null, "id": 1}""")
        .setHeader("Set-Cookie", "_session_id=abc123; Path=/json")

    @Test
    fun `logs in with password and passes session cookie`() = runTest {
        server.enqueue(loginOk())
        server.enqueue(MockResponse().setBody(fixture("torrents-status.json")))

        adapter.listTorrents()

        val login = server.takeRequest()
        assertEquals("/json", login.path)
        val loginBody = login.body.readUtf8()
        assertTrue(loginBody.contains("\"method\":\"auth.login\""))
        assertTrue(loginBody.contains("\"deluge\""))
        val status = server.takeRequest()
        assertEquals("_session_id=abc123", status.getHeader("Cookie"))
        assertTrue(status.body.readUtf8().contains("core.get_torrents_status"))
    }

    @Test
    fun `rejected login maps to authentication error`() = runTest {
        server.enqueue(MockResponse().setBody("""{"result": false, "error": null, "id": 1}"""))

        try {
            adapter.listTorrents()
            fail("Expected DaemonException.Authentication")
        } catch (expected: DaemonException.Authentication) {
        }
    }

    @Test
    fun `list torrents parses and normalizes fixture`() = runTest {
        server.enqueue(loginOk())
        server.enqueue(MockResponse().setBody(fixture("torrents-status.json")))

        val torrents = adapter.listTorrents().sortedByDescending { it.addedTimestamp }

        assertEquals(4, torrents.size)
        val downloading = torrents[0]
        assertEquals("8c212779b4abde7c6bc608063a0d008b7e40ce32", downloading.id)
        assertEquals(TorrentStatus.DOWNLOADING, downloading.status)
        assertEquals("percent scale normalized to 0..1", 0.4266f, downloading.progress, 0.001f)
        assertEquals(1220L, downloading.etaSeconds)
        assertEquals(34, downloading.peersConnected)
        assertEquals("Label plugin value maps to a label", listOf("linux-isos"), downloading.labels)

        val seeding = torrents[1]
        assertEquals(TorrentStatus.SEEDING, seeding.status)
        assertNull("eta 0 must normalize to null", seeding.etaSeconds)

        val paused = torrents[2]
        assertEquals(TorrentStatus.PAUSED, paused.status)
        assertEquals("negative ratio must clamp to 0", 0f, paused.ratio, 0.001f)

        val errored = torrents[3]
        assertEquals(TorrentStatus.ERROR, errored.status)
        assertEquals("Files missing", errored.error)
    }

    @Test
    fun `expired session re-authenticates once`() = runTest {
        server.enqueue(loginOk())
        server.enqueue(
            MockResponse().setBody("""{"result": null, "error": {"message": "Not authenticated", "code": 1}, "id": 2}""")
        )
        server.enqueue(loginOk())
        server.enqueue(MockResponse().setBody(fixture("torrents-status.json")))

        val torrents = adapter.listTorrents()

        assertEquals(4, torrents.size)
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `pause and resume use list parameters`() = runTest {
        server.enqueue(loginOk())
        server.enqueue(MockResponse().setBody("""{"result": null, "error": null, "id": 2}"""))
        server.enqueue(MockResponse().setBody("""{"result": null, "error": null, "id": 3}"""))

        adapter.pause("abcdef")
        adapter.start("abcdef")

        server.takeRequest() // login
        val pause = server.takeRequest().body.readUtf8()
        assertTrue(pause.contains("\"method\":\"core.pause_torrent\""))
        assertTrue(pause.contains("[[\"abcdef\"]]"))
        val resume = server.takeRequest().body.readUtf8()
        assertTrue(resume.contains("\"method\":\"core.resume_torrent\""))
    }

    @Test
    fun `remove sends id and delete flag`() = runTest {
        server.enqueue(loginOk())
        server.enqueue(MockResponse().setBody("""{"result": true, "error": null, "id": 2}"""))

        adapter.remove("abcdef", deleteData = true)

        server.takeRequest() // login
        val remove = server.takeRequest().body.readUtf8()
        assertTrue(remove.contains("\"method\":\"core.remove_torrent\""))
        assertTrue(remove.contains("[\"abcdef\",true]"))
    }

    @Test
    fun `deluge 1_3 file priorities use the legacy scale`() = runTest {
        server.enqueue(loginOk())
        server.enqueue(
            MockResponse().setBody(
                """{"result": {"files": [{"index": 0, "path": "a", "size": 10}, {"index": 1, "path": "b", "size": 10},
                    {"index": 2, "path": "c", "size": 10}], "file_progress": [1, 0, 0],
                    "file_priorities": [1, 2, 0]}, "error": null, "id": 2}"""
            )
        )
        // usesLegacyPriorities: get_version unknown on 1.3, daemon.info answers
        server.enqueue(MockResponse().setBody("""{"result": null, "error": {"message": "Unknown method", "code": 2}, "id": 3}"""))
        server.enqueue(MockResponse().setBody("""{"result": "1.3.15", "error": null, "id": 4}"""))

        val files = adapter.listFiles("abc")

        assertEquals("1 is Normal on 1.3, not Low", FilePriority.NORMAL, files[0].priority)
        assertEquals("2 is High on 1.3", FilePriority.HIGH, files[1].priority)
        assertEquals(FilePriority.OFF, files[2].priority)

        // Writing NORMAL must send 1 on 1.3 (4 would read as High there)
        server.enqueue(MockResponse().setBody("""{"result": {"file_priorities": [1, 2, 0]}, "error": null, "id": 5}"""))
        server.enqueue(MockResponse().setBody("""{"result": null, "error": null, "id": 6}"""))
        adapter.setFilePriority("abc", 1, FilePriority.NORMAL)
        repeat(5) { server.takeRequest() }
        assertTrue(server.takeRequest().body.readUtf8().contains("\"file_priorities\":[1,1,0]"))
    }

    @Test
    fun `test connection fails when the web ui has no daemon`() = runTest {
        server.enqueue(loginOk())
        server.enqueue(MockResponse().setBody("""{"result": false, "error": null, "id": 2}"""))

        try {
            adapter.testConnection()
            fail("Expected DaemonException.UnexpectedResponse")
        } catch (expected: DaemonException.UnexpectedResponse) {
            assertTrue(expected.message!!.contains("not connected to its daemon"))
        }
    }

    @Test
    fun `remove tracker writes back the remaining tracker list`() = runTest {
        server.enqueue(loginOk())
        server.enqueue(
            MockResponse().setBody(
                """{"result": {"trackers": [
                    {"url": "https://a.example.org/announce", "tier": 0},
                    {"url": "https://b.example.net/announce", "tier": 1}]}, "error": null, "id": 2}"""
            )
        )
        server.enqueue(MockResponse().setBody("""{"result": null, "error": null, "id": 3}"""))

        adapter.removeTracker("abcdef", TrackerInfo(id = "https://b.example.net/announce", url = "https://b.example.net/announce"))

        server.takeRequest() // login
        assertTrue(server.takeRequest().body.readUtf8().contains("core.get_torrent_status"))
        val setBody = server.takeRequest().body.readUtf8()
        assertTrue(setBody.contains("core.set_torrent_trackers"))
        assertTrue(setBody.contains("https://a.example.org/announce"))
        assertTrue("removed url must be gone", !setBody.contains("https://b.example.net/announce"))
    }

    @Test
    fun `reannounce passes the id as a list`() = runTest {
        server.enqueue(loginOk())
        server.enqueue(MockResponse().setBody("""{"result": null, "error": null, "id": 2}"""))

        adapter.forceReannounce("abcdef")

        server.takeRequest() // login
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("core.force_reannounce"))
        assertTrue(body.contains("""["abcdef"]"""))
    }

    @Test
    fun `disconnected web ui is explained instead of unknown method`() = runTest {
        server.enqueue(loginOk())
        server.enqueue(
            MockResponse().setBody("""{"result": null, "error": {"message": "Unknown method", "code": 2}, "id": 2}""")
        )
        server.enqueue(MockResponse().setBody("""{"result": false, "error": null, "id": 3}"""))

        try {
            adapter.listTorrents()
            fail("Expected DaemonException.UnexpectedResponse")
        } catch (expected: DaemonException.UnexpectedResponse) {
            assertTrue(expected.message!!.contains("not connected to its daemon"))
        }
    }

    @Test
    fun `daemon error maps to unexpected response`() = runTest {
        server.enqueue(loginOk())
        server.enqueue(
            MockResponse().setBody("""{"result": null, "error": {"message": "Unknown method", "code": 2}, "id": 2}""")
        )
        // The adapter double-checks web.connected before reporting; here it IS connected
        server.enqueue(MockResponse().setBody("""{"result": true, "error": null, "id": 3}"""))

        try {
            adapter.listTorrents()
            fail("Expected DaemonException.UnexpectedResponse")
        } catch (expected: DaemonException.UnexpectedResponse) {
            assertTrue(expected.message!!.contains("Unknown method"))
        }
    }
}
