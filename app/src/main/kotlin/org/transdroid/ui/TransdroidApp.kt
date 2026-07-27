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
package org.transdroid.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.transdroid.ui.add.AddTorrentScreen
import org.transdroid.ui.settings.EditServerScreen
import org.transdroid.ui.settings.SettingsScreen
import org.transdroid.ui.settings.SettingsViewModel
import org.transdroid.ui.torrents.TorrentDetailsScreen
import org.transdroid.ui.torrents.TorrentsScreen
import org.transdroid.ui.torrents.TorrentsViewModel

object Routes {
    const val TORRENTS = "torrents"
    const val TORRENT_DETAILS = "torrent/{id}"
    const val ADD = "add?url={url}"
    const val SETTINGS = "settings"
    const val EDIT_SERVER = "settings/server/{id}"

    const val NEW_SERVER_ID = "new"

    fun torrentDetails(id: String) = "torrent/${Uri.encode(id)}"
    fun add(url: String?) = if (url == null) "add" else "add?url=${Uri.encode(url)}"
    fun editServer(id: String?) = "settings/server/${Uri.encode(id ?: NEW_SERVER_ID)}"
}

@Composable
fun TransdroidApp(
    useTwoPane: Boolean,
    pendingTorrentUrl: String?,
    onPendingTorrentUrlConsumed: () -> Unit,
) {
    val navController = rememberNavController()
    val torrentsViewModel: TorrentsViewModel = viewModel(factory = TorrentsViewModel.Factory)
    val settingsViewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)

    LaunchedEffect(pendingTorrentUrl) {
        if (pendingTorrentUrl != null) {
            navController.navigate(Routes.add(pendingTorrentUrl))
            onPendingTorrentUrlConsumed()
        }
    }

    NavHost(navController = navController, startDestination = Routes.TORRENTS) {
        composable(Routes.TORRENTS) {
            TorrentsScreen(
                viewModel = torrentsViewModel,
                useTwoPane = useTwoPane,
                onOpenDetails = { id ->
                    torrentsViewModel.select(id)
                    if (!useTwoPane) navController.navigate(Routes.torrentDetails(id))
                },
                onAddTorrent = { navController.navigate(Routes.add(null)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(
            Routes.TORRENT_DETAILS,
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            TorrentDetailsScreen(
                viewModel = torrentsViewModel,
                torrentId = id,
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            Routes.ADD,
            arguments = listOf(navArgument("url") { type = NavType.StringType; defaultValue = "" }),
        ) { entry ->
            AddTorrentScreen(
                viewModel = torrentsViewModel,
                initialUrl = entry.arguments?.getString("url").orEmpty(),
                onDone = { navController.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                viewModel = settingsViewModel,
                onEditServer = { id -> navController.navigate(Routes.editServer(id)) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            Routes.EDIT_SERVER,
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            EditServerScreen(
                viewModel = settingsViewModel,
                serverId = entry.arguments?.getString("id")?.takeIf { it != Routes.NEW_SERVER_ID },
                onBack = { navController.popBackStack() },
            )
        }
    }
}
