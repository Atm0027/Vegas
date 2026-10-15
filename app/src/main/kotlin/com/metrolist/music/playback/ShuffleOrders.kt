/**
 * Vega: history-preserving shuffle orders.
 * Licensed under GPL-3.0
 */

package com.metrolist.music.playback

import androidx.media3.common.C
import androidx.media3.exoplayer.source.ShuffleOrder
import kotlin.random.Random

/**
 * Builds ExoPlayer shuffle orders. [extend] keeps everything up to the current item in place so
 * "previous" still reaches songs that already played after the queue grows.
 */
object ShuffleOrders {
    fun fresh(
        currentIndex: Int,
        totalCount: Int,
        originalQueueSize: Int,
        playlistFirst: Boolean,
        random: Random = Random.Default,
    ): IntArray {
        if (totalCount <= 0) return IntArray(0)
        val rest = (0 until totalCount).filter { it != currentIndex }
        val tail =
            if (playlistFirst && originalQueueSize in 1 until totalCount) {
                rest.filter { it < originalQueueSize }.shuffled(random) +
                    rest.filter { it >= originalQueueSize }.shuffled(random)
            } else {
                rest.shuffled(random)
            }
        val head = if (currentIndex in 0 until totalCount) listOf(currentIndex) else emptyList()
        return (head + tail).toIntArray()
    }

    fun extend(
        order: IntArray,
        currentIndex: Int,
        addedIndices: Set<Int>,
        keepAddedLast: Boolean,
        random: Random = Random.Default,
    ): IntArray {
        val pos = order.indexOf(currentIndex)
        if (pos < 0) {
            return fresh(currentIndex.coerceIn(0, (order.size - 1).coerceAtLeast(0)), order.size, 0, false, random)
        }
        val history = order.take(pos + 1).filter { it !in addedIndices }
        val pending = order.drop(pos + 1).filter { it !in addedIndices }
        val added = order.filter { it in addedIndices }
        val tail =
            if (keepAddedLast) {
                pending.shuffled(random) + added.shuffled(random)
            } else {
                (pending + added).shuffled(random)
            }
        return (history + tail).toIntArray()
    }

    fun remainingAfter(
        order: IntArray,
        currentIndex: Int,
    ): Int {
        val pos = order.indexOf(currentIndex)
        return if (pos < 0) 0 else order.size - 1 - pos
    }
}

fun ShuffleOrder.toIntArray(itemCount: Int): IntArray {
    if (length == 0 || itemCount == 0) return IntArray(0)
    val out = ArrayList<Int>(length)
    var index = firstIndex
    while (index != C.INDEX_UNSET && out.size < length) {
        out.add(index)
        index = getNextIndex(index)
    }
    return out.toIntArray()
}
