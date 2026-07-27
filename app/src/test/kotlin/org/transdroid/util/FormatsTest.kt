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
package org.transdroid.util

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class FormatsTest {

    @Before
    fun fixLocale() {
        Locale.setDefault(Locale.US)
    }

    @Test
    fun bytes() {
        assertEquals("512 B", formatBytes(512))
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("1.4 GB", formatBytes(1_503_238_554))
        assertEquals("2.0 TB", formatBytes(2L * 1024 * 1024 * 1024 * 1024))
    }

    @Test
    fun speed() {
        assertEquals("1.2 MB/s", formatSpeed(1_258_291))
    }

    @Test
    fun eta() {
        assertNull(formatEta(null))
        assertNull(formatEta(-1))
        assertEquals("30s", formatEta(30))
        assertEquals("45m", formatEta(45 * 60))
        assertEquals("3h 12m", formatEta(3 * 3600 + 12 * 60))
        assertEquals("2d 4h", formatEta(2 * 86400 + 4 * 3600))
    }

    @Test
    fun ratio() {
        assertEquals("2.04", formatRatio(2.04f))
        assertEquals("0.00", formatRatio(0f))
    }
}
