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

import javax.crypto.AEADBadTagException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupCryptoTest {

    private val payload = """{"profiles":[{"id":"1","password":"s3cret"}]}""".encodeToByteArray()

    @Test
    fun `round trips with the right passphrase`() {
        val blob = BackupCrypto.encrypt(payload, "correct horse".toCharArray())

        assertArrayEquals(payload, BackupCrypto.decrypt(blob, "correct horse".toCharArray()))
    }

    @Test
    fun `backup does not contain the plaintext`() {
        val blob = BackupCrypto.encrypt(payload, "correct horse".toCharArray())

        assertTrue(!blob.decodeToString().contains("s3cret"))
    }

    @Test
    fun `wrong passphrase is rejected`() {
        val blob = BackupCrypto.encrypt(payload, "correct horse".toCharArray())

        try {
            BackupCrypto.decrypt(blob, "wrong horse".toCharArray())
            fail("Expected AEADBadTagException")
        } catch (expected: AEADBadTagException) {
        }
    }

    @Test
    fun `random data is not a backup`() {
        try {
            BackupCrypto.decrypt(ByteArray(64) { it.toByte() }, "any".toCharArray())
            fail("Expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
        }
    }
}
