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
package org.transdroid.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.transdroid.protocol.discovery.DaemonProbe
import org.transdroid.protocol.discovery.DiscoveredDaemon

/**
 * Finds torrent daemons on the local Wi-Fi/Ethernet subnet: a fast TCP connect sweep of
 * the /24 around the device's own address on the clients' default ports, followed by an
 * HTTP probe of every open port to identify which daemon answers.
 */
class LanDiscovery(private val context: Context) {

    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(1, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    /** Empty when not on a local network (cellular/VPN-only) or nothing was found. */
    suspend fun scan(): List<DiscoveredDaemon> = withContext(Dispatchers.IO) {
        val prefix = localSubnetPrefix() ?: return@withContext emptyList()
        val concurrency = Semaphore(CONCURRENT_SOCKETS)
        coroutineScope {
            (1..254).flatMap { hostIndex ->
                DaemonProbe.DEFAULT_PORTS.map { port ->
                    async {
                        concurrency.withPermit {
                            val host = "$prefix$hostIndex"
                            if (isPortOpen(host, port)) DaemonProbe.probe(probeClient, host, port) else null
                        }
                    }
                }
            }.awaitAll().filterNotNull().distinctBy { it.host to it.port }.sortedBy { it.host }
        }
    }

    private fun isPortOpen(host: String, port: Int): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            true
        }
    } catch (e: Exception) {
        false
    }

    /** The /24 prefix ("192.168.1.") of the current Wi-Fi/Ethernet IPv4 address, if any. */
    private fun localSubnetPrefix(): String? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = connectivity.activeNetwork ?: return null
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return null
        val local = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        if (!local) return null
        val address = connectivity.getLinkProperties(network)?.linkAddresses
            ?.map { it.address }
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?: return null
        val bytes = address.address
        return "${bytes[0].toUByte()}.${bytes[1].toUByte()}.${bytes[2].toUByte()}."
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 300
        const val CONCURRENT_SOCKETS = 96
    }
}
