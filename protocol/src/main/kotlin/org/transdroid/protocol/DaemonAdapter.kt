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
package org.transdroid.protocol

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.transdroid.protocol.deluge.DelugeAdapter
import org.transdroid.protocol.qbittorrent.QbittorrentAdapter
import org.transdroid.protocol.rtorrent.RtorrentAdapter
import org.transdroid.protocol.transmission.TransmissionAdapter

/**
 * A connection to one torrent daemon. Implementations are stateless beyond connection/session
 * bookkeeping and safe to call from any dispatcher; all calls block on network I/O internally
 * on the IO dispatcher. All methods throw [DaemonException] on failure.
 */
interface DaemonAdapter {
    val config: DaemonConfig

    /** Verifies connectivity and credentials, returning a daemon version description. */
    suspend fun testConnection(): String

    suspend fun listTorrents(): List<Torrent>

    /**
     * Adds a torrent by magnet link or a URL to a .torrent file. With [startPaused] the
     * torrent is added stopped, so files can be deselected before starting it.
     */
    suspend fun addByUrl(url: String, startPaused: Boolean = false)

    /** Adds a torrent from the raw bytes of a .torrent file. */
    suspend fun addByFile(fileName: String, contents: ByteArray, startPaused: Boolean = false)

    suspend fun start(torrentId: String)

    suspend fun pause(torrentId: String)

    suspend fun remove(torrentId: String, deleteData: Boolean)

    suspend fun listFiles(torrentId: String): List<TorrentFile>

    /**
     * Changes one file's download priority. Clients without a LOW level treat LOW as
     * NORMAL; OFF always means "do not download".
     */
    suspend fun setFilePriority(torrentId: String, fileIndex: Int, priority: FilePriority)

    /** Asks the daemon to announce to this torrent's trackers now. */
    suspend fun forceReannounce(torrentId: String)

    /** Lists the tracker announce URLs registered on a torrent (DHT/PeX pseudo-entries excluded). */
    suspend fun listTrackers(torrentId: String): List<TrackerInfo>

    /**
     * Removes a tracker from a torrent. Pass a [TrackerInfo] obtained from [listTrackers]
     * on the same torrent. rTorrent cannot delete trackers over XML-RPC, so there the
     * tracker is permanently disabled instead and no longer listed.
     */
    suspend fun removeTracker(torrentId: String, tracker: TrackerInfo)
}

object DaemonAdapterFactory {

    /** A default client with timeouts suited for home servers and seedboxes. */
    fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun create(config: DaemonConfig, httpClient: OkHttpClient = defaultHttpClient()): DaemonAdapter {
        var client = config.pinnedCertSha256
            ?.takeIf { it.isNotBlank() }
            ?.let { Tls.clientWithPinnedCertificate(httpClient, it) }
            ?: httpClient
        if (config.customHeaders.isNotEmpty()) {
            client = client.newBuilder().addInterceptor { chain ->
                chain.proceed(withCustomHeaders(chain.request(), config.customHeaders))
            }.build()
        }
        val adapter = when (config.type) {
            DaemonType.TRANSMISSION -> TransmissionAdapter(config, client)
            DaemonType.QBITTORRENT -> QbittorrentAdapter(config, client)
            DaemonType.RTORRENT -> RtorrentAdapter(config, client)
            DaemonType.DELUGE -> DelugeAdapter(config, client)
        }
        return BackgroundDispatchingAdapter(adapter)
    }

    /**
     * Custom headers never clobber what the adapter itself set: an adapter's session
     * cookie or Authorization must survive, so a same-named Cookie is appended to it and
     * any other header the adapter already sent is left alone.
     */
    internal fun withCustomHeaders(request: Request, custom: Map<String, String>): Request {
        val builder = request.newBuilder()
        custom.forEach { (name, value) ->
            val existing = request.header(name)
            when {
                existing == null -> builder.header(name, value)
                name.equals("Cookie", ignoreCase = true) -> builder.header(name, "$existing; $value")
                else -> Unit
            }
        }
        return builder.build()
    }
}

/**
 * Runs every adapter call on the IO dispatcher. The adapters only move the socket work
 * off the caller's thread themselves; decoding and mapping a multi-megabyte torrent list
 * would otherwise run on whatever dispatcher called them — the main thread, for the UI.
 */
private class BackgroundDispatchingAdapter(private val delegate: DaemonAdapter) : DaemonAdapter {
    override val config: DaemonConfig get() = delegate.config

    override suspend fun testConnection(): String = io { testConnection() }
    override suspend fun listTorrents(): List<Torrent> = io { listTorrents() }
    override suspend fun addByUrl(url: String, startPaused: Boolean) = io { addByUrl(url, startPaused) }
    override suspend fun addByFile(fileName: String, contents: ByteArray, startPaused: Boolean) =
        io { addByFile(fileName, contents, startPaused) }
    override suspend fun start(torrentId: String) = io { start(torrentId) }
    override suspend fun pause(torrentId: String) = io { pause(torrentId) }
    override suspend fun remove(torrentId: String, deleteData: Boolean) = io { remove(torrentId, deleteData) }
    override suspend fun listFiles(torrentId: String): List<TorrentFile> = io { listFiles(torrentId) }
    override suspend fun setFilePriority(torrentId: String, fileIndex: Int, priority: FilePriority) =
        io { setFilePriority(torrentId, fileIndex, priority) }
    override suspend fun forceReannounce(torrentId: String) = io { forceReannounce(torrentId) }
    override suspend fun listTrackers(torrentId: String): List<TrackerInfo> = io { listTrackers(torrentId) }
    override suspend fun removeTracker(torrentId: String, tracker: TrackerInfo) = io { removeTracker(torrentId, tracker) }

    private suspend fun <T> io(block: suspend DaemonAdapter.() -> T): T = withContext(Dispatchers.IO) { delegate.block() }
}
