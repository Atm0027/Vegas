package com.metrolist.music.offline

import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadNetworkRuleTest {
    @Test
    fun `automatic downloads wait for wifi unless mobile data is allowed`() {
        assertEquals(NetworkType.UNMETERED, DownloadNetworkRule.networkType(automatic = true, allowMobileData = false))
        assertEquals(NetworkType.CONNECTED, DownloadNetworkRule.networkType(automatic = true, allowMobileData = true))
    }

    @Test
    fun `manual downloads use any connection`() {
        assertEquals(NetworkType.CONNECTED, DownloadNetworkRule.networkType(automatic = false, allowMobileData = false))
    }
}
