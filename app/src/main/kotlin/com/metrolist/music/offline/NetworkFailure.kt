/**
 * Vegas: recognises a lost connection however deep ExoPlayer wraps it, and tracks connectivity from
 * the callbacks themselves (asking the system inside onLost can still report the lost network).
 * Licensed under GPL-3.0
 */

package com.metrolist.music.offline

import androidx.media3.common.PlaybackException
import java.net.ConnectException
import java.net.UnknownHostException

object NetworkFailure {
    private val NETWORK_CODES = setOf(
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    )

    fun isNetworkFailure(error: Throwable): Boolean =
        generateSequence(error) { it.cause.takeIf { cause -> cause !== it } }
            .take(16)
            .any { it is ConnectException || it is UnknownHostException || (it is PlaybackException && it.errorCode in NETWORK_CODES) }
}

class ConnectedNetworks {
    private val networks = mutableSetOf<Any>()

    val isConnected: Boolean
        @Synchronized get() = networks.isNotEmpty()

    @Synchronized
    fun available(network: Any) {
        networks += network
    }

    @Synchronized
    fun lost(network: Any) {
        networks -= network
    }
}
