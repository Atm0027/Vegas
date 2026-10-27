/**
 * Vegas: do not start a new PoToken WebView while the phone is short of memory.
 * A WebView renderer costs 50-100 MB; spawning one while the system is already killing apps
 * (e.g. a game just opened) got Vegas itself killed. Without a PoToken, extraction falls back
 * to clients that do not need one.
 * Licensed under GPL-3.0
 */

package com.metrolist.music.utils.potoken

object PoTokenMemory {
    /** How long after a sign of memory pressure new WebViews are avoided. */
    const val COOLDOWN_MS = 2 * 60_000L

    fun skipNewWebView(lowMemory: Boolean, pressureAt: Long?, now: Long): Boolean =
        lowMemory || (pressureAt != null && now - pressureAt in 0 until COOLDOWN_MS)
}
