/**
 * Vegas desktop: user settings in %APPDATA%\Vegas\settings.json. A broken file never stops the app.
 * Licensed under GPL-3.0
 */

package dev.alextorres.vegas.desktop.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

enum class AudioQualitySetting { AUTO, HIGH, LOW }

@Serializable
data class WindowBounds(val x: Int, val y: Int, val width: Int, val height: Int, val maximized: Boolean)

class DesktopSettings(private val file: Path) {
    @Serializable
    data class Values(
        val audioQuality: AudioQualitySetting = AudioQualitySetting.HIGH,
        val pureBlack: Boolean = false,
        val dynamicColor: Boolean = true,
        val volume: Float = 1f,
        val windowBounds: WindowBounds? = null,
        val hideExplicit: Boolean = false,
        // Stored for sync with the phone; playback support comes in a later phase.
        val skipSilence: Boolean = false,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val state = MutableStateFlow(load())
    val flow: StateFlow<Values> = state.asStateFlow()

    @Synchronized
    fun update(transform: (Values) -> Values) {
        val next = transform(state.value)
        if (next == state.value) return
        state.value = next
        runCatching {
            Files.createDirectories(file.toAbsolutePath().parent)
            val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
            Files.writeString(tmp, json.encodeToString(Values.serializer(), next))
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    private fun load(): Values =
        runCatching { json.decodeFromString(Values.serializer(), Files.readString(file)) }.getOrDefault(Values())
}
