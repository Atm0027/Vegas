package com.metrolist.music.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FastFailLoadErrorPolicyTest {
    private val policy = FastFailLoadErrorPolicy()
    private val spec = DataSpec(Uri.parse("https://example.invalid/a"))

    private fun info(e: IOException) =
        LoadErrorInfo(
            LoadEventInfo(0, spec, 0L),
            MediaLoadData(C.DATA_TYPE_MEDIA),
            e,
            1,
        )

    private fun http(code: Int) =
        HttpDataSource.InvalidResponseCodeException(code, null, null, emptyMap(), spec, ByteArray(0))

    @Test
    fun expiredUrl_403_isNotRetried() {
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(info(http(403))))
    }

    @Test
    fun gone_410_isNotRetried() {
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(info(http(410))))
    }

    @Test
    fun plainIoError_isStillRetried() {
        assertNotEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(info(IOException("reset"))))
    }
}
