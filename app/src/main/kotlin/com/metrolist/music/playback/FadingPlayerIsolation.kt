/**
 * Vegas: the player fading out during a crossfade must only hold the track it is fading. Trimming
 * just the items after the current index is not enough with shuffle on, where the next song in
 * play order can sit at a lower index; the old player then started that song too and its intro
 * played twice at once.
 * Licensed under GPL-3.0
 */

package com.metrolist.music.playback

object FadingPlayerIsolation {
    /** Index ranges to remove so only [currentIndex] remains; later range first so indices stay valid. */
    fun rangesToRemove(currentIndex: Int, itemCount: Int): List<IntRange> =
        buildList {
            if (currentIndex + 1 < itemCount) add(currentIndex + 1 until itemCount)
            if (currentIndex > 0) add(0 until currentIndex)
        }
}
