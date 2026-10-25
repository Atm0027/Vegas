/**
 * Vegas: names of downloaded files in Music/Vegas, "Artist - Title [videoId].ext". The trailing
 * [videoId] lets a fresh install recognise its files without reading tags.
 * Same as the phone's copy in app/…/offline; keep both in step.
 * Licensed under GPL-3.0
 */

package dev.alextorres.vegas.desktop.offline

object OfflineFileName {
    data class Parsed(val artist: String?, val title: String, val videoId: String)

    private const val MAX_TEXT = 150
    private val invalid = Regex("[/\\\\:*?\"<>|\\p{Cntrl}]")
    private val idPattern = Regex("""\[([A-Za-z0-9_-]{11})](?: \(\d+\))?\.[A-Za-z0-9]+$""")

    fun build(artist: String?, title: String, videoId: String, ext: String): String {
        val text = listOfNotNull(artist?.takeIf { it.isNotBlank() }, title)
            .joinToString(" - ") { clean(it) }
            .take(MAX_TEXT)
            .trimEnd()
        return "$text [$videoId].$ext"
    }

    fun videoIdOf(fileName: String): String? = idPattern.find(fileName)?.groupValues?.get(1)

    fun parse(fileName: String): Parsed? {
        val match = idPattern.find(fileName) ?: return null
        val text = fileName.substring(0, match.range.first).trimEnd()
        val sep = text.indexOf(" - ")
        return if (sep < 0) {
            Parsed(null, text, match.groupValues[1])
        } else {
            Parsed(text.substring(0, sep), text.substring(sep + 3), match.groupValues[1])
        }
    }

    private fun clean(s: String) = s.replace(invalid, "_").trim()
}
