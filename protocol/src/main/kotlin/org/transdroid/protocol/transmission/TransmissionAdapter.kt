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

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.transdroid.protocol.DaemonAdapter
import org.transdroid.protocol.DaemonConfig
import org.transdroid.protocol.DaemonException
import org.transdroid.protocol.FilePriority
import org.transdroid.protocol.internal.executeOnIo
import org.transdroid.protocol.Torrent
import org.transdroid.protocol.TorrentFile
import org.transdroid.protocol.TorrentStatus

/**
 * Adapter for the Transmission RPC protocol (JSON over HTTP POST), as documented in
 * https://github.com/transmission/transmission/blob/main/docs/rpc-spec.md. Handles the
 * CSRF handshake where the daemon answers 409 with a fresh X-Transmission-Session-Id.
 */
class TransmissionAdapter(
    override val config: DaemonConfig,
    private val httpClient: OkHttpClient,
) : DaemonAdapter {

    private val json = Json { ignoreUnknownKeys = true }
    private val rpcUrl = config.baseUrl + (config.path?.takeIf { it.isNotBlank() } ?: "/transmission/rpc")

    @Volatile
    private var sessionId: String? = null

    override suspend fun testConnection(): String {
        val arguments = request("session-get")
        val version = arguments["version"]?.jsonPrimitive?.contentOrNull ?: "unknown"
        val rpcVersion = arguments["rpc-version"]?.jsonPrimitive?.contentOrNull
        return "Transmission $version" + (rpcVersion?.let { " (RPC v$it)" } ?: "")
    }

    override suspend fun listTorrents(): List<Torrent> {
        val arguments = request("torrent-get") {
            put("fields", buildJsonArray { TORRENT_FIELDS.forEach { add(it) } })
        }
        val torrents = arguments["torrents"]?.jsonArray
            ?: throw DaemonException.UnexpectedResponse("Missing 'torrents' in torrent-get response")
        return torrents.map { parseTorrent(it.jsonObject) }
    }

    override suspend fun addByUrl(url: String) {
        request("torrent-add") { put("filename", url) }
    }

    override suspend fun addByFile(fileName: String, contents: ByteArray) {
        request("torrent-add") { put("metainfo", Base64.getEncoder().encodeToString(contents)) }
    }

    override suspend fun start(torrentId: String) {
        request("torrent-start") { putIds(torrentId) }
    }

    override suspend fun pause(torrentId: String) {
        request("torrent-stop") { putIds(torrentId) }
    }

    override suspend fun remove(torrentId: String, deleteData: Boolean) {
        request("torrent-remove") {
            putIds(torrentId)
            put("delete-local-data", deleteData)
        }
    }

    override suspend fun listFiles(torrentId: String): List<TorrentFile> {
        val arguments = request("torrent-get") {
            putIds(torrentId)
            put("fields", buildJsonArray { listOf("files", "fileStats").forEach { add(it) } })
        }
        val torrent = arguments["torrents"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw DaemonException.UnexpectedResponse("Torrent $torrentId not found")
        val files = torrent["files"]?.jsonArray ?: return emptyList()
        val stats = torrent["fileStats"]?.jsonArray
        return files.mapIndexed { index, element ->
            val file = element.jsonObject
            val stat = stats?.getOrNull(index)?.jsonObject
            val wanted = stat?.get("wanted")?.jsonPrimitive?.contentOrNull != "false"
            val priority = when {
                !wanted -> FilePriority.OFF
                else -> when (stat?.get("priority")?.jsonPrimitive?.contentOrNull) {
                    "-1" -> FilePriority.LOW
                    "1" -> FilePriority.HIGH
                    else -> FilePriority.NORMAL
                }
            }
            TorrentFile(
                index = index,
                path = file["name"]?.jsonPrimitive?.contentOrNull ?: "",
                sizeBytes = file["length"]?.jsonPrimitive?.long ?: 0L,
                downloadedBytes = file["bytesCompleted"]?.jsonPrimitive?.long ?: 0L,
                priority = priority,
            )
        }
    }

    override suspend fun setFilePriority(torrentId: String, fileIndex: Int, priority: FilePriority) {
        request("torrent-set") {
            putIds(torrentId)
            val indexes = buildJsonArray { add(fileIndex) }
            if (priority == FilePriority.OFF) {
                put("files-unwanted", indexes)
            } else {
                put("files-wanted", indexes)
                val key = when (priority) {
                    FilePriority.LOW -> "priority-low"
                    FilePriority.HIGH -> "priority-high"
                    else -> "priority-normal"
                }
                put(key, indexes)
            }
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putIds(torrentId: String) {
        val id = torrentId.toIntOrNull()
            ?: throw DaemonException.UnexpectedResponse("Not a Transmission torrent id: $torrentId")
        put("ids", buildJsonArray { add(id) })
    }

    /** Sends one RPC request, retrying once after a 409 session-id challenge. */
    private suspend fun request(
        method: String,
        argumentsBuilder: (kotlinx.serialization.json.JsonObjectBuilder.() -> Unit)? = null,
    ): JsonObject {
        val body = buildJsonObject {
            put("method", method)
            if (argumentsBuilder != null) putJsonObject("arguments", argumentsBuilder)
        }.toString()

        var response = send(body)
        if (response.code == 409) {
            sessionId = response.header(SESSION_ID_HEADER)
            response.close()
            response = send(body)
        }
        response.use {
            when {
                it.code == 401 || it.code == 403 ->
                    throw DaemonException.Authentication("Transmission rejected the username/password")
                !it.isSuccessful ->
                    throw DaemonException.UnexpectedResponse("Transmission returned HTTP ${it.code}")
            }
            val text = it.body?.string().orEmpty()
            val root = try {
                json.parseToJsonElement(text).jsonObject
            } catch (e: Exception) {
                throw DaemonException.UnexpectedResponse("Not a Transmission RPC response", e)
            }
            val result = root["result"]?.jsonPrimitive?.contentOrNull
            if (result != "success") {
                throw DaemonException.UnexpectedResponse("Transmission error: ${result ?: "no result"}")
            }
            return root["arguments"]?.jsonObject ?: JsonObject(emptyMap())
        }
    }

    private suspend fun send(body: String): okhttp3.Response {
        val builder = Request.Builder()
            .url(rpcUrl)
            .post(body.toRequestBody("application/json".toMediaType()))
        sessionId?.let { builder.header(SESSION_ID_HEADER, it) }
        val username = config.username
        if (!username.isNullOrEmpty()) {
            builder.header("Authorization", Credentials.basic(username, config.password.orEmpty()))
        }
        return httpClient.executeOnIo(builder.build())
    }

    private fun parseTorrent(obj: JsonObject): Torrent {
        val error = obj["errorString"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val statusCode = obj["status"]?.jsonPrimitive?.int ?: -1
        val status = when {
            error != null -> TorrentStatus.ERROR
            else -> when (statusCode) {
                0 -> TorrentStatus.PAUSED
                1, 2 -> TorrentStatus.CHECKING
                3, 5 -> TorrentStatus.QUEUED
                4 -> TorrentStatus.DOWNLOADING
                6 -> TorrentStatus.SEEDING
                else -> TorrentStatus.UNKNOWN
            }
        }
        val eta = obj["eta"]?.jsonPrimitive?.long?.takeIf { it >= 0 }
        return Torrent(
            id = obj["id"]?.jsonPrimitive?.contentOrNull
                ?: throw DaemonException.UnexpectedResponse("Torrent without id"),
            name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "",
            status = status,
            progress = obj["percentDone"]?.jsonPrimitive?.float?.coerceIn(0f, 1f) ?: 0f,
            downloadRate = obj["rateDownload"]?.jsonPrimitive?.long ?: 0L,
            uploadRate = obj["rateUpload"]?.jsonPrimitive?.long ?: 0L,
            etaSeconds = eta,
            sizeBytes = obj["totalSize"]?.jsonPrimitive?.long ?: 0L,
            downloadedBytes = obj["downloadedEver"]?.jsonPrimitive?.long ?: 0L,
            uploadedBytes = obj["uploadedEver"]?.jsonPrimitive?.long ?: 0L,
            ratio = (obj["uploadRatio"]?.jsonPrimitive?.float ?: 0f).coerceAtLeast(0f),
            peersConnected = obj["peersConnected"]?.jsonPrimitive?.int ?: 0,
            addedTimestamp = obj["addedDate"]?.jsonPrimitive?.long?.takeIf { it > 0 },
            downloadDir = obj["downloadDir"]?.jsonPrimitive?.contentOrNull,
            error = error,
            labels = obj["labels"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank) }
                ?: emptyList(),
        )
    }

    private companion object {
        const val SESSION_ID_HEADER = "X-Transmission-Session-Id"

        val TORRENT_FIELDS = listOf(
            "id", "name", "status", "percentDone", "rateDownload", "rateUpload", "eta",
            "totalSize", "downloadedEver", "uploadedEver", "uploadRatio", "peersConnected",
            "addedDate", "downloadDir", "errorString", "labels",
        )
    }
}
