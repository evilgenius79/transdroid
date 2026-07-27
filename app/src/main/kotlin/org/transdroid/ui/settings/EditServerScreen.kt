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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.transdroid.R
import org.transdroid.data.ServerProfile
import org.transdroid.protocol.DaemonType
import org.transdroid.protocol.discovery.DiscoveredDaemon
import org.transdroid.ui.message
import org.transdroid.ui.torrents.UiError

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditServerScreen(
    viewModel: SettingsViewModel,
    serverId: String?,
    onBack: () -> Unit,
) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val testState by viewModel.testState.collectAsStateWithLifecycle()
    val certificateState by viewModel.certificateState.collectAsStateWithLifecycle()
    val discovery by viewModel.discovery.collectAsStateWithLifecycle()
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
    var pinnedCert by rememberSaveable(existing?.id) { mutableStateOf(existing?.pinnedCertSha256.orEmpty()) }
    var hostError by remember { mutableStateOf(false) }
    var portError by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { viewModel.resetTestState() }
    }

    // Adding a new server: look around the local network for daemons to offer.
    // Keyed on the nav argument, not `existing` — the profiles flow hasn't emitted on
    // first composition, so `existing` is briefly null even when editing.
    if (serverId == null) {
        LaunchedEffect(Unit) { viewModel.startLanScan() }
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
        pinnedCertSha256 = pinnedCert,
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
            if (serverId == null && (discovery.scanning || discovery.found.isNotEmpty())) {
                DiscoverySection(
                    state = discovery,
                    onPick = { daemon ->
                        type = daemon.type
                        host = daemon.host
                        port = daemon.port.toString()
                        useSsl = false
                        if (name.isBlank()) name = daemon.type.displayName()
                        hostError = false
                        portError = false
                    },
                )
            }

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
                                DaemonType.RTORRENT -> R.string.settings_path_hint_rtorrent
                                DaemonType.DELUGE -> R.string.settings_path_hint_deluge
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

            if (pinnedCert.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(
                            R.string.settings_cert_pinned,
                            pinnedCert.uppercase().chunked(2).take(8).joinToString(":"),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { pinnedCert = "" }) {
                        Text(stringResource(R.string.settings_cert_forget))
                    }
                }
            }

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
                is TestState.Failure -> Column {
                    Text(
                        state.error.message(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (state.error == UiError.Ssl && useSsl) {
                        Spacer(Modifier.height(4.dp))
                        OutlinedButton(
                            onClick = {
                                if (validate()) {
                                    viewModel.fetchCertificate(host.trim(), port.toIntOrNull() ?: type.defaultSslPort)
                                }
                            },
                            enabled = certificateState != CertificateState.Fetching,
                        ) {
                            Text(stringResource(R.string.settings_trust_cert))
                        }
                    }
                }
            }

            when (val certState = certificateState) {
                CertificateState.Idle -> {}
                CertificateState.Fetching -> Text(
                    stringResource(R.string.settings_cert_fetching),
                    style = MaterialTheme.typography.bodyMedium,
                )
                is CertificateState.Failed -> Text(
                    certState.error.message(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                is CertificateState.Fetched -> AlertDialog(
                    onDismissRequest = { viewModel.dismissCertificate() },
                    title = { Text(stringResource(R.string.settings_trust_cert_title)) },
                    text = {
                        Column {
                            Text(stringResource(R.string.settings_trust_cert_message))
                            Spacer(Modifier.height(8.dp))
                            Text(certState.fingerprint.subject, style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                certState.fingerprint.displayFingerprint,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            pinnedCert = certState.fingerprint.sha256
                            viewModel.dismissCertificate()
                            viewModel.testConnection(buildProfile())
                        }) { Text(stringResource(R.string.settings_trust_cert_confirm)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.dismissCertificate() }) {
                            Text(stringResource(R.string.details_cancel))
                        }
                    },
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
    DaemonType.RTORRENT -> "rTorrent"
    DaemonType.DELUGE -> "Deluge"
}

@Composable
private fun DiscoverySection(state: DiscoveryState, onPick: (DiscoveredDaemon) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                stringResource(R.string.settings_discovery_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            if (state.scanning) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp)
                    Text(
                        "  " + stringResource(R.string.settings_discovery_scanning),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            state.found.forEach { daemon ->
                ListItem(
                    headlineContent = { Text(daemon.type.displayName()) },
                    supportingContent = { Text("${daemon.host}:${daemon.port}") },
                    leadingContent = {
                        Icon(Icons.Default.Dns, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.fillMaxWidth().clickable { onPick(daemon) },
                )
            }
        }
    }
}
