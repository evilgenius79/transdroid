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
package org.transdroid.protocol.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class XmlTest {

    @Test
    fun `plain documents parse`() {
        val document = parseXmlSafely("""<?xml version="1.0"?><root><child>text</child></root>""")

        assertEquals("text", document.documentElement.childText("child"))
    }

    @Test
    fun `documents declaring a DTD are refused before parsing`() {
        val xxe = """<?xml version="1.0"?>
            <!DOCTYPE root [<!ENTITY secret SYSTEM "file:///etc/passwd">]>
            <root>&secret;</root>"""

        try {
            parseXmlSafely(xxe)
            fail("Expected the DTD to be refused")
        } catch (expected: IllegalArgumentException) {
            // Refused regardless of what the platform parser would have done with the entity
        }
    }

    @Test
    fun `the document's own encoding declaration is honored for raw bytes`() {
        val latin1 = """<?xml version="1.0" encoding="ISO-8859-1"?><root><title>Amélie</title></root>"""
            .toByteArray(Charsets.ISO_8859_1)

        val document = parseXmlSafely(latin1)

        assertEquals("Amélie", document.documentElement.childText("title"))
    }
}
