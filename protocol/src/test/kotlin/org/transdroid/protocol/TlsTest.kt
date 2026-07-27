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

import javax.net.ssl.SSLException
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class TlsTest {

    private lateinit var server: MockWebServer
    private lateinit var certificate: HeldCertificate
    private lateinit var certSha256: String

    @Before
    fun setUp() {
        certificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .commonName("my-seedbox")
            .build()
        certSha256 = Tls.sha256Hex(certificate.certificate.encoded)
        val handshakeCertificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        server = MockWebServer()
        server.useHttps(handshakeCertificates.sslSocketFactory(), false)
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `self-signed server is rejected without a pin`() = runTest {
        server.enqueue(MockResponse().setBody("hello"))
        try {
            OkHttpClient().newCall(Request.Builder().url(server.url("/")).build()).execute()
            fail("Expected SSLException")
        } catch (expected: SSLException) {
        }
    }

    @Test
    fun `pinned certificate is accepted`() = runTest {
        server.enqueue(MockResponse().setBody("hello"))

        val client = Tls.clientWithPinnedCertificate(OkHttpClient(), certSha256)
        client.newCall(Request.Builder().url(server.url("/")).build()).execute().use { response ->
            assertEquals("hello", response.body?.string())
        }
    }

    @Test
    fun `wrong pin is rejected`() = runTest {
        server.enqueue(MockResponse().setBody("hello"))

        val wrongPin = "ab".repeat(32)
        val client = Tls.clientWithPinnedCertificate(OkHttpClient(), wrongPin)
        try {
            client.newCall(Request.Builder().url(server.url("/")).build()).execute()
            fail("Expected SSLException")
        } catch (expected: SSLException) {
        }
    }

    @Test
    fun `a CA-style different certificate is rejected even though hostname checks are relaxed`() = runTest {
        // Simulates the MITM case: the attacker presents a different, otherwise-valid
        // certificate; strict pinning must reject it regardless of CA validity
        val otherCertificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .commonName("attacker")
            .build()
        val otherServer = MockWebServer()
        otherServer.useHttps(
            HandshakeCertificates.Builder().heldCertificate(otherCertificate).build().sslSocketFactory(),
            false,
        )
        otherServer.start()
        otherServer.enqueue(MockResponse().setBody("mitm"))

        val client = Tls.clientWithPinnedCertificate(OkHttpClient(), certSha256)
        try {
            client.newCall(Request.Builder().url(otherServer.url("/")).build()).execute()
            fail("Expected SSLException")
        } catch (expected: SSLException) {
        } finally {
            otherServer.shutdown()
        }
    }

    @Test
    fun `fetchCertificate reads the fingerprint without trusting anything`() = runTest {
        val fingerprint = Tls.fetchCertificate(server.hostName, server.port)

        assertEquals(certSha256, fingerprint.sha256)
        assertTrue(fingerprint.subject.contains("my-seedbox"))
        assertEquals(32 * 3 - 1, fingerprint.displayFingerprint.length)
    }
}
