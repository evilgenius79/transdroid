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
package org.transdroid.background

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.transdroid.MainActivity
import org.transdroid.R
import org.transdroid.appContainer

/**
 * Periodically checks the active server and notifies for torrents that finished since the
 * previous check. Also refreshes the home screen widget snapshot as a side effect.
 */
class FinishedTorrentsWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = applicationContext.appContainer
        val profile = container.activeProfile.first() ?: return Result.success()
        val torrents = try {
            container.adapterFor(profile).listTorrents()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Server unreachable right now; try again on the next periodic run
            return Result.success()
        }

        container.widgetStateRepository.update(profile.displayName, torrents)

        val previouslyUnfinished = container.settingsRepository.unfinishedTorrentIds(profile.id).first()
        val newlyFinished = torrents.filter { it.isFinished && it.id in previouslyUnfinished }
        if (newlyFinished.isNotEmpty()) {
            notifyFinished(newlyFinished.map { it.name })
        }
        container.settingsRepository.setUnfinishedTorrentIds(
            profile.id,
            torrents.filterNot { it.isFinished }.map { it.id }.toSet(),
        )
        return Result.success()
    }

    // Permission is checked just below; lint cannot follow the SDK_INT-guarded early return
    @android.annotation.SuppressLint("MissingPermission", "NotificationPermission")
    private fun notifyFinished(names: List<String>) {
        val context = applicationContext
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel(context)
        val tapIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = context.resources.getQuantityString(R.plurals.notification_finished_title, names.size, names.size)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(names.joinToString())
            .setStyle(NotificationCompat.BigTextStyle().bigText(names.joinToString("\n")))
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_finished),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
    }

    companion object {
        private const val WORK_NAME = "finished_torrents_check"
        private const val CHANNEL_ID = "torrents_finished"
        private const val NOTIFICATION_ID = 1

        /** Idempotent; safe to call on every app start while the setting is enabled. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<FinishedTorrentsWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
