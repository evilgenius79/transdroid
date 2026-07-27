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

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
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
 * Adapter for the qBittorrent Web API v2 (REST over HTTP with SID cookie auth), as documented
 * in https://github.com/qbittorrent/qBittorrent/wiki/WebUI-API-(qBittorrent-4.1). Also covers
 * qBittorrent 5.x, which renamed the pause/resume endpoints to stop/start; both are tried.
 */
class QbittorrentAdapter(
    override val config: DaemonConfig,
    private val httpClient: OkHttpClient,
) : DaemonAdapter {

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var sessionCookie: String? = null

    override suspend fun testConnection(): String {
        val version = get("api/v2/app/version").use { it.readBodyOrThrow() }
        return "qBittorrent $version"
    }

    override suspend fun listTorrents(): List<Torrent> {
        val body = get("api/v2/torrents/info").use { it.readBodyOrThrow() }
        val infos = try {
            json.decodeFromString<List<TorrentInfo>>(body)
        } catch (e: Exception) {
            throw DaemonException.UnexpectedResponse("Cannot parse qBittorrent torrent list", e)
        }
        return infos.map { it.toTorrent() }
    }

    override suspend fun addByUrl(url: String) {
        val form = FormBody.Builder().add("urls", url).build()
        post("api/v2/torrents/add", form).use { it.readBodyOrThrow() }
    }

    override suspend fun addByFile(fileName: String, contents: ByteArray) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "torrents", fileName,
                contents.toRequestBody("application/x-bittorrent".toMediaType()),
            )
            .build()
        post("api/v2/torrents/add", body).use { it.readBodyOrThrow() }
    }

    override suspend fun start(torrentId: String) {
        hashesActionWithFallback("start", "resume", torrentId)
    }

    override suspend fun pause(torrentId: String) {
        hashesActionWithFallback("stop", "pause", torrentId)
    }

    override suspend fun remove(torrentId: String, deleteData: Boolean) {
        val form = FormBody.Builder()
            .add("hashes", torrentId)
            .add("deleteFiles", deleteData.toString())
            .build()
        post("api/v2/torrents/delete", form).use { it.readBodyOrThrow() }
    }

    override suspend fun listFiles(torrentId: String): List<TorrentFile> {
        val body = get("api/v2/torrents/files?hash=$torrentId").use { it.readBodyOrThrow() }
        val files = try {
            json.decodeFromString<List<FileInfo>>(body)
        } catch (e: Exception) {
            throw DaemonException.UnexpectedResponse("Cannot parse qBittorrent file list", e)
        }
        return files.map { file ->
            TorrentFile(
                path = file.name,
                sizeBytes = file.size,
                downloadedBytes = (file.progress * file.size).toLong(),
                priority = when (file.priority) {
                    0 -> FilePriority.OFF
                    6, 7 -> FilePriority.HIGH
                    else -> FilePriority.NORMAL
                },
            )
        }
    }

    /** qBittorrent 5 renamed pause/resume to stop/start; try new name first, fall back on 404. */
    private suspend fun hashesActionWithFallback(newEndpoint: String, legacyEndpoint: String, hash: String) {
        val form = { FormBody.Builder().add("hashes", hash).build() }
        val response = post("api/v2/torrents/$newEndpoint", form(), allowNotFound = true)
        if (response.code == 404) {
            response.close()
            post("api/v2/torrents/$legacyEndpoint", form()).use { it.readBodyOrThrow() }
        } else {
            response.use { it.readBodyOrThrow() }
        }
    }

    private suspend fun ensureAuthenticated() {
        if (sessionCookie != null || config.username.isNullOrEmpty()) return
        login()
    }

    private suspend fun login() {
        val form = FormBody.Builder()
            .add("username", config.username.orEmpty())
            .add("password", config.password.orEmpty())
            .build()
        val request = Request.Builder()
            .url(config.baseUrl + joinPath(config.path, "api/v2/auth/login"))
            .post(form)
            .build()
        httpClient.executeOnIo(request).use { response ->
            if (response.code == 403) {
                throw DaemonException.Authentication("qBittorrent blocked this address (too many failed logins)")
            }
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful || body.trim() != "Ok.") {
                throw DaemonException.Authentication("qBittorrent rejected the username/password")
            }
            val cookie = response.headers("Set-Cookie").firstOrNull { it.startsWith("SID=") }
                ?: throw DaemonException.UnexpectedResponse("qBittorrent login did not return a session cookie")
            sessionCookie = cookie.substringBefore(';')
        }
    }

    private suspend fun get(endpoint: String): Response =
        sendAuthenticated { Request.Builder().url(config.baseUrl + joinPath(config.path, endpoint)).get() }

    private suspend fun post(endpoint: String, body: okhttp3.RequestBody, allowNotFound: Boolean = false): Response =
        sendAuthenticated(allowNotFound) {
            Request.Builder().url(config.baseUrl + joinPath(config.path, endpoint)).post(body)
        }

    /** Sends a request with the SID cookie, re-authenticating once when the session expired. */
    private suspend fun sendAuthenticated(
        allowNotFound: Boolean = false,
        build: () -> Request.Builder,
    ): Response {
        ensureAuthenticated()
        var response = send(build())
        if (response.code == 403 && !config.username.isNullOrEmpty()) {
            response.close()
            login()
            response = send(build())
        }
        when {
            response.code == 401 || response.code == 403 -> {
                response.close()
                throw DaemonException.Authentication("qBittorrent requires a valid username/password")
            }
            response.code == 404 && allowNotFound -> return response
            !response.isSuccessful -> {
                val code = response.code
                response.close()
                throw DaemonException.UnexpectedResponse("qBittorrent returned HTTP $code")
            }
        }
        return response
    }

    private suspend fun send(builder: Request.Builder): Response {
        sessionCookie?.let { builder.header("Cookie", it) }
        return httpClient.executeOnIo(builder.build())
    }

    private fun Response.readBodyOrThrow(): String = body?.string().orEmpty()

    @Serializable
    private data class TorrentInfo(
        val hash: String,
        val name: String = "",
        val state: String = "",
        val progress: Float = 0f,
        val dlspeed: Long = 0,
        val upspeed: Long = 0,
        val eta: Long = INFINITE_ETA,
        val size: Long = 0,
        val completed: Long = 0,
        val uploaded: Long = 0,
        val ratio: Float = 0f,
        val num_seeds: Int = 0,
        val num_leechs: Int = 0,
        val added_on: Long = 0,
        val save_path: String? = null,
    ) {
        fun toTorrent() = Torrent(
            id = hash,
            name = name,
            status = when (state) {
                "downloading", "metaDL", "forcedDL", "stalledDL", "forcedMetaDL" -> TorrentStatus.DOWNLOADING
                "uploading", "stalledUP", "forcedUP" -> TorrentStatus.SEEDING
                "pausedDL", "pausedUP", "stoppedDL", "stoppedUP" -> TorrentStatus.PAUSED
                "checkingDL", "checkingUP", "checkingResumeData", "allocating" -> TorrentStatus.CHECKING
                "queuedDL", "queuedUP" -> TorrentStatus.QUEUED
                "error", "missingFiles" -> TorrentStatus.ERROR
                else -> TorrentStatus.UNKNOWN
            },
            progress = progress.coerceIn(0f, 1f),
            downloadRate = dlspeed,
            uploadRate = upspeed,
            etaSeconds = eta.takeIf { it in 0 until INFINITE_ETA },
            sizeBytes = size,
            downloadedBytes = completed,
            uploadedBytes = uploaded,
            ratio = ratio.coerceAtLeast(0f),
            peersConnected = num_seeds + num_leechs,
            addedTimestamp = added_on.takeIf { it > 0 },
            downloadDir = save_path,
            error = if (state == "error" || state == "missingFiles") "Torrent in error state ($state)" else null,
        )
    }

    @Serializable
    private data class FileInfo(
        val name: String,
        val size: Long = 0,
        val progress: Float = 0f,
        val priority: Int = 1,
    )

    private companion object {
        /** qBittorrent reports 8640000 seconds as "no ETA". */
        const val INFINITE_ETA = 8640000L
    }
}
