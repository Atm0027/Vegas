/**
 * Vegas: what happens when another app (a game, a video) takes the audio.
 * By default Vegas pauses, but keeps its foreground service for 10 minutes so Android does not kill it
 * to free memory; coming back resumes where it was. With «Seguir sonando» it keeps playing instead.
 * Licensed under GPL-3.0
 */

package com.metrolist.music.playback

object FocusLoss {
    const val HOLD_MS = 10 * 60_000L

    enum class Action { PAUSE, KEEP_PLAYING }

    fun onLoss(keepPlaying: Boolean): Action = if (keepPlaying) Action.KEEP_PLAYING else Action.PAUSE

    /** True while a pause caused by another app is recent enough to keep the service in the foreground. */
    fun holdForeground(pausedAt: Long?, now: Long): Boolean = pausedAt != null && now - pausedAt in 0..HOLD_MS
}
