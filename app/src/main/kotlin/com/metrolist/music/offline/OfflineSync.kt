/**
 * Vega: keeps downloaded playlists (and Liked, when marked) downloaded. Any song in a marked
 * playlist without a file is queued, which also covers songs added later and files deleted by
 * hand or outside Vega. Files this install cannot read yet (another install, no permission) are
 * kept, never treated as deleted.
 * Licensed under GPL-3.0
 */

package com.metrolist.music.offline

import android.content.Context
import android.net.Uri
import com.metrolist.music.constants.AutoDownloadOnLikeKey
import com.metrolist.music.constants.DownloadLikedKey
import com.metrolist.music.constants.DownloadOnMobileDataKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.safeDataStoreEdit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OfflineSync @Inject constructor(
    private val database: MusicDatabase,
    private val queue: DownloadQueue,
    private val store: OfflineStore,
    @ApplicationContext private val context: Context,
) {
    data class VerifyReport(val missing: Int, val noAccess: Int)

    /** Steps run before the first pass, e.g. the legacy download migration. */
    var beforeFirstPass: (suspend () -> Unit)? = null

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                migrateLikedSetting()
                beforeFirstPass?.invoke()
                verifyFiles()
            }.onFailure { Timber.tag(TAG).e(it, "Offline start-up checks failed") }

            val settings = context.dataStore.data
                .map { (it[DownloadLikedKey] ?: false) to (it[DownloadOnMobileDataKey] ?: false) }
                .distinctUntilChanged()

            launch {
                settings.map { it.second }.distinctUntilChanged().drop(1).collect { allowMobileData ->
                    queue.requeueAutomaticPending(allowMobileData)
                }
            }

            settings
                .flatMapLatest { (liked, mobile) ->
                    database.songsToAutoDownload(liked).distinctUntilChanged().map { it to mobile }
                }
                .debounce(1_000)
                .collect { (ids, mobile) ->
                    runCatching { if (ids.isNotEmpty()) queue.enqueueAutomatic(ids, mobile) }
                        .onFailure { Timber.tag(TAG).e(it, "Could not queue automatic downloads") }
                }
        }
    }

    /** One catch-up pass: queue every tracked song that has no file. */
    internal suspend fun pass(includeLiked: Boolean, allowMobileData: Boolean) {
        val ids = database.songsToAutoDownload(includeLiked).first()
        if (ids.isNotEmpty()) queue.enqueueAutomatic(ids, allowMobileData)
    }

    suspend fun verifyFiles(): VerifyReport {
        var missing = 0
        var noAccess = 0
        database.allDownloadsOnce().forEach { row ->
            when (store.exists(Uri.parse(row.uri))) {
                OfflineStore.Existence.PRESENT -> Unit
                OfflineStore.Existence.MISSING -> {
                    database.deleteDownload(row.songId)
                    missing++
                }
                OfflineStore.Existence.NO_ACCESS -> noAccess++
            }
        }
        return VerifyReport(missing, noAccess)
    }

    /** "Download when liked" becomes "Liked is a downloaded playlist". */
    private suspend fun migrateLikedSetting() {
        val prefs = context.dataStore.data.first()
        if (prefs[AutoDownloadOnLikeKey] == true && prefs[DownloadLikedKey] == null) {
            context.safeDataStoreEdit {
                it[DownloadLikedKey] = true
                it.remove(AutoDownloadOnLikeKey)
            }
        }
    }

    private companion object {
        const val TAG = "OfflineSync"
    }
}
