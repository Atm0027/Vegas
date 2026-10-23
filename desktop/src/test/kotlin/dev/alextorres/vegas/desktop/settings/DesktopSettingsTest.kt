package dev.alextorres.vegas.desktop.settings

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class DesktopSettingsTest {
    private val file = Files.createTempFile("settings", ".json")

    @After
    fun tearDown() {
        Files.deleteIfExists(file)
    }

    @Test
    fun settingsRoundTrip() {
        DesktopSettings(file).update { it.copy(audioQuality = AudioQualitySetting.LOW, pureBlack = true, volume = 0.4f, hideExplicit = true) }
        val reloaded = DesktopSettings(file).flow.value
        assertEquals(AudioQualitySetting.LOW, reloaded.audioQuality)
        assertEquals(true, reloaded.pureBlack)
        assertEquals(0.4f, reloaded.volume, 0.0001f)
        assertEquals(true, reloaded.hideExplicit)
    }

    @Test
    fun corruptSettingsFileFallsBackToDefaults() {
        Files.writeString(file, "\u0000garbage{")
        assertEquals(DesktopSettings.Values(), DesktopSettings(file).flow.value)
    }

    @Test
    fun missingFileGivesDefaults() {
        Files.delete(file)
        val values = DesktopSettings(file).flow.value
        assertEquals(AudioQualitySetting.HIGH, values.audioQuality)
        assertEquals(true, values.dynamicColor)
        assertEquals(1f, values.volume, 0.0001f)
    }
}
