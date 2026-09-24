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

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.transdroid.protocol.DaemonAdapter
import org.transdroid.protocol.DaemonConfig
import org.transdroid.protocol.DaemonException
import org.transdroid.protocol.FilePriority
import org.transdroid.protocol.Torrent
import org.transdroid.protocol.TorrentFile
import org.transdroid.protocol.TorrentStatus
import org.transdroid.protocol.TrackerInfo
import org.transdroid.protocol.internal.executeOnIo
import org.transdroid.protocol.internal.joinPath

/**
 * Adapter for the Deluge Web UI JSON-RPC API (POST /json with an _session_id cookie),
 * compatible with Deluge 1.3 and 2.x. Authentication uses the Web UI password only; the
 * username field is ignored. Assumes the Web UI is already connected to its daemon.
 */
class DelugeAdapter(
    override val config: DaemonConfig,
    private val httpClient: OkHttpClient,
) : DaemonAdapter {

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonUrl = config.baseUrl + joinPath(config.path, "json")

    @Volatile
    private var sessionCookie: String? = null

    @Volatile
    private var requestId: Long = 0

    /** Null until first needed; Deluge 1.3 uses a different file-priority scale than 2.x. */
    @Volatile
    private var legacyPriorities: Boolean? = null

    override suspend fun testConnection(): String {
        ensureAuthenticated()
        // The web UI answers even with no daemon behind it; that state is a setup problem
        // the user must fix in Deluge's connection manager, so say so instead of "OK"
        val connected = try {
            call("web.connected").jsonPrimitive.booleanOrNull
        } catch (e: DaemonException.UnexpectedResponse) {
            null
        }
        if (connected == false) {
            throw DaemonException.UnexpectedResponse(
                "Deluge's web interface is not connected to its daemon — open the Deluge web UI and pick the daemon in its connection manager"
            )
        }
        val version = fetchVersion()
        return if (version == null) "Deluge" else "Deluge $version"
    }

    /** daemon.info exists on Deluge 1.3, daemon.get_version on 2.x; either may be absent. */
    private suspend fun fetchVersion(): String? =
        tryVersionCall("daemon.get_version") ?: tryVersionCall("daemon.info")

    private suspend fun tryVersionCall(method: String): String? = try {
        call(method).jsonPrimitive.contentOrNull
    } catch (e: DaemonException.UnexpectedResponse) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    /**
     * Deluge 1.3 files use 0 skip / 1 normal / 2 high / 5 highest; 2.x uses 0 skip /
     * 1-3 low / 4 normal / 5-7 high. Decided once from the daemon version (1.3 when the
     * version cannot be read, since 2.x always answers daemon.get_version).
     */
    private suspend fun usesLegacyPriorities(): Boolean {
        legacyPriorities?.let { return it }
        val version = fetchVersion()
        val legacy = version == null || version.trim().startsWith("1.")
        legacyPriorities = legacy
        return legacy
    }

    private fun decodePriority(value: Int, legacy: Boolean): FilePriority = when {
        value == 0 -> FilePriority.OFF
        legacy -> if (value >= 2) FilePriority.HIGH else FilePriority.NORMAL
        value <= 3 -> FilePriority.LOW
        value >= 5 -> FilePriority.HIGH
        else -> FilePriority.NORMAL
    }

    private fun encodePriority(priority: FilePriority, legacy: Boolean): Int = when (priority) {
        FilePriority.OFF -> 0
        FilePriority.LOW -> if (legacy) 1 else 1
        FilePriority.NORMAL -> if (legacy) 1 else 4
        FilePriority.HIGH -> if (legacy) 2 else 7
    }

    override suspend fun listTorrents(): List<Torrent> {
        ensureAuthenticated()
        val result = try {
            call(
                "core.get_torrents_status",
                buildJsonObject {},
                buildJsonArray { TORRENT_KEYS.forEach { add(it) } },
            )
        } catch (e: DaemonException.UnexpectedResponse) {
            explainIfDaemonDisconnected(e)
        }
        val torrents = result as? JsonObject
            ?: throw DaemonException.UnexpectedResponse("Unexpected core.get_torrents_status reply")
        return torrents.entries.map { (hash, fields) -> parseTorrent(hash, fields.jsonObject) }
    }

    /**
     * core.* calls fail with an opaque "Unknown method" when deluge-web has lost its
     * connection to the daemon (routine after a daemon restart); name the real problem.
     */
    private suspend fun explainIfDaemonDisconnected(original: DaemonException): Nothing {
        val connected = try {
            call("web.connected").jsonPrimitive.booleanOrNull
        } catch (e: Exception) {
            null
        }
        if (connected == false) {
            throw DaemonException.UnexpectedResponse(
                "Deluge's web interface is not connected to its daemon — open the Deluge web UI and pick the daemon in its connection manager"
            )
        }
        throw original
    }

    private fun parseTorrent(hash: String, obj: JsonObject): Torrent {
        val stateName = obj["state"]?.jsonPrimitive?.contentOrNull ?: ""
        val status = when (stateName) {
            "Downloading" -> TorrentStatus.DOWNLOADING
            "Seeding" -> TorrentStatus.SEEDING
            "Paused" -> TorrentStatus.PAUSED
            "Checking", "Allocating", "Moving" -> TorrentStatus.CHECKING
            "Queued" -> TorrentStatus.QUEUED
            "Error" -> TorrentStatus.ERROR
            else -> TorrentStatus.UNKNOWN
        }
        // Deluge freely mixes ints and floats between versions (2.x reports even eta as a
        // float); parse every numeric field tolerantly
        fun long(key: String): Long = obj[key]?.jsonPrimitive?.doubleOrNull?.toLong() ?: 0L
        fun int(key: String): Int = obj[key]?.jsonPrimitive?.doubleOrNull?.toInt() ?: 0

        return Torrent(
            id = hash,
            name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "",
            status = status,
            progress = ((obj["progress"]?.jsonPrimitive?.floatOrNull ?: 0f) / 100f).coerceIn(0f, 1f),
            downloadRate = long("download_payload_rate"),
            uploadRate = long("upload_payload_rate"),
            etaSeconds = long("eta").takeIf { it > 0 },
            sizeBytes = long("total_wanted"),
            downloadedBytes = long("total_done"),
            uploadedBytes = long("total_uploaded"),
            ratio = (obj["ratio"]?.jsonPrimitive?.floatOrNull ?: 0f).coerceAtLeast(0f),
            peersConnected = int("num_peers") + int("num_seeds"),
            addedTimestamp = long("time_added").takeIf { it > 0 },
            downloadDir = obj["save_path"]?.jsonPrimitive?.contentOrNull,
            error = if (status == TorrentStatus.ERROR) {
                obj["message"]?.jsonPrimitive?.contentOrNull ?: "Torrent in error state"
            } else {
                null
            },
            // Present only when Deluge's Label plugin is enabled
            labels = obj["label"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList(),
        )
    }

    override suspend fun addByUrl(url: String, startPaused: Boolean) {
        ensureAuthenticated()
        val options = buildJsonObject { if (startPaused) put("add_paused", true) }
        if (url.startsWith("magnet:")) {
            call("core.add_torrent_magnet", url, options)
        } else {
            call("core.add_torrent_url", url, options)
        }
    }

    override suspend fun addByFile(fileName: String, contents: ByteArray, startPaused: Boolean) {
        ensureAuthenticated()
        val options = buildJsonObject { if (startPaused) put("add_paused", true) }
        call("core.add_torrent_file", fileName, Base64.getEncoder().encodeToString(contents), options)
    }

    override suspend fun start(torrentId: String) {
        ensureAuthenticated()
        call("core.resume_torrent", buildJsonArray { add(torrentId) })
    }

    override suspend fun pause(torrentId: String) {
        ensureAuthenticated()
        call("core.pause_torrent", buildJsonArray { add(torrentId) })
    }

    override suspend fun remove(torrentId: String, deleteData: Boolean) {
        ensureAuthenticated()
        call("core.remove_torrent", torrentId, deleteData)
    }

    override suspend fun listFiles(torrentId: String): List<TorrentFile> {
        ensureAuthenticated()
        val result = call(
            "core.get_torrent_status",
            torrentId,
            buildJsonArray { listOf("files", "file_progress", "file_priorities").forEach { add(it) } },
        )
        val obj = result as? JsonObject
            ?: throw DaemonException.UnexpectedResponse("Unexpected core.get_torrent_status reply")
        val files = obj["files"]?.jsonArray ?: return emptyList()
        val progress = obj["file_progress"]?.jsonArray
        val priorities = obj["file_priorities"]?.jsonArray
        val legacy = usesLegacyPriorities()
        return files.mapIndexed { listIndex, element ->
            val file = element.jsonObject
            val size = file["size"]?.jsonPrimitive?.doubleOrNull?.toLong() ?: 0L
            val index = file["index"]?.jsonPrimitive?.doubleOrNull?.toInt() ?: listIndex
            val fileProgress = progress?.getOrNull(listIndex)?.jsonPrimitive?.floatOrNull ?: 0f
            val rawPriority = priorities?.getOrNull(index)?.jsonPrimitive?.doubleOrNull?.toInt()
                ?: if (legacy) 1 else 4
            TorrentFile(
                index = index,
                path = file["path"]?.jsonPrimitive?.contentOrNull ?: "",
                sizeBytes = size,
                downloadedBytes = (size * fileProgress).toLong(),
                priority = decodePriority(rawPriority, legacy),
            )
        }
    }

    override suspend fun setFilePriority(torrentId: String, fileIndex: Int, priority: FilePriority) {
        ensureAuthenticated()
        // Deluge wants the complete priorities array; read-modify-write it
        val status = call(
            "core.get_torrent_status",
            torrentId,
            buildJsonArray { add("file_priorities") },
        ) as? JsonObject ?: throw DaemonException.UnexpectedResponse("Unexpected file_priorities reply")
        val legacy = usesLegacyPriorities()
        val current = status["file_priorities"]?.jsonArray
            ?.map { it.jsonPrimitive.doubleOrNull?.toInt() ?: if (legacy) 1 else 4 }
            ?: throw DaemonException.UnexpectedResponse("Deluge did not report file priorities")
        if (fileIndex !in current.indices) {
            throw DaemonException.UnexpectedResponse("File index $fileIndex out of range")
        }
        val updated = current.toMutableList().also { it[fileIndex] = encodePriority(priority, legacy) }
        call(
            "core.set_torrent_options",
            buildJsonArray { add(torrentId) },
            buildJsonObject { put("file_priorities", buildJsonArray { updated.forEach { add(it) } }) },
        )
    }

    override suspend fun forceReannounce(torrentId: String) {
        ensureAuthenticated()
        call("core.force_reannounce", buildJsonArray { add(torrentId) })
    }

    override suspend fun listTrackers(torrentId: String): List<TrackerInfo> {
        ensureAuthenticated()
        return fetchTrackers(torrentId).mapNotNull { tracker ->
            val url = tracker["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            TrackerInfo(id = url, url = url)
        }
    }

    override suspend fun removeTracker(torrentId: String, tracker: TrackerInfo) {
        ensureAuthenticated()
        // Deluge has no single-tracker removal; write the tracker list back without it
        val remaining = fetchTrackers(torrentId)
            .filterNot { it["url"]?.jsonPrimitive?.contentOrNull == tracker.url }
        call(
            "core.set_torrent_trackers",
            torrentId,
            buildJsonArray {
                remaining.forEachIndexed { index, entry ->
                    add(buildJsonObject {
                        put("url", entry["url"]?.jsonPrimitive?.contentOrNull ?: "")
                        put("tier", entry["tier"]?.jsonPrimitive?.doubleOrNull?.toInt() ?: index)
                    })
                }
            },
        )
    }

    private suspend fun fetchTrackers(torrentId: String): List<JsonObject> {
        val result = call(
            "core.get_torrent_status",
            torrentId,
            buildJsonArray { add("trackers") },
        ) as? JsonObject ?: throw DaemonException.UnexpectedResponse("Unexpected core.get_torrent_status reply")
        return result["trackers"]?.jsonArray?.mapNotNull { it as? JsonObject }
            ?: throw DaemonException.UnexpectedResponse("Deluge did not report trackers")
    }

    private suspend fun ensureAuthenticated() {
        if (sessionCookie == null) login()
    }

    private suspend fun login() {
        val response = send("auth.login", listOf(JsonPrimitive(config.password.orEmpty())))
        response.use {
            val cookie = it.headers("Set-Cookie").firstOrNull { header -> header.startsWith("_session_id=") }
            val body = parseBody(it)
            val loggedIn = body["result"]?.jsonPrimitive?.booleanOrNull == true
            if (!loggedIn || cookie == null) {
                throw DaemonException.Authentication("Deluge rejected the Web UI password")
            }
            sessionCookie = cookie.substringBefore(';')
        }
    }

    /** Sends one JSON-RPC call, re-authenticating once if the session expired. */
    private suspend fun call(method: String, vararg params: Any?): JsonElement {
        var body = send(method, params.toList()).use { parseBody(it) }
        if (isNotAuthenticated(body["error"])) {
            login()
            body = send(method, params.toList()).use { parseBody(it) }
        }
        val error = body["error"]
        if (error != null && error != JsonNull) {
            if (isNotAuthenticated(error)) {
                throw DaemonException.Authentication("Deluge rejected the Web UI password")
            }
            val message = (error as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: "unknown error"
            throw DaemonException.UnexpectedResponse("Deluge error: $message")
        }
        return body["result"] ?: JsonNull
    }

    private fun isNotAuthenticated(error: JsonElement?): Boolean =
        (error as? JsonObject)?.get("code")?.jsonPrimitive?.int == NOT_AUTHENTICATED_CODE

    private suspend fun send(method: String, params: List<Any?>): okhttp3.Response {
        val payload = buildJsonObject {
            put("method", method)
            put("params", buildJsonArray {
                params.forEach { param ->
                    when (param) {
                        null -> add(JsonNull)
                        is String -> add(param)
                        is Boolean -> add(param)
                        is Number -> add(param)
                        is JsonElement -> add(param)
                        else -> throw IllegalArgumentException("Unsupported param type: ${param::class}")
                    }
                }
            })
            put("id", ++requestId)
        }.toString()
        val builder = Request.Builder()
            .url(jsonUrl)
            .post(payload.toRequestBody("application/json".toMediaType()))
        sessionCookie?.let { builder.header("Cookie", it) }
        val response = httpClient.executeOnIo(builder.build())
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            throw DaemonException.UnexpectedResponse("Deluge returned HTTP $code")
        }
        return response
    }

    private fun parseBody(response: okhttp3.Response): JsonObject = try {
        json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
    } catch (e: Exception) {
        throw DaemonException.UnexpectedResponse("Not a Deluge JSON response", e)
    }

    private companion object {
        const val NOT_AUTHENTICATED_CODE = 1

        val TORRENT_KEYS = listOf(
            "name", "state", "progress", "download_payload_rate", "upload_payload_rate", "eta",
            "total_wanted", "total_done", "total_uploaded", "ratio", "num_peers", "num_seeds",
            "time_added", "save_path", "message", "label",
        )
    }
}
