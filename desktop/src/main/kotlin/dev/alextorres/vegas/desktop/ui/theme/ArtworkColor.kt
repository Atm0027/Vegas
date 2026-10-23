/**
 * Vegas desktop: picks the theme seed from the current artwork, like extractThemeColor() on the phone.
 * Licensed under GPL-3.0
 */

package dev.alextorres.vegas.desktop.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import com.materialkolor.quantize.QuantizerCelebi
import com.materialkolor.score.Score
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun rememberArtworkSeed(url: String?, enabled: Boolean, loader: ImageLoader): Color {
    var seed by remember { mutableStateOf(DefaultThemeColor) }
    LaunchedEffect(url, enabled) {
        seed = if (!enabled || url == null) DefaultThemeColor else artworkSeed(url, loader) ?: DefaultThemeColor
    }
    return seed
}

internal suspend fun artworkSeed(url: String, loader: ImageLoader): Color? =
    withContext(Dispatchers.Default) {
        val request = ImageRequest.Builder(PlatformContext.INSTANCE).data(url).size(64).build()
        val bitmap = (loader.execute(request) as? SuccessResult)?.image?.toBitmap() ?: return@withContext null
        val pixels = IntArray(bitmap.width * bitmap.height)
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) pixels[y * bitmap.width + x] = bitmap.getColor(x, y)
        seedFromPixels(pixels)
    }

/** Most suitable theme colour in the pixels (Material's scoring), or Vegas red for colourless artwork. */
internal fun seedFromPixels(pixels: IntArray): Color =
    Color(Score.score(QuantizerCelebi.quantize(pixels, 128), 1, DefaultThemeColor.toArgb(), true).first())
