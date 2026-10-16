/**
 * Vega: no YouTube account. Removes account data that older installs or restored
 * Metrolist backups may still carry. The anonymous visitorData is kept on purpose:
 * account-less playback depends on it.
 * Licensed under GPL-3.0
 */

package com.metrolist.music.utils

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

object LegacyAccountCleanup {
    private val KEYS: List<Preferences.Key<*>> =
        listOf(
            stringPreferencesKey("innerTubeCookie"),
            stringPreferencesKey("innerTubeAuthUser"),
            stringPreferencesKey("accountName"),
            stringPreferencesKey("accountEmail"),
            stringPreferencesKey("accountChannelHandle"),
            stringPreferencesKey("dataSyncId"),
            booleanPreferencesKey("ytmSync"),
            booleanPreferencesKey("useLoginForBrowse"),
            longPreferencesKey("last_full_sync"),
        )

    fun keysToRemove(prefs: Preferences): List<Preferences.Key<*>> = KEYS.filter { it in prefs }
}
