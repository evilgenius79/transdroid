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
package org.transdroid.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.booleanResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.UUID
import org.transdroid.BuildConfig
import org.transdroid.R
import org.transdroid.data.SearchProviderConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onEditServer: (String?) -> Unit,
    onBack: () -> Unit,
) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val activeId by viewModel.activeServerId.collectAsStateWithLifecycle()
    val providers by viewModel.searchProviders.collectAsStateWithLifecycle()
    val notifyFinished by viewModel.notifyFinished.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val searchAvailable = booleanResource(R.bool.search_available)

    var editingProvider by remember { mutableStateOf<SearchProviderConfig?>(null) }
    var showProviderDialog by remember { mutableStateOf(false) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // Enable regardless; without permission the worker simply cannot post, and the
        // system settings remain the source of truth the user controls.
        viewModel.setNotifyFinished(context, true)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.details_back),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onEditServer(null) }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.settings_add_server))
            }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item { SectionHeader(stringResource(R.string.settings_servers)) }
            items(profiles, key = { it.id }) { profile ->
                val effectiveActiveId = activeId ?: profiles.firstOrNull()?.id
                ListItem(
                    headlineContent = { Text(profile.displayName) },
                    supportingContent = {
                        Text("${profile.type.name.lowercase().replaceFirstChar { it.uppercase() }} · ${profile.host}:${profile.port}")
                    },
                    leadingContent = {
                        Icon(Icons.Default.Dns, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    },
                    trailingContent = {
                        RadioButton(
                            selected = profile.id == effectiveActiveId,
                            onClick = { viewModel.setActive(profile.id) },
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onEditServer(profile.id) },
                )
            }

            item { SectionHeader(stringResource(R.string.settings_notifications)) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_notify_finished)) },
                    supportingContent = { Text(stringResource(R.string.settings_notify_finished_summary)) },
                    leadingContent = {
                        Icon(
                            Icons.Default.Notifications,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = notifyFinished,
                            onCheckedChange = { enabled ->
                                if (enabled && Build.VERSION.SDK_INT >= 33) {
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    viewModel.setNotifyFinished(context, enabled)
                                }
                            },
                        )
                    },
                )
            }

            if (searchAvailable) {
                item { SectionHeader(stringResource(R.string.settings_search_providers)) }
                items(providers, key = { it.id }) { provider ->
                    ListItem(
                        headlineContent = { Text(provider.displayName) },
                        supportingContent = { Text(provider.url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        modifier = Modifier.fillMaxWidth().clickable {
                            editingProvider = provider
                            showProviderDialog = true
                        },
                    )
                }
                item {
                    TextButton(
                        onClick = {
                            editingProvider = null
                            showProviderDialog = true
                        },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        Text(stringResource(R.string.settings_add_search_provider))
                    }
                }
            }

            item {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.settings_about, BuildConfig.VERSION_NAME),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (showProviderDialog) {
        SearchProviderDialog(
            existing = editingProvider,
            onDismiss = { showProviderDialog = false },
            onSave = { provider ->
                viewModel.saveSearchProvider(provider)
                showProviderDialog = false
            },
            onDelete = editingProvider?.let { provider ->
                {
                    viewModel.deleteSearchProvider(provider.id)
                    showProviderDialog = false
                }
            },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun SearchProviderDialog(
    existing: SearchProviderConfig?,
    onDismiss: () -> Unit,
    onSave: (SearchProviderConfig) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var url by remember { mutableStateOf(existing?.url.orEmpty()) }
    var apiKey by remember { mutableStateOf(existing?.apiKey.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (existing == null) R.string.settings_add_search_provider
                    else R.string.settings_edit_search_provider
                )
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.settings_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.settings_torznab_url)) },
                    placeholder = { Text(stringResource(R.string.settings_torznab_url_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(stringResource(R.string.settings_api_key)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        SearchProviderConfig(
                            id = existing?.id ?: UUID.randomUUID().toString(),
                            name = name.trim(),
                            url = url.trim(),
                            apiKey = apiKey.trim(),
                        )
                    )
                },
                enabled = url.trim().startsWith("http"),
            ) { Text(stringResource(R.string.settings_save)) }
        },
        dismissButton = {
            if (onDelete != null) {
                TextButton(onClick = onDelete) { Text(stringResource(R.string.details_remove_confirm)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.details_cancel)) }
            }
        },
    )
}
