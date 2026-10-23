/**
 * Vegas desktop: small UI helpers shared by every screen.
 * Licensed under GPL-3.0
 */

package dev.alextorres.vegas.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

private val sizedGoogleImage = Regex("=w\\d+-h\\d+.*$")

/** Asks YouTube's image servers for a size close to what is drawn, like resize() on the phone. */
fun thumbnail(url: String?, px: Int): String? =
    when {
        url == null -> null
        url.contains("googleusercontent.com") && sizedGoogleImage.containsMatchIn(url) ->
            url.replace(sizedGoogleImage, "=w$px-h$px-l90-rj")
        url.contains("googleusercontent.com") && !url.contains('=') -> "$url=w$px-h$px-l90-rj"
        else -> url
    }

@Composable
fun Artwork(
    url: String?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
    px: Int = 226,
) {
    Box(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        AsyncImage(
            model = thumbnail(url, px),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
        )
    }
}

fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
