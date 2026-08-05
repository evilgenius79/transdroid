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

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Passphrase-based encryption for settings backup files. Unlike the at-rest store (whose
 * Keystore key intentionally dies with the install), a backup must survive uninstall and
 * move between devices, so its key is derived from a user-chosen passphrase via PBKDF2.
 *
 * File layout: "TDBKP1" magic, [1B salt length][salt], [1B IV length][IV], ciphertext+tag.
 */
object BackupCrypto {

    private val MAGIC = "TDBKP1".encodeToByteArray()
    private const val ITERATIONS = 210_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val TAG_LENGTH_BITS = 128

    fun encrypt(plaintext: ByteArray, passphrase: CharArray): ByteArray {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt))
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        return MAGIC + byteArrayOf(salt.size.toByte()) + salt +
            byteArrayOf(iv.size.toByte()) + iv + ciphertext
    }

    /**
     * @throws IllegalArgumentException when [blob] is not a Transdroid backup file
     * @throws javax.crypto.AEADBadTagException when the passphrase is wrong
     */
    fun decrypt(blob: ByteArray, passphrase: CharArray): ByteArray {
        require(blob.size > MAGIC.size + 2) { "Not a Transdroid backup" }
        require(blob.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) { "Not a Transdroid backup" }
        var offset = MAGIC.size
        val saltLength = blob[offset].toInt()
        require(saltLength in 8..32 && blob.size > offset + 1 + saltLength) { "Corrupt backup file" }
        val salt = blob.copyOfRange(offset + 1, offset + 1 + saltLength)
        offset += 1 + saltLength
        val ivLength = blob[offset].toInt()
        require(ivLength in 12..16 && blob.size > offset + 1 + ivLength) { "Corrupt backup file" }
        val iv = blob.copyOfRange(offset + 1, offset + 1 + ivLength)
        offset += 1 + ivLength
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        return cipher.doFinal(blob.copyOfRange(offset, blob.size))
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val key = factory.generateSecret(PBEKeySpec(passphrase, salt, ITERATIONS, KEY_BITS))
        return SecretKeySpec(key.encoded, "AES")
    }
}
