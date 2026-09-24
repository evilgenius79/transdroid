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
import org.transdroid.protocol.TrackerInfo

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

        assertEquals(5, torrents.size)
        val downloading = torrents[0]
        assertEquals("info hash, not the session-scoped numeric id", "8c212779b4abde7c6bc608063a0d008b7e40ce32", downloading.id)
        assertEquals("ubuntu-24.04.2-desktop-amd64.iso", downloading.name)
        assertEquals(TorrentStatus.DOWNLOADING, downloading.status)
        assertEquals(0.4266f, downloading.progress, 0.0001f)
        assertEquals(1250000L, downloading.downloadRate)
        assertEquals(1220L, downloading.etaSeconds)
        assertEquals(34, downloading.peersConnected)
        assertEquals(listOf("isos"), downloading.labels)
        assertEquals("sizeWhenDone is the wanted size", 6114656256L, downloading.sizeBytes)
        assertEquals("haveValid+haveUnchecked", 2608512724L, downloading.downloadedBytes)

        val seeding = torrents[1]
        assertEquals("a tracker warning (error 2) must not mark the torrent errored",
            TorrentStatus.SEEDING, seeding.status)
        assertEquals("Tracker announce timed out", seeding.error)
        assertNull("eta -1 must normalize to null", seeding.etaSeconds)
        assertEquals(2.0f, seeding.ratio, 0.0001f)
        assertTrue(seeding.isFinished)

        val paused = torrents[2]
        assertEquals(TorrentStatus.PAUSED, paused.status)
        assertEquals("negative ratio must clamp to 0", 0f, paused.ratio, 0.0001f)

        val errored = torrents[3]
        assertEquals("only a local error (3) is a real error", TorrentStatus.ERROR, errored.status)
        assertEquals("Unregistered torrent", errored.error)

        // A magnet still fetching its metadata reports the fetch progress, not 0%
        val magnet = torrents[4]
        assertEquals(0.822f, magnet.metadataProgress!!, 0.001f)
        assertEquals(0.822f, magnet.displayProgress, 0.001f)
        assertNull("resolved torrents must not report metadata progress", downloading.metadataProgress)
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
        assertTrue("magnets/URLs go under the 'filename' argument", body.contains("\"filename\":\"magnet:?xt=urn:btih:abcdef\""))
        assertTrue("not paused unless requested", !body.contains("\"paused\""))
    }

    @Test
    fun `add by file sends base64 metainfo`() = runTest {
        server.enqueue(MockResponse().setBody("""{"result":"success","arguments":{}}"""))

        adapter.addByFile("a.torrent", byteArrayOf(0x64, 0x38, 0x3A), startPaused = true)

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"metainfo\":\"ZDg6\""))
        assertTrue(body.contains("\"paused\":true"))
    }

    @Test
    fun `actions address torrents by info hash`() = runTest {
        server.enqueue(MockResponse().setBody("""{"result":"success","arguments":{}}"""))

        adapter.pause("8c212779b4abde7c6bc608063a0d008b7e40ce32")

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"ids\":[\"8c212779b4abde7c6bc608063a0d008b7e40ce32\"]"))
    }

    @Test
    fun `torrent comment is read from torrent-get`() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"result":"success","arguments":{"torrents":[{"comment":"https://hdclub.com/torrents/1"}]}}""")
        )
        server.enqueue(MockResponse().setBody("""{"result":"success","arguments":{"torrents":[{"comment":""}]}}"""))

        assertEquals("https://hdclub.com/torrents/1", adapter.torrentComment("7"))
        assertTrue(server.takeRequest().body.readUtf8().contains("\"fields\":[\"comment\"]"))
        assertNull(adapter.torrentComment("7"))
    }

    @Test
    fun `403 is explained as an rpc-whitelist rejection, not a bad password`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("<p>Unauthorized IP Address.</p>"))

        try {
            adapter.listTorrents()
            fail("Expected DaemonException.Authentication")
        } catch (expected: DaemonException.Authentication) {
            assertTrue(expected.message!!.contains("rpc-whitelist"))
        }
    }

    @Test
    fun `basic auth encodes the password as UTF-8`() = runTest {
        val utf8Adapter = TransmissionAdapter(
            DaemonConfig(
                type = DaemonType.TRANSMISSION,
                host = server.hostName,
                port = server.port,
                username = "user",
                password = "pässwörd",
            ),
            OkHttpClient(),
        )
        server.enqueue(MockResponse().setBody("""{"result":"success","arguments":{"version":"4.0.5"}}"""))

        utf8Adapter.testConnection()

        val expected = "Basic " + java.util.Base64.getEncoder().encodeToString("user:pässwörd".toByteArray(Charsets.UTF_8))
        assertEquals(expected, server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `add paused sends the paused flag`() = runTest {
        server.enqueue(MockResponse().setBody("""{"result":"success","arguments":{}}"""))

        adapter.addByUrl("magnet:?xt=urn:btih:abcdef", startPaused = true)

        assertTrue(server.takeRequest().body.readUtf8().contains("\"paused\":true"))
    }

    @Test
    fun `html login portal answer produces a diagnosable error`() = runTest {
        server.enqueue(MockResponse().setBody("<html><body>Cloudflare Access login</body></html>"))

        try {
            adapter.listTorrents()
            fail("Expected DaemonException.UnexpectedResponse")
        } catch (expected: DaemonException.UnexpectedResponse) {
            assertTrue(expected.message!!.contains("web page"))
        }
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
    fun `reannounce sends torrent-reannounce with ids`() = runTest {
        server.enqueue(MockResponse().setBody("""{"result":"success","arguments":{}}"""))

        adapter.forceReannounce("7")

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"method\":\"torrent-reannounce\""))
        assertTrue(body.contains("\"ids\":[7]"))
    }

    @Test
    fun `list trackers parses announce urls and ids`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"result":"success","arguments":{"torrents":[{"trackers":[
                  {"id":0,"announce":"https://tracker.example.org/announce","tier":0},
                  {"id":1,"announce":"udp://tracker.example.net:6969/announce","tier":1}]}]}}"""
            )
        )

        val trackers = adapter.listTrackers("7")

        assertEquals(2, trackers.size)
        assertEquals("0", trackers[0].id)
        assertEquals("https://tracker.example.org/announce", trackers[0].url)
        assertEquals("udp://tracker.example.net:6969/announce", trackers[1].url)
    }

    @Test
    fun `remove tracker sends trackerRemove with the tracker id`() = runTest {
        server.enqueue(MockResponse().setBody("""{"result":"success","arguments":{}}"""))

        adapter.removeTracker("7", TrackerInfo(id = "3", url = "https://tracker.example.org/announce"))

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"method\":\"torrent-set\""))
        assertTrue(body.contains("\"trackerRemove\":[3]"))
    }

    @Test
    fun `remove tracker falls back to rewriting trackerList`() = runTest {
        server.enqueue(MockResponse().setBody("""{"result":"error: invalid argument","arguments":{}}"""))
        server.enqueue(
            MockResponse().setBody(
                """{"result":"success","arguments":{"torrents":[
                  {"trackerList":"https://a.example.org/announce\n\nhttps://b.example.net/announce"}]}}"""
            )
        )
        server.enqueue(MockResponse().setBody("""{"result":"success","arguments":{}}"""))

        adapter.removeTracker("7", TrackerInfo(id = "1", url = "https://b.example.net/announce"))

        server.takeRequest() // failed trackerRemove
        server.takeRequest() // trackerList read
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"trackerList\":"))
        assertTrue(body.contains("https://a.example.org/announce"))
        assertTrue("removed url must be gone", !body.contains("https://b.example.net/announce"))
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
