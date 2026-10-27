/**
 * Vegas: keeps the Google session used only for playback in its own SharedPreferences file, out of
 * Vegas backups and Android cloud backups (see backup_rules.xml), since the cookie works like a password.
 * Licensed under GPL-3.0
 */

package com.metrolist.music.utils

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object PlaybackAccountStore {
    const val FILE = "playback_account"
    private const val COOKIE = "cookie"
    private const val VISITOR_DATA = "visitorData"

    private val _session = MutableStateFlow<InnerTubeXPlayer.AgeVerificationSession?>(null)
    val session: StateFlow<InnerTubeXPlayer.AgeVerificationSession?> = _session.asStateFlow()

    @Volatile
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        val prefs = context.prefs()
        _session.value =
            prefs.getString(COOKIE, null)?.takeIf(String::isNotBlank)?.let {
                InnerTubeXPlayer.AgeVerificationSession(it, prefs.getString(VISITOR_DATA, null))
            }
        loaded = true
    }

    fun save(
        context: Context,
        cookie: String,
        visitorData: String?,
    ) {
        context.prefs().edit(commit = true) {
            putString(COOKIE, cookie)
            if (visitorData != null) putString(VISITOR_DATA, visitorData) else remove(VISITOR_DATA)
        }
        _session.value = InnerTubeXPlayer.AgeVerificationSession(cookie, visitorData)
    }

    fun clear(context: Context) {
        context.prefs().edit(commit = true) { clear() }
        _session.value = null
    }

    private fun Context.prefs() = applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
