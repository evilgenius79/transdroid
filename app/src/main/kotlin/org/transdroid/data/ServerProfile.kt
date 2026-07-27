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

import kotlinx.serialization.Serializable
import org.transdroid.protocol.DaemonConfig
import org.transdroid.protocol.DaemonType

/**
 * One configured server connection. Persisted encrypted (see [ServerProfilesRepository]);
 * [extras] leaves room for adapter-specific settings without schema migrations.
 */
@Serializable
data class ServerProfile(
    val id: String,
    val name: String,
    val type: DaemonType,
    val host: String,
    val port: Int,
    val useSsl: Boolean = false,
    val path: String = "",
    val username: String = "",
    val password: String = "",
    val extras: Map<String, String> = emptyMap(),
) {
    val displayName: String
        get() = name.ifBlank { host }

    fun toDaemonConfig() = DaemonConfig(
        type = type,
        host = host,
        port = port,
        useSsl = useSsl,
        path = path.takeIf { it.isNotBlank() },
        username = username.takeIf { it.isNotBlank() },
        password = password.takeIf { it.isNotBlank() },
    )
}

/** Root object stored in the encrypted profiles DataStore. */
@Serializable
data class ProfilesData(
    val profiles: List<ServerProfile> = emptyList(),
)
