package dev.alextorres.vegas.desktop.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

class ArtworkColorTest {
    private fun solid(argb: Int) = IntArray(64 * 64) { argb }

    @Test
    fun blueArtworkGivesABlueSeed() {
        val seed = seedFromPixels(solid(0xFF1E5AD2.toInt()))
        assertTrue("$seed", seed.blue > seed.red && seed.blue > seed.green)
    }

    @Test
    fun greenArtworkGivesAGreenSeed() {
        val seed = seedFromPixels(solid(0xFF2EA043.toInt()))
        assertTrue("$seed", seed.green > seed.red && seed.green > seed.blue)
    }

    @Test
    fun greyArtworkFallsBackToVegasRed() {
        assertTrue(seedFromPixels(solid(0xFF808080.toInt())) == DefaultThemeColor)
    }
}

class ArtworkColorLiveTest {
    @Test
    @Ignore("live network; run manually")
    fun realArtworkGivesItsOwnSeed() = kotlinx.coroutines.runBlocking {
        val loader = coil3.ImageLoader.Builder(coil3.PlatformContext.INSTANCE)
            .components { add(coil3.network.okhttp.OkHttpNetworkFetcherFactory()) }.build()
        val seed = artworkSeed("https://i.ytimg.com/vi/kJQP7kiw5Fk/hqdefault.jpg", loader)
        println("LIVESEED $seed")
        assertTrue(seed != null && seed != DefaultThemeColor)
    }
}
