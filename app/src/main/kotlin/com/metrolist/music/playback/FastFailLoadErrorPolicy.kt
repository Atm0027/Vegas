/**
 * Vegas: don't hammer a dead stream URL.
 * Licensed under GPL-3.0
 */

package com.metrolist.music.playback

import androidx.media3.common.C
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo

/**
 * 403/410 mean the signed stream URL expired or was rejected; retrying the same URL only delays
 * the error. Failing immediately lets MusicService.onPlayerError refresh the URL.
 */
class FastFailLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorInfo): Long {
        val error = loadErrorInfo.exception
        if (error is HttpDataSource.InvalidResponseCodeException && error.responseCode in DEAD_URL_CODES) {
            return C.TIME_UNSET
        }
        return super.getRetryDelayMsFor(loadErrorInfo)
    }

    private companion object {
        val DEAD_URL_CODES = setOf(403, 410)
    }
}
