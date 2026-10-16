package com.metrolist.music.offline

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DownloadQueueTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var queue: DownloadQueue

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setMinimumLoggingLevel(Log.DEBUG).setExecutor(SynchronousExecutor()).build(),
        )
        queue = DownloadQueue(context)
    }

    private fun pending(songId: String): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork("offline-$songId").get().filterNot { it.state.isFinished }

    @Test
    fun automaticDownload_waitsForWifi() {
        queue.enqueueAutomatic(listOf("a"), allowMobileData = false)
        val work = pending("a").single()
        assertEquals(NetworkType.UNMETERED, work.constraints.requiredNetworkType)
        assertEquals(true, "offline-auto" in work.tags)
    }

    @Test
    fun manualDownload_replacesWaitingAutomaticOne_withAnyNetwork() {
        queue.enqueueAutomatic(listOf("a"), allowMobileData = false)
        queue.enqueueManual("a")
        val work = pending("a").single()
        assertEquals(NetworkType.CONNECTED, work.constraints.requiredNetworkType)
        assertEquals(true, "offline-manual" in work.tags)
    }

    @Test
    fun automaticDownload_isNotDuplicated() {
        queue.enqueueAutomatic(listOf("a"), allowMobileData = false)
        queue.enqueueAutomatic(listOf("a"), allowMobileData = false)
        assertEquals(1, pending("a").size)
    }

    @Test
    fun allowingMobileData_movesWaitingAutomaticDownloads() {
        queue.enqueueAutomatic(listOf("a", "b"), allowMobileData = false)
        queue.requeueAutomaticPending(allowMobileData = true)
        assertEquals(NetworkType.CONNECTED, pending("a").single().constraints.requiredNetworkType)
        assertEquals(NetworkType.CONNECTED, pending("b").single().constraints.requiredNetworkType)
    }

    @Test
    fun cancel_removesTheWork() {
        queue.enqueueManual("a")
        queue.cancel("a")
        assertEquals(0, pending("a").size)
    }
}
