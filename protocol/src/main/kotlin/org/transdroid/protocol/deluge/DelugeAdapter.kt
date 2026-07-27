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

    override suspend fun testConnection(): String {
        ensureAuthenticated()
        // daemon.info exists on Deluge 1.3, daemon.get_version on 2.x; either may be absent
        val version = tryVersionCall("daemon.info") ?: tryVersionCall("daemon.get_version")
        return if (version == null) "Deluge" else "Deluge $version"
    }

    private suspend fun tryVersionCall(method: String): String? = try {
        call(method).jsonPrimitive.contentOrNull
    } catch (e: DaemonException.UnexpectedResponse) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    override suspend fun listTorrents(): List<Torrent> {
        ensureAuthenticated()
        val result = call(
            "core.get_torrents_status",
            buildJsonObject {},
            buildJsonArray { TORRENT_KEYS.forEach { add(it) } },
        )
        val torrents = result as? JsonObject
            ?: throw DaemonException.UnexpectedResponse("Unexpected core.get_torrents_status reply")
        return torrents.entries.map { (hash, fields) -> parseTorrent(hash, fields.jsonObject) }
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

    override suspend fun addByUrl(url: String) {
        ensureAuthenticated()
        if (url.startsWith("magnet:")) {
            call("core.add_torrent_magnet", url, buildJsonObject {})
        } else {
            call("core.add_torrent_url", url, buildJsonObject {})
        }
    }

    override suspend fun addByFile(fileName: String, contents: ByteArray) {
        ensureAuthenticated()
        call("core.add_torrent_file", fileName, Base64.getEncoder().encodeToString(contents), buildJsonObject {})
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
        return files.mapIndexed { listIndex, element ->
            val file = element.jsonObject
            val size = file["size"]?.jsonPrimitive?.doubleOrNull?.toLong() ?: 0L
            val index = file["index"]?.jsonPrimitive?.doubleOrNull?.toInt() ?: listIndex
            val fileProgress = progress?.getOrNull(listIndex)?.jsonPrimitive?.floatOrNull ?: 0f
            TorrentFile(
                index = index,
                path = file["path"]?.jsonPrimitive?.contentOrNull ?: "",
                sizeBytes = size,
                downloadedBytes = (size * fileProgress).toLong(),
                priority = when (priorities?.getOrNull(index)?.jsonPrimitive?.doubleOrNull?.toInt() ?: 4) {
                    0 -> FilePriority.OFF
                    1 -> FilePriority.LOW
                    7 -> FilePriority.HIGH
                    else -> FilePriority.NORMAL
                },
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
        val current = status["file_priorities"]?.jsonArray
            ?.map { it.jsonPrimitive.doubleOrNull?.toInt() ?: 4 }
            ?: throw DaemonException.UnexpectedResponse("Deluge did not report file priorities")
        if (fileIndex !in current.indices) {
            throw DaemonException.UnexpectedResponse("File index $fileIndex out of range")
        }
        val value = when (priority) {
            FilePriority.OFF -> 0
            FilePriority.LOW -> 1
            FilePriority.HIGH -> 7
            else -> 4
        }
        val updated = current.toMutableList().also { it[fileIndex] = value }
        call(
            "core.set_torrent_options",
            buildJsonArray { add(torrentId) },
            buildJsonObject { put("file_priorities", buildJsonArray { updated.forEach { add(it) } }) },
        )
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
