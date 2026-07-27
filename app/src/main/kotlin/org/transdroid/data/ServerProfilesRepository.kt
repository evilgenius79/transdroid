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

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * Stores server profiles as one JSON document, encrypted at rest with a Keystore-bound
 * AES-GCM key (see [ProfileCrypto]) via a custom DataStore [Serializer]. The store file is
 * excluded from auto-backup because the key never leaves this device.
 */
class ServerProfilesRepository(context: Context) {

    private val dataStore: DataStore<ProfilesData> = DataStoreFactory.create(
        serializer = EncryptedProfilesSerializer,
        corruptionHandler = ReplaceFileCorruptionHandler { ProfilesData() },
        produceFile = { context.dataStoreFile(FILE_NAME) },
    )

    val profiles: Flow<List<ServerProfile>> = dataStore.data.map { it.profiles }

    suspend fun save(profile: ServerProfile) {
        dataStore.updateData { data ->
            val existing = data.profiles.indexOfFirst { it.id == profile.id }
            val updated = if (existing >= 0) {
                data.profiles.toMutableList().also { it[existing] = profile }
            } else {
                data.profiles + profile
            }
            data.copy(profiles = updated)
        }
    }

    suspend fun delete(profileId: String) {
        dataStore.updateData { data ->
            data.copy(profiles = data.profiles.filterNot { it.id == profileId })
        }
    }

    val feeds: Flow<List<RssFeed>> = dataStore.data.map { it.feeds }

    suspend fun saveFeed(feed: RssFeed) {
        dataStore.updateData { data ->
            val existing = data.feeds.indexOfFirst { it.id == feed.id }
            val updated = if (existing >= 0) {
                data.feeds.toMutableList().also { it[existing] = feed }
            } else {
                data.feeds + feed
            }
            data.copy(feeds = updated)
        }
    }

    suspend fun deleteFeed(feedId: String) {
        dataStore.updateData { data ->
            data.copy(feeds = data.feeds.filterNot { it.id == feedId })
        }
    }

    val searchProviders: Flow<List<SearchProviderConfig>> = dataStore.data.map { it.searchProviders }

    suspend fun saveSearchProvider(provider: SearchProviderConfig) {
        dataStore.updateData { data ->
            val existing = data.searchProviders.indexOfFirst { it.id == provider.id }
            val updated = if (existing >= 0) {
                data.searchProviders.toMutableList().also { it[existing] = provider }
            } else {
                data.searchProviders + provider
            }
            data.copy(searchProviders = updated)
        }
    }

    suspend fun deleteSearchProvider(providerId: String) {
        dataStore.updateData { data ->
            data.copy(searchProviders = data.searchProviders.filterNot { it.id == providerId })
        }
    }

    private object EncryptedProfilesSerializer : Serializer<ProfilesData> {

        private val json = Json { ignoreUnknownKeys = true }

        override val defaultValue = ProfilesData()

        override suspend fun readFrom(input: InputStream): ProfilesData {
            val blob = input.readBytes()
            if (blob.isEmpty()) return defaultValue
            return try {
                json.decodeFromString(ProfileCrypto.decrypt(blob).decodeToString())
            } catch (e: Exception) {
                throw CorruptionException("Cannot read server profiles", e)
            }
        }

        override suspend fun writeTo(t: ProfilesData, output: OutputStream) {
            output.write(ProfileCrypto.encrypt(json.encodeToString(ProfilesData.serializer(), t).encodeToByteArray()))
        }
    }

    private companion object {
        // Referenced in data_extraction_rules.xml / full_backup_content.xml
        const val FILE_NAME = "server_profiles.bin"
    }
}
