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
package org.transdroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.transdroid.protocol.DaemonType

class ServerProfileTest {

    private fun profile(headers: String) = ServerProfile(
        id = "1",
        name = "Test",
        type = DaemonType.TRANSMISSION,
        host = "host",
        port = 443,
        customHeaders = headers,
    )

    @Test
    fun `custom headers parse one name-value pair per line`() {
        val config = profile(
            "CF-Access-Client-Id: abc.access\n" +
                "CF-Access-Client-Secret: s3cr3t: with: colons\n" +
                "\n" +
                "not-a-header\n" +
                ": no-name"
        ).toDaemonConfig()

        assertEquals(
            mapOf(
                "CF-Access-Client-Id" to "abc.access",
                // Only the first colon separates; the value keeps its own colons
                "CF-Access-Client-Secret" to "s3cr3t: with: colons",
            ),
            config.customHeaders,
        )
    }

    @Test
    fun `no headers yields an empty map`() {
        assertTrue(profile("").toDaemonConfig().customHeaders.isEmpty())
    }
}
