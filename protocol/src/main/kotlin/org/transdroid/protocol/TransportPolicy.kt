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

import java.net.InetAddress

/**
 * Cleartext is acceptable for daemons on the local network (the usual home-server setup).
 * Credentials must not be sent to a public host over HTTP, where any observer on the path
 * can reuse them.
 */
object TransportPolicy {

    fun credentialedCleartextToPublicHost(config: DaemonConfig): Boolean {
        if (config.useSsl) return false
        val hasSecret = !config.password.isNullOrBlank() ||
            !config.apiKey.isNullOrBlank() ||
            config.customHeaders.isNotEmpty()
        return hasSecret && !isLocalOrPrivate(config.host)
    }

    /** Loopback, link-local, and RFC1918 / ULA hosts, plus mDNS names. */
    fun isLocalOrPrivate(host: String): Boolean {
        val trimmed = host.trim().removePrefix("[").removeSuffix("]")
        if (trimmed.equals("localhost", ignoreCase = true) || trimmed.endsWith(".local", ignoreCase = true)) {
            return true
        }
        val address = try {
            InetAddress.getByName(trimmed)
        } catch (e: Exception) {
            return false
        }
        return address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress
    }

    /**
     * A feed or indexer URL that embeds a reusable secret (userinfo, or a query string
     * such as a passkey) must not travel in the clear to a public host.
     */
    fun rejectsCleartextSecretUrl(url: String): Boolean {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd <= 0 || !url.startsWith("http://", ignoreCase = true)) return false
        val rest = url.substring(schemeEnd + 3)
        val authority = rest.substringBefore('/').substringBefore('?')
        if ('@' in authority) return !isLocalOrPrivate(authority.substringAfterLast('@').substringBefore(':'))
        if ('?' !in url) return false
        val host = authority.substringBefore(':')
        return !isLocalOrPrivate(host)
    }
}
