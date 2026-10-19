/**
 * Vegas: runs one queued download as a data sync foreground job, with progress shown in the
 * notification and in the song lists.
 * Licensed under GPL-3.0
 */

package com.metrolist.music.offline

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.metrolist.music.R
import com.metrolist.music.db.MusicDatabase
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import timber.log.Timber

@EntryPoint
@InstallIn(SingletonComponent::class)
interface OfflineWorkerEntryPoint {
    fun songDownloader(): SongDownloader

    fun database(): MusicDatabase
}

class OfflineDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val songId = inputData.getString(KEY_SONG_ID) ?: return Result.failure()
        val entry = EntryPointAccessors.fromApplication(applicationContext, OfflineWorkerEntryPoint::class.java)
        val title = entry.database().getSongById(songId)?.song?.title
        val outcome = DownloadSlots.shared.withSlot {
            runCatching { setForeground(foregroundInfo(title, 0f)) }
                .onFailure { Timber.tag(TAG).w(it, "Download of $songId runs without a foreground notification") }

            var lastPercent = -1
            entry.songDownloader().download(songId) { progress ->
                val percent = (progress * 100).toInt()
                if (percent / 5 != lastPercent / 5) {
                    lastPercent = percent
                    setProgressAsync(workDataOf(KEY_PROGRESS to progress))
                    runCatching { setForegroundAsync(foregroundInfo(title, progress)) }
                }
            }
        }
        return when (outcome) {
            is DownloadOutcome.Done -> Result.success()
            is DownloadOutcome.Retry -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
            is DownloadOutcome.Failed -> if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(null, 0f)

    private fun foregroundInfo(title: String?, progress: Float): ForegroundInfo {
        val manager = NotificationManagerCompat.from(applicationContext)
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(applicationContext.getString(R.string.downloading))
                .build(),
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.download)
            .setContentTitle(applicationContext.getString(R.string.downloading))
            .setContentText(title)
            .setProgress(100, (progress * 100).toInt(), progress <= 0f)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val KEY_SONG_ID = "songId"
        const val KEY_PROGRESS = "progress"
        const val CHANNEL_ID = "download"
        private const val NOTIFICATION_ID = 0x5645
        private const val MAX_ATTEMPTS = 8
        private const val TAG = "OfflineDownloadWorker"
    }
}
