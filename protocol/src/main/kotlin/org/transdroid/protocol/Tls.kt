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

import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A server certificate's identity, for the "trust this server?" flow. */
data class CertificateFingerprint(
    /** Lowercase hex SHA-256 of the DER-encoded certificate, no separators. */
    val sha256: String,
    val subject: String,
) {
    /** Human-readable form like AB:12:… for display in a trust dialog. */
    val displayFingerprint: String
        get() = sha256.uppercase().chunked(2).joinToString(":")
}

object Tls {

    /**
     * Wraps [base] so TLS accepts ONLY the certificate whose SHA-256 equals [certSha256]
     * (lowercase hex) — nothing else, not even CA-valid chains. Hostname verification is
     * safe to relax precisely because trust can only come from the exact pinned
     * certificate: an attacker cannot substitute any other identity, CA-signed or not,
     * without failing the pin. (A CA fallback here would let any publicly valid
     * certificate bypass the disabled hostname check and MITM the connection.)
     */
    fun clientWithPinnedCertificate(base: okhttp3.OkHttpClient, certSha256: String): okhttp3.OkHttpClient {
        val trustManager = PinnedTrustManager(certSha256.lowercase())
        val sslContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
        return base.newBuilder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    /**
     * Connects to [host]:[port], performs a TLS handshake without validating trust, and
     * returns the leaf certificate's fingerprint. Nothing but the handshake is exchanged;
     * the caller shows the fingerprint to the user before anything is trusted.
     */
    suspend fun fetchCertificate(host: String, port: Int): CertificateFingerprint = withContext(Dispatchers.IO) {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val sslContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), null) }
        val factory: SSLSocketFactory = sslContext.socketFactory
        try {
            Socket().use { raw ->
                raw.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                raw.soTimeout = CONNECT_TIMEOUT_MS
                (factory.createSocket(raw, host, port, true) as SSLSocket).use { socket ->
                    socket.startHandshake()
                    val leaf = socket.session.peerCertificates.firstOrNull() as? X509Certificate
                        ?: throw DaemonException.UntrustedServer("Server sent no certificate")
                    CertificateFingerprint(
                        sha256 = sha256Hex(leaf.encoded),
                        subject = leaf.subjectX500Principal.name,
                    )
                }
            }
        } catch (e: DaemonException) {
            throw e
        } catch (e: Exception) {
            throw DaemonException.Connection("Cannot read the certificate of $host:$port", e)
        }
    }

    internal fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private const val CONNECT_TIMEOUT_MS = 7_000
}

/**
 * Trusts exactly one certificate: the explicitly pinned one. Deliberately no CA fallback —
 * combined with the relaxed hostname verifier, a CA path would let any publicly valid
 * certificate impersonate the pinned server.
 */
private class PinnedTrustManager(private val pinnedSha256: String) : X509TrustManager {

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        throw CertificateException("Client certificates are not supported")
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        val leaf = chain.firstOrNull()
            ?: throw CertificateException("Server sent no certificate")
        if (Tls.sha256Hex(leaf.encoded) != pinnedSha256) {
            throw CertificateException("Server certificate does not match the pinned fingerprint")
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
}
