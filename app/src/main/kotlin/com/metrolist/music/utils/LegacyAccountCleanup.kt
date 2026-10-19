/**
 * Vegas: no YouTube account and no Discord. Removes the data that older installs or restored
 * Metrolist backups may still carry. The anonymous visitorData is kept on purpose: account-less
 * playback depends on it. Only a visitorData minted next to a signed-in cookie is dropped, since it
 * may be tied to that session; a fresh anonymous one is generated on demand.
 * Licensed under GPL-3.0
 */

package com.metrolist.music.utils

import androidx.datastore.preferences.core.Preferences

object LegacyAccountCleanup {
    private const val COOKIE = "innerTubeCookie"
    private const val VISITOR_DATA = "visitorData"
    private const val DISCORD_PREFIX = "discord"

    /** SharedPreferences file where Metrolist kept its encrypted Discord tokens. */
    const val DISCORD_TOKEN_PREFS = "discord_token"

    private val ACCOUNT_KEYS =
        setOf(
            COOKIE,
            "innerTubeAuthUser",
            "accountName",
            "accountEmail",
            "accountChannelHandle",
            "dataSyncId",
            "ytmSync",
            "useLoginForBrowse",
            "last_full_sync",
        )

    fun keysToRemove(prefs: Preferences): List<Preferences.Key<*>> {
        val keys = prefs.asMap().keys
        val signedIn = keys.any { it.name == COOKIE }
        return keys.filter {
            it.name in ACCOUNT_KEYS ||
                it.name.startsWith(DISCORD_PREFIX) ||
                (signedIn && it.name == VISITOR_DATA)
        }
    }
}
