package com.metrolist.music.utils

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyAccountCleanupTest {
    private val visitorData = stringPreferencesKey("visitorData")

    @Test
    fun signedInLeftovers_removeAccountKeysAndTheirVisitorData() {
        val prefs =
            mutablePreferencesOf(
                stringPreferencesKey("innerTubeCookie") to "SAPISID=x",
                stringPreferencesKey("accountName") to "Alex",
                stringPreferencesKey("dataSyncId") to "123",
                visitorData to "signed-in",
            )
        val names = LegacyAccountCleanup.keysToRemove(prefs).map { it.name }.toSet()
        assertEquals(setOf("innerTubeCookie", "accountName", "dataSyncId", "visitorData"), names)
    }

    @Test
    fun withoutCookie_anonymousVisitorDataIsKept() {
        val prefs =
            mutablePreferencesOf(
                stringPreferencesKey("accountName") to "Alex",
                visitorData to "anon",
            )
        val names = LegacyAccountCleanup.keysToRemove(prefs).map { it.name }.toSet()
        assertEquals(setOf("accountName"), names)
    }

    @Test
    fun discordKeys_areRemoved() {
        val prefs =
            mutablePreferencesOf(
                booleanPreferencesKey("discordRPCEnable") to true,
                stringPreferencesKey("discordUsername") to "alex",
                stringPreferencesKey("discordButton1Url") to "https://x",
                visitorData to "anon",
            )
        val names = LegacyAccountCleanup.keysToRemove(prefs).map { it.name }.toSet()
        assertEquals(setOf("discordRPCEnable", "discordUsername", "discordButton1Url"), names)
    }

    @Test
    fun cleanPreferences_needNothing() {
        assertTrue(LegacyAccountCleanup.keysToRemove(mutablePreferencesOf(visitorData to "anon")).isEmpty())
    }
}
