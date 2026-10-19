/**
 * Vegas: download queue on WorkManager, so downloads survive closing Vegas and rebooting. One unique
 * work per song ("offline-<id>"). Automatic downloads respect the Wi-Fi only setting; downloads the
 * user starts by hand take over a waiting automatic one and use any network.
 * Licensed under GPL-3.0
 */

package com.metrolist.music.offline

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class QueueState(val running: Boolean, val progress: Float)

@Singleton
open class DownloadQueue @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val workManager get() = WorkManager.getInstance(context)

    open fun enqueueManual(songId: String) {
        val current = workManager.getWorkInfosForUniqueWork(workName(songId)).get().filterNot { it.state.isFinished }
        val waitingAutomatic = current.isNotEmpty() &&
            current.all { TAG_AUTO in it.tags && it.state != WorkInfo.State.RUNNING }
        val policy = if (current.isEmpty() || waitingAutomatic) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        enqueue(songId, automatic = false, allowMobileData = true, policy = policy)
    }

    open fun enqueueAutomatic(songIds: Collection<String>, allowMobileData: Boolean) {
        songIds.forEach { enqueue(it, automatic = true, allowMobileData = allowMobileData, policy = ExistingWorkPolicy.KEEP) }
    }

    open fun cancel(songId: String) {
        workManager.cancelUniqueWork(workName(songId))
    }

    /** Waiting automatic downloads adopt a changed mobile data setting. */
    open fun requeueAutomaticPending(allowMobileData: Boolean) {
        workManager.getWorkInfosByTag(TAG_AUTO).get()
            .filter { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
            .mapNotNull { songIdOf(it.tags) }
            .distinct()
            .forEach { enqueue(it, automatic = true, allowMobileData = allowMobileData, policy = ExistingWorkPolicy.REPLACE) }
    }

    open fun states(): Flow<Map<String, QueueState>> =
        workManager.getWorkInfosByTagFlow(TAG).map { infos ->
            infos.filterNot { it.state.isFinished }.mapNotNull { info ->
                songIdOf(info.tags)?.let { id ->
                    id to QueueState(
                        running = info.state == WorkInfo.State.RUNNING,
                        progress = info.progress.getFloat(OfflineDownloadWorker.KEY_PROGRESS, 0f),
                    )
                }
            }.toMap()
        }

    private fun enqueue(songId: String, automatic: Boolean, allowMobileData: Boolean, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<OfflineDownloadWorker>()
            .setInputData(workDataOf(OfflineDownloadWorker.KEY_SONG_ID to songId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(DownloadNetworkRule.networkType(automatic, allowMobileData))
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .addTag(if (automatic) TAG_AUTO else TAG_MANUAL)
            .addTag(ID_PREFIX + songId)
            .build()
        workManager.enqueueUniqueWork(workName(songId), policy, request)
    }

    companion object {
        const val TAG = "offline"
        const val TAG_AUTO = "offline-auto"
        const val TAG_MANUAL = "offline-manual"
        private const val ID_PREFIX = "id:"

        fun workName(songId: String) = "offline-$songId"

        fun songIdOf(tags: Set<String>): String? = tags.firstOrNull { it.startsWith(ID_PREFIX) }?.removePrefix(ID_PREFIX)
    }
}
