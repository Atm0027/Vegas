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
    /** Queue start: current first, rest shuffled. */
    fun fresh(
        currentIndex: Int,
        totalCount: Int,
        userAdded: Set<Int>?,
        random: Random = Random.Default,
    ): IntArray {
        if (totalCount <= 0) return IntArray(0)
        val rest = (0 until totalCount).filter { it != currentIndex }
        val head = if (currentIndex in 0 until totalCount) listOf(currentIndex) else emptyList()
        return (head + shuffleTail(rest, userAdded, random)).toIntArray()
    }

    /** Shuffle switched on mid-queue: songs before the current one stay as "previous" history. */
    fun enable(
        currentIndex: Int,
        totalCount: Int,
        userAdded: Set<Int>?,
        random: Random = Random.Default,
    ): IntArray {
        if (currentIndex !in 0 until totalCount) return fresh(0, totalCount, userAdded, random)
        val history = (0..currentIndex).toList()
        val rest = (currentIndex + 1 until totalCount).toList()
        return (history + shuffleTail(rest, userAdded, random)).toIntArray()
    }

    /** Queue grew: keep the order up to the current item, reshuffle only what has not played. */
    fun extend(
        order: IntArray,
        currentIndex: Int,
        addedIndices: Set<Int>,
        userAdded: Set<Int>?,
        random: Random = Random.Default,
    ): IntArray {
        val pos = order.indexOf(currentIndex)
        if (pos < 0) {
            return fresh(currentIndex.coerceIn(0, (order.size - 1).coerceAtLeast(0)), order.size, userAdded, random)
        }
        val history = order.take(pos + 1).filter { it !in addedIndices }
        val upcoming = order.filter { it !in history }
        return (history + shuffleTail(upcoming, userAdded, random)).toIntArray()
    }

    /** With [userAdded] (shuffle-playlist-first on), playlist songs play before user-added ones. */
    private fun shuffleTail(
        indices: List<Int>,
        userAdded: Set<Int>?,
        random: Random,
    ): List<Int> =
        if (userAdded.isNullOrEmpty()) {
            indices.shuffled(random)
        } else {
            indices.filter { it !in userAdded }.shuffled(random) + indices.filter { it in userAdded }.shuffled(random)
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
