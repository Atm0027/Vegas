/**
 * Vegas for Windows: wires playback, library and settings, then opens the window.
 * Licensed under GPL-3.0
 */

package dev.alextorres.vegas.desktop

import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.innertube.models.YouTubeLocale
import com.metrolist.innertube.models.filterPodcasts
import dev.alextorres.vegas.desktop.db.Library
import dev.alextorres.vegas.desktop.db.openDatabase
import dev.alextorres.vegas.desktop.playback.AudioEngine
import dev.alextorres.vegas.desktop.playback.PlayerController
import dev.alextorres.vegas.desktop.playback.QueueStore
import dev.alextorres.vegas.desktop.playback.StreamResolver
import dev.alextorres.vegas.desktop.playback.toTrack
import dev.alextorres.vegas.desktop.settings.AudioQualitySetting
import dev.alextorres.vegas.desktop.settings.DesktopSettings
import dev.alextorres.vegas.desktop.settings.WindowBounds
import dev.alextorres.vegas.desktop.ui.AppShell
import dev.alextorres.vegas.desktop.ui.Destination
import dev.alextorres.vegas.desktop.ui.player.PlayerActions
import dev.alextorres.vegas.desktop.ui.theme.VegasTheme
import dev.alextorres.vegas.desktop.ui.theme.rememberArtworkSeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import com.metrolist.innertubex.extraction.AudioQuality as InnerTubeXAudioQuality

/** %APPDATA%\Vegas: database, settings, saved queue and crash reports. */
val dataDir: Path = Path.of(System.getenv("APPDATA") ?: System.getProperty("user.home"), "Vegas")

private fun installCrashLog() {
    val dir = dataDir.resolve("crashes")
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        runCatching {
            Files.createDirectories(dir)
            val name = "crash-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".txt"
            Files.writeString(dir.resolve(name), "${thread.name}\n${error.stackTraceToString()}")
            // Keep the last five reports, like CrashLogStore on the phone.
            Files.list(dir).use { files -> files.sorted(Comparator.reverseOrder()).skip(5).forEach(Files::delete) }
        }
        previous?.uncaughtException(thread, error)
    }
}

fun main() {
    installCrashLog()
    YouTube.locale = YouTubeLocale(gl = "ES", hl = "es")
    val settings = DesktopSettings(dataDir.resolve("settings.json"))
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val engine = AudioEngine().apply { volume = settings.flow.value.volume }
    val resolver = StreamResolver.live {
        when (settings.flow.value.audioQuality) {
            AudioQualitySetting.HIGH -> InnerTubeXAudioQuality.HIGH
            AudioQualitySetting.LOW -> InnerTubeXAudioQuality.LOW
            AudioQualitySetting.AUTO -> InnerTubeXAudioQuality.AUTO
        }
    }
    val controller = PlayerController(
        scope = scope,
        resolver = resolver,
        engine = engine,
        related = { track ->
            YouTube.next(WatchEndpoint(videoId = track.id, playlistId = "RDAMVM${track.id}"))
                .getOrNull()?.items.orEmpty().filterPodcasts().map { it.toTrack() }
        },
        store = QueueStore(dataDir.resolve("queue.json")),
    )
    val library = Library(openDatabase(dataDir.resolve("vegas.db")))

    application {
        setSingletonImageLoaderFactory { context ->
            ImageLoader.Builder(context).components { add(OkHttpNetworkFetcherFactory()) }.build()
        }
        val saved = settings.flow.value.windowBounds
        val windowState = rememberWindowState(
            size = saved?.let { DpSize(it.width.dp, it.height.dp) } ?: DpSize(1280.dp, 800.dp),
            position = saved?.let { WindowPosition(it.x.dp, it.y.dp) } ?: WindowPosition.PlatformDefault,
            placement = if (saved?.maximized == true) WindowPlacement.Maximized else WindowPlacement.Floating,
        )
        Window(
            onCloseRequest = {
                settings.update {
                    it.copy(
                        windowBounds = WindowBounds(
                            windowState.position.x.value.toInt(),
                            windowState.position.y.value.toInt(),
                            windowState.size.width.value.toInt(),
                            windowState.size.height.value.toInt(),
                            windowState.placement == WindowPlacement.Maximized,
                        ),
                    )
                }
                engine.release()
                exitApplication()
            },
            title = "Vegas",
            icon = painterResource("vegas.png"),
            state = windowState,
        ) {
            window.minimumSize = java.awt.Dimension(900, 600)
            val prefs by settings.flow.collectAsState()
            val player by controller.state.collectAsState()
            var destination by remember { mutableStateOf(Destination.HOME) }
            var fullPlayer by remember { mutableStateOf(false) }
            val seed = rememberArtworkSeed(player.current?.thumbnailUrl, prefs.dynamicColor, coil3.SingletonImageLoader.get(PlatformContext.INSTANCE))
            val actions = remember {
                PlayerActions(
                    togglePlay = controller::togglePlay,
                    next = controller::next,
                    previous = controller::previous,
                    seekTo = controller::seekTo,
                    setShuffle = controller::setShuffle,
                    cycleRepeat = controller::cycleRepeat,
                    playAt = controller::playAt,
                    removeFromQueue = controller::removeFromQueue,
                    moveInQueue = controller::moveInQueue,
                )
            }
            VegasTheme(seed = seed, pureBlack = prefs.pureBlack) {
                AppShell(
                    destination = destination,
                    onDestination = { destination = it; fullPlayer = false },
                    player = player,
                    actions = actions,
                    volume = prefs.volume,
                    onVolume = { v ->
                        engine.volume = v
                        settings.update { it.copy(volume = v) }
                    },
                    fullPlayerOpen = fullPlayer,
                    onFullPlayer = { fullPlayer = it },
                    onDismissMessage = controller::dismissMessage,
                ) {
                    Text(destination.label)
                }
            }
        }
    }
}
