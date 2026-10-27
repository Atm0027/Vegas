/**
 * Vegas: YouTube only streams age-restricted uploads to signed-in accounts and Vegas has none, so
 * when a track is age-restricted we look for another upload of the same song (official audio,
 * music video, lyric video, clean version) that YouTube does stream anonymously.
 * Licensed under GPL-3.0
 */

package com.metrolist.music.utils

import com.metrolist.innertube.models.SongItem
import java.text.Normalizer
import kotlin.math.abs

object AgeRestrictedFallback {
    const val MAX_CANDIDATES = 4
    private const val MAX_DURATION_DIFF_SECONDS = 25

    /** Variants that are a different recording, never a replacement for the original. */
    private val VARIANT_WORDS =
        listOf(
            "remix", "live", "en vivo", "directo", "cover", "karaoke", "instrumental", "sped up",
            "slowed", "reverb", "8d", "nightcore", "acapella", "acustic", "acoustic", "acustico", "mashup",
        )

    data class Original(
        val videoId: String,
        val title: String,
        val artists: List<String>,
        val durationSeconds: Int?,
    )

    /** The search query for alternatives: main artist plus title. */
    fun query(original: Original): String = listOfNotNull(original.artists.firstOrNull(), original.title).joinToString(" ")

    /** Candidates that are the same song, best first, without the original and without variants. */
    fun candidates(
        original: Original,
        results: List<SongItem>,
    ): List<String> {
        val title = normalize(baseTitle(original.title))
        if (title.isBlank()) return emptyList()
        val artists = original.artists.map(::normalize).filter(String::isNotBlank)
        val originalText = normalize(original.title)
        return results
            .asSequence()
            .filter { it.id != original.videoId && !it.isEpisode }
            .filter { item ->
                val itemTitle = normalize(item.title)
                val itemText = itemTitle + " " + item.artists.joinToString(" ") { normalize(it.name) }
                itemTitle.contains(title) &&
                    (artists.isEmpty() || artists.any { itemText.contains(it) }) &&
                    VARIANT_WORDS.none { word -> containsWord(itemTitle, word) && !containsWord(originalText, word) }
            }.filter { item ->
                val a = original.durationSeconds
                val b = item.duration
                a == null || b == null || abs(a - b) <= MAX_DURATION_DIFF_SECONDS
            }.sortedBy { item ->
                // Clean versions are the likeliest to stream without an account.
                if (item.explicit) 1 else 0
            }.map { it.id }
            .distinct()
            .take(MAX_CANDIDATES)
            .toList()
    }

    /** Drops featuring credits and bracketed notes: "Song (feat. X) [Letra]" -> "Song". */
    internal fun baseTitle(title: String): String =
        title
            .replace(Regex("""[(\[].*?[)\]]"""), " ")
            .replace(Regex("""\s(feat\.?|ft\.?|featuring)\s.*$""", RegexOption.IGNORE_CASE), " ")
            .trim()

    internal fun normalize(text: String): String =
        Normalizer
            .normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "")
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            .trim()

    private fun containsWord(
        text: String,
        word: String,
    ): Boolean = Regex("""(^|\s)${Regex.escape(word)}($|\s)""").containsMatchIn(text)
}
