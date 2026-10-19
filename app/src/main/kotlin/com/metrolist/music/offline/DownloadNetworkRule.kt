/**
 * Vegas: automatic downloads (tracked playlists, liked songs) wait for Wi-Fi unless the user allows
 * mobile data; downloads the user starts by hand use any connection.
 * Licensed under GPL-3.0
 */

package com.metrolist.music.offline

import androidx.work.NetworkType

object DownloadNetworkRule {
    fun networkType(automatic: Boolean, allowMobileData: Boolean): NetworkType =
        if (automatic && !allowMobileData) NetworkType.UNMETERED else NetworkType.CONNECTED
}
