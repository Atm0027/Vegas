package com.metrolist.music.utils

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyAccountCleanupTest {
    private val visitorData = stringPreferencesKey("visitorData")

    @Test
    fun accountKeys_areRemoved_visitorDataIsKept() {
        val prefs =
            mutablePreferencesOf(
                stringPreferencesKey("innerTubeCookie") to "SAPISID=x",
                stringPreferencesKey("accountName") to "Alex",
                stringPreferencesKey("dataSyncId") to "123",
                visitorData to "anon",
            )
        val names = LegacyAccountCleanup.keysToRemove(prefs).map { it.name }.toSet()
        assertEquals(setOf("innerTubeCookie", "accountName", "dataSyncId"), names)
        assertTrue(visitorData !in LegacyAccountCleanup.keysToRemove(prefs))
    }

    @Test
    fun cleanPreferences_needNothing() {
        assertTrue(LegacyAccountCleanup.keysToRemove(mutablePreferencesOf(visitorData to "anon")).isEmpty())
    }
}
