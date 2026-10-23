/**
 * Vegas desktop: where decoded 16-bit little-endian PCM goes.
 * Licensed under GPL-3.0
 */

package dev.alextorres.vegas.desktop.playback

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

interface PcmSink {
    var volume: Float

    fun open(sampleRate: Int, channels: Int)

    fun write(buf: ByteArray, len: Int)

    /** Drops audio that was written but not played yet. */
    fun flush()

    fun close()

    /** Stops output without losing what is buffered. */
    fun pause() = Unit

    fun resume() = Unit
}

/** The system's default output device; volume is applied in software so it works on every device. */
class JavaSoundSink : PcmSink {
    private var line: SourceDataLine? = null

    @Volatile
    override var volume: Float = 1f

    override fun open(sampleRate: Int, channels: Int) {
        val format = AudioFormat(sampleRate.toFloat(), 16, channels, true, false)
        // About a quarter of a second of buffer: small enough for snappy pause and seek.
        line = AudioSystem.getSourceDataLine(format).apply {
            open(format, sampleRate * channels * 2 / 4)
            start()
        }
    }

    override fun write(buf: ByteArray, len: Int) {
        val gain = volume.coerceIn(0f, 1f)
        if (gain < 1f) {
            var i = 0
            while (i + 1 < len) {
                val sample = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xff)).toShort()
                val scaled = (sample * gain).toInt()
                buf[i] = (scaled and 0xff).toByte()
                buf[i + 1] = (scaled shr 8).toByte()
                i += 2
            }
        }
        val l = checkNotNull(line) { "Sink not open" }
        var offset = 0
        while (offset < len) offset += l.write(buf, offset, len - offset)
    }

    override fun flush() {
        line?.flush()
    }

    override fun pause() {
        line?.stop()
    }

    override fun resume() {
        line?.start()
    }

    override fun close() {
        line?.run {
            stop()
            close()
        }
        line = null
    }
}
