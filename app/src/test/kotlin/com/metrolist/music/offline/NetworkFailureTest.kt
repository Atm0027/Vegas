package com.metrolist.music.offline

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.net.UnknownHostException

@RunWith(RobolectricTestRunner::class)
class NetworkFailureTest {
    private fun playback(code: Int, cause: Throwable? = null) = PlaybackException("x", cause, code)

    @Test
    fun noInternetWrappedByTheLoader_isNetworkFailure() {
        val ours = playback(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, IOException("offline"))
        val wrapped = playback(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, RuntimeException("Unexpected", ours))
        assertTrue(NetworkFailure.isNetworkFailure(wrapped))
    }

    @Test
    fun unknownHostDeepInTheChain_isNetworkFailure() {
        val error = playback(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, IOException("io", UnknownHostException("host")))
        assertTrue(NetworkFailure.isNetworkFailure(error))
    }

    @Test
    fun decoderError_isNotNetworkFailure() {
        val error = playback(PlaybackException.ERROR_CODE_DECODING_FAILED, IllegalStateException("codec"))
        assertFalse(NetworkFailure.isNetworkFailure(error))
    }

    @Test
    fun connectedNetworks_tracksLossWithoutAskingTheSystem() {
        val networks = ConnectedNetworks()
        networks.available("wifi")
        networks.available("vpn")
        networks.lost("vpn")
        assertTrue(networks.isConnected)
        networks.lost("wifi")
        assertFalse(networks.isConnected)
    }
}
