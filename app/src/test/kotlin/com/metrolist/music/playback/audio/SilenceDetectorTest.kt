package com.metrolist.music.playback.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SilenceDetectorTest {
    private val format = AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT)

    private fun pcm(seconds: Double, amplitude: Short): ByteBuffer {
        val frames = (format.sampleRate * seconds).toInt()
        return ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(frames) { i -> val s = if (i % 2 == 0) amplitude else (-amplitude).toShort(); putShort(s); putShort(s) }
            flip()
        }
    }

    @Suppress("DEPRECATION")
    private fun detector(onSilence: () -> Unit) = SilenceDetectorAudioProcessor(onLongSilence = onSilence).apply {
        instantModeEnabled = true
        configure(format)
        flush()
    }

    @Test
    fun twoSecondsOfSilence_triggerOnce() {
        var calls = 0
        val d = detector { calls++ }
        repeat(25) { d.queueInput(pcm(0.1, 0)); d.output }
        assertEquals(1, calls)
        assertTrue(d.isCurrentlySilent())
    }

    @Test
    fun quietRoomNoise_isNotSilence() {
        var calls = 0
        val d = detector { calls++ }
        repeat(25) { d.queueInput(pcm(0.1, 400)); d.output }
        assertEquals(0, calls)
        assertFalse(d.isCurrentlySilent())
    }
}
