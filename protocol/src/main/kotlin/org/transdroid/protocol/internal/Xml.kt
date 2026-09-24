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

import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * Parser features that rule out XXE where the platform parser supports them. Xerces (the
 * JVM) knows the apache.org names; Android's built-in parser throws for anything but the
 * sax.org basics, so each is applied best-effort and [rejectDtd] is the real guarantee.
 */
private val HARDENING_FEATURES = listOf(
    "http://apache.org/xml/features/disallow-doctype-decl" to true,
    "http://xml.org/sax/features/external-general-entities" to false,
    "http://xml.org/sax/features/external-parameter-entities" to false,
    "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
)

/**
 * Parses XML from an untrusted server. Any document carrying a DTD is refused outright:
 * none of the XML this app consumes (XML-RPC, RSS/Atom, Torznab) legitimately needs one,
 * and refusing it works identically on every parser implementation.
 */
internal fun parseXmlSafely(xml: String): Document = parseXmlSafely(xml.toByteArray(Charsets.UTF_8))

/** Parses raw bytes, so the document's own encoding declaration is honored. */
internal fun parseXmlSafely(bytes: ByteArray): Document {
    rejectDtd(bytes)
    val factory = DocumentBuilderFactory.newInstance().apply {
        isExpandEntityReferences = false
        HARDENING_FEATURES.forEach { (name, value) ->
            try {
                setFeature(name, value)
            } catch (e: Exception) {
                // Feature unknown to this parser implementation
            }
        }
        try {
            isXIncludeAware = false
        } catch (e: UnsupportedOperationException) {
            // Android's factory does not implement XInclude at all
        }
    }
    return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
}

private fun rejectDtd(bytes: ByteArray) {
    // Scan as Latin-1 so the check is byte-exact regardless of the declared encoding;
    // the markers are pure ASCII in every ASCII-compatible encoding
    val text = String(bytes, Charsets.ISO_8859_1)
    if (text.contains("<!DOCTYPE", ignoreCase = true) || text.contains("<!ENTITY", ignoreCase = true)) {
        throw IllegalArgumentException("XML documents with a DTD are not accepted")
    }
}

internal fun Element.childElements(): List<Element> {
    val result = mutableListOf<Element>()
    var child: Node? = firstChild
    while (child != null) {
        if (child is Element) result.add(child)
        child = child.nextSibling
    }
    return result
}

/** The text of the first direct child element with this (possibly prefixed) tag name. */
internal fun Element.childText(tagName: String): String? =
    childElements().firstOrNull { it.tagName == tagName || it.tagName.substringAfter(':') == tagName }
        ?.textContent?.trim()?.takeIf { it.isNotEmpty() }
