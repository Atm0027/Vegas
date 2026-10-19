/**
 * Vegas: downloaded songs are real files (content:// or file://). They open through [local] and never
 * touch the network or the stream cache; everything else goes through the [remote] cache chain.
 * Licensed under GPL-3.0
 */

package com.metrolist.music.playback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

@androidx.annotation.OptIn(UnstableApi::class)
class LocalFirstDataSource private constructor(
    private val local: DataSource,
    private val remote: DataSource,
) : DataSource {
    private var current: DataSource? = null

    class Factory(
        private val local: DataSource.Factory,
        private val remote: DataSource.Factory,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = LocalFirstDataSource(local.createDataSource(), remote.createDataSource())
    }

    override fun addTransferListener(transferListener: TransferListener) {
        local.addTransferListener(transferListener)
        remote.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val source = if (isLocal(dataSpec.uri)) local else remote
        current = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        checkNotNull(current) { "read before open" }.read(buffer, offset, length)

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            current?.close()
        } finally {
            current = null
        }
    }

    companion object {
        fun isLocal(uri: Uri): Boolean = uri.scheme == "content" || uri.scheme == "file"
    }
}
