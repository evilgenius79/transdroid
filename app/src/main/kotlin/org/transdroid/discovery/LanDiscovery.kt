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

    /**
     * The blocking connect sweep runs on its own bounded pool: on Dispatchers.IO (64
     * threads) 700+ queued connects would starve every other IO user — polls, DataStore,
     * saves — for the whole scan.
     */
    private val sweepDispatcher = Dispatchers.IO.limitedParallelism(CONCURRENT_SOCKETS)

    /** Empty when not on a local network (cellular/VPN-only) or nothing was found. */
    suspend fun scan(): List<DiscoveredDaemon> = withContext(sweepDispatcher) {
        val subnet = localSubnet() ?: return@withContext emptyList()
        coroutineScope {
            subnet.hosts.flatMap { host ->
                DaemonProbe.DEFAULT_PORTS.map { port ->
                    async {
                        if (isPortOpen(host, port)) DaemonProbe.probe(probeClient, host, port) else null
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

    private class Subnet(val hosts: List<String>)

    /**
     * The hosts to sweep on the current Wi-Fi/Ethernet link, honoring its prefix length:
     * at most the /24 around the device (bigger subnets are capped to keep the sweep
     * quick), and only the addresses actually inside a smaller subnet.
     */
    private fun localSubnet(): Subnet? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = connectivity.activeNetwork ?: return null
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return null
        // A VPN mixes its underlying transports into its capabilities; sweeping the
        // tunnel's address space would probe the wrong network entirely
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return null
        val local = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        if (!local) return null
        val link = connectivity.getLinkProperties(network)?.linkAddresses
            ?.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
            ?: return null
        val bytes = link.address.address.map { it.toUByte().toInt() }
        val prefixLength = link.prefixLength.coerceIn(MIN_SWEEP_PREFIX, 30)
        val hostBits = 32 - prefixLength
        val addressInt = (bytes[0] shl 24) or (bytes[1] shl 16) or (bytes[2] shl 8) or bytes[3]
        val networkInt = addressInt and (-1 shl hostBits)
        val broadcastInt = networkInt or ((1 shl hostBits) - 1)
        val hosts = ((networkInt + 1) until broadcastInt).map { host ->
            "${(host ushr 24) and 0xFF}.${(host ushr 16) and 0xFF}.${(host ushr 8) and 0xFF}.${host and 0xFF}"
        }
        return Subnet(hosts)
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 300
        const val CONCURRENT_SOCKETS = 48
        /** Never sweep more than a /24 (254 hosts × 3 ports) regardless of the real subnet. */
        const val MIN_SWEEP_PREFIX = 24
    }
}
