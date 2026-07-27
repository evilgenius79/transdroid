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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.transdroid.R
import org.transdroid.data.ServerProfile
import org.transdroid.protocol.DaemonType
import org.transdroid.ui.message

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditServerScreen(
    viewModel: SettingsViewModel,
    serverId: String?,
    onBack: () -> Unit,
) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val testState by viewModel.testState.collectAsStateWithLifecycle()
    val existing = profiles.firstOrNull { it.id == serverId }

    var name by rememberSaveable(existing?.id) { mutableStateOf(existing?.name.orEmpty()) }
    var type by rememberSaveable(existing?.id) { mutableStateOf(existing?.type ?: DaemonType.TRANSMISSION) }
    var host by rememberSaveable(existing?.id) { mutableStateOf(existing?.host.orEmpty()) }
    var port by rememberSaveable(existing?.id) {
        mutableStateOf((existing?.port ?: DaemonType.TRANSMISSION.defaultPort).toString())
    }
    var useSsl by rememberSaveable(existing?.id) { mutableStateOf(existing?.useSsl ?: false) }
    var path by rememberSaveable(existing?.id) { mutableStateOf(existing?.path.orEmpty()) }
    var username by rememberSaveable(existing?.id) { mutableStateOf(existing?.username.orEmpty()) }
    var password by rememberSaveable(existing?.id) { mutableStateOf(existing?.password.orEmpty()) }
    var hostError by remember { mutableStateOf(false) }
    var portError by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { viewModel.resetTestState() }
    }

    fun buildProfile() = ServerProfile(
        id = serverId ?: viewModel.newProfileId(),
        name = name.trim(),
        type = type,
        host = host.trim(),
        port = port.toIntOrNull() ?: type.defaultPort,
        useSsl = useSsl,
        path = path.trim(),
        username = username.trim(),
        password = password,
    )

    fun validate(): Boolean {
        hostError = host.isBlank()
        portError = port.toIntOrNull() !in 1..65535
        return !hostError && !portError
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(if (existing == null) R.string.settings_new_server else R.string.settings_edit_server))
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.details_back),
                        )
                    }
                },
                actions = {
                    if (existing != null) {
                        IconButton(onClick = {
                            viewModel.delete(existing.id)
                            onBack()
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.settings_delete))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.settings_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            var typeMenuExpanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = typeMenuExpanded,
                onExpandedChange = { typeMenuExpanded = it },
            ) {
                OutlinedTextField(
                    value = type.displayName(),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.settings_type)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeMenuExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                )
                ExposedDropdownMenu(
                    expanded = typeMenuExpanded,
                    onDismissRequest = { typeMenuExpanded = false },
                ) {
                    DaemonType.entries.forEach { daemonType ->
                        DropdownMenuItem(
                            text = { Text(daemonType.displayName()) },
                            onClick = {
                                if (port == type.defaultPort.toString()) {
                                    port = daemonType.defaultPort.toString()
                                }
                                type = daemonType
                                typeMenuExpanded = false
                            },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = host,
                onValueChange = {
                    host = it
                    hostError = false
                },
                label = { Text(stringResource(R.string.settings_host)) },
                isError = hostError,
                supportingText = if (hostError) {
                    { Text(stringResource(R.string.settings_invalid_host)) }
                } else {
                    null
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = port,
                onValueChange = {
                    port = it
                    portError = false
                },
                label = { Text(stringResource(R.string.settings_port)) },
                isError = portError,
                supportingText = if (portError) {
                    { Text(stringResource(R.string.settings_invalid_port)) }
                } else {
                    null
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = useSsl, onCheckedChange = { useSsl = it })
                Spacer(Modifier.fillMaxWidth(0.05f))
                Text(stringResource(R.string.settings_use_ssl))
            }
            OutlinedTextField(
                value = path,
                onValueChange = { path = it },
                label = { Text(stringResource(R.string.settings_path)) },
                placeholder = {
                    Text(
                        stringResource(
                            when (type) {
                                DaemonType.TRANSMISSION -> R.string.settings_path_hint_transmission
                                DaemonType.QBITTORRENT -> R.string.settings_path_hint_qbittorrent
                            }
                        )
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text(stringResource(R.string.settings_username)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.settings_password)) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            when (val state = testState) {
                TestState.Idle -> {}
                TestState.Testing -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.height(20.dp).fillMaxWidth(0.06f))
                    Text(
                        "  " + stringResource(R.string.settings_testing),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                is TestState.Success -> Text(
                    stringResource(R.string.settings_test_success, state.versionInfo),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                is TestState.Failure -> Text(
                    state.error.message(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { if (validate()) viewModel.testConnection(buildProfile()) },
                    enabled = testState != TestState.Testing,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.settings_test))
                }
                Button(
                    onClick = {
                        if (validate()) {
                            viewModel.save(buildProfile())
                            onBack()
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.settings_save))
                }
            }
        }
    }
}

private fun DaemonType.displayName(): String = when (this) {
    DaemonType.TRANSMISSION -> "Transmission"
    DaemonType.QBITTORRENT -> "qBittorrent"
}
