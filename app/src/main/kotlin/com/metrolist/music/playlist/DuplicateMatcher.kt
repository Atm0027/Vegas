/**
 * Vega: decides whether a song being added to a playlist is already there.
 * Exact = same YouTube id. Probable = another upload of the same recording (same normalized title and
 * main artist, duration within a few seconds). Songs that merely share a title stay New, and anything
 * with unknown duration or artist is never treated as probable.
 * Licensed under GPL-3.0
 */

package com.metrolist.music.playlist

import java.text.Normalizer
import kotlin.math.abs

object DuplicateMatcher {
    private const val DURATION_TOLERANCE_SEC = 3

    private val DIACRITICS = Regex("""\p{Mn}+""")
    private val BRACKETED = Regex("""\([^)]*\)|\[[^]]*]""")
    private val FEATURING = Regex("""\s(feat\.|ft\.|featuring)\s.*$""")
    private val SPACES = Regex("""\s+""")

    fun normalize(text: String): String {
        val plain = DIACRITICS.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase()
        val noTags = BRACKETED.replace(plain, " ")
        return SPACES.replace(FEATURING.replace(" $noTags ", " "), " ").trim()
    }

    fun match(candidate: Candidate, existing: List<Candidate>): Match {
        existing.firstOrNull { it.id == candidate.id }?.let { return Match.Exact(it.id) }
        if (candidate.durationSec <= 0 || candidate.artist.isNullOrBlank()) return Match.New

        val title = normalize(candidate.title)
        val artist = normalize(candidate.artist)
        if (title.isEmpty() || artist.isEmpty()) return Match.New
        val probable =
            existing.firstOrNull {
                it.durationSec > 0 &&
                    !it.artist.isNullOrBlank() &&
                    abs(it.durationSec - candidate.durationSec) <= DURATION_TOLERANCE_SEC &&
                    normalize(it.title) == title &&
                    normalize(it.artist) == artist
            }
        return probable?.let { Match.Probable(it) } ?: Match.New
    }
}

data class Candidate(val id: String, val title: String, val artist: String?, val durationSec: Int)

sealed interface Match {
    data object New : Match

    data class Exact(val existingId: String) : Match

    data class Probable(val existing: Candidate) : Match
}
