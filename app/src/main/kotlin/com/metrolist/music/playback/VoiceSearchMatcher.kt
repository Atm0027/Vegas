package com.metrolist.music.playback

import com.metrolist.music.db.entities.Song
import java.text.Normalizer

object VoiceSearchMatcher {
    const val STRONG_MATCH_THRESHOLD = 0.60
    private val PUNCTUATION_REGEX = Regex("[^\\p{L}\\p{N}\\s]")
    const val FUZZY_THRESHOLD = 0.85

    data class ScoredSong(
        val song: Song,
        val score: Double
    )

    fun rankAll(query: String, candidates: List<Song>): List<ScoredSong> {
        val queryLower = normalizedLowercase(query).trim()
        val queryTokens = tokenize(queryLower)
        if (queryTokens.isEmpty()) return emptyList()

        val isTooGeneric = (queryTokens.size == 1 && queryTokens.first().length < 4) || queryLower.length < 5

        return candidates
            .map { song -> ScoredSong(song, scoreCandidate(song, queryLower, queryTokens, isTooGeneric)) }
            .sortedByDescending { it.score }
    }

    fun findBest(query: String, candidates: List<Song>): Song? {
        val ranks = rankAll(query, candidates)
        return ranks.firstOrNull { it.score >= STRONG_MATCH_THRESHOLD }?.song
    }

    private fun scoreCandidate(
        song: Song,
        queryLower: String,
        queryTokens: List<String>,
        isTooGeneric: Boolean,
    ): Double {
        val title = normalizedLowercase(song.title).trim()
        val titleTokens = tokenize(title)

        // Exact title (same words, same order) always wins; same words in another order is a near tie.
        if (titleTokens == queryTokens || title == queryLower) return 1.0
        val titleTokenSet = titleTokens.toSet()
        val queryTokenSet = queryTokens.toSet()
        if (titleTokenSet == queryTokenSet) return 0.99

        val remaining = stripArtistTokens(queryTokenSet, song)

        if (remaining.isEmpty()) return 0.5     // The query was purely artist name, so return moderate confidence
        if (titleTokenSet == remaining) return 0.95
        if (isTooGeneric) return 0.0

        // Capped so a fuzzy match never ties with an exact title.
        return fuzzyScore(remaining, titleTokenSet).coerceAtMost(0.98)
    }

    private fun stripArtistTokens(queryTokens: Set<String>, song: Song): Set<String> {
        val remaining = queryTokens.toMutableSet()
        song.artists.forEach { artist ->
            val artistTokens = tokenize(normalizedLowercase(artist.name)).toSet()
            remaining.removeIf { queryToken ->
                if (queryToken.length < 4) artistTokens.contains(queryToken)
                else artistTokens.any { artistToken -> jaroWinkler(queryToken, artistToken) >= 0.85 }
            }
        }
        return remaining
    }

    private fun fuzzyScore(queryTokens: Set<String>, titleTokens: Set<String>): Double {
        if (queryTokens.isEmpty() || titleTokens.isEmpty()) return 0.0

        val matchedQuery = queryTokens.count { q ->
            titleTokens.any { t -> q == t || jaroWinkler(q, t) >= FUZZY_THRESHOLD }
        }
        val matchedTitle = titleTokens.count { t ->
            queryTokens.any { q -> q == t || jaroWinkler(q, t) >= FUZZY_THRESHOLD }
        }

        if (matchedQuery == 0 || matchedTitle == 0) return 0.0

        val titleCoverage = matchedTitle.toDouble() / titleTokens.size
        val queryCoverage = matchedQuery.toDouble() / queryTokens.size
        return if (titleCoverage + queryCoverage > 0)
            (2.0 * titleCoverage * queryCoverage) / (titleCoverage + queryCoverage)   // harmonic mean
        else 0.0
    }


    /**
     * NFC first: a decomposed accent (NFD combining mark) would otherwise be replaced by a space
     * by [PUNCTUATION_REGEX] and split the word in two.
     */
    private fun normalizedLowercase(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFC).lowercase()

    /** Splits already-normalized text into words, keeping their order. */
    internal fun tokenize(text: String): List<String> =
        PUNCTUATION_REGEX.replace(text, " ")
            .split(" ")
            .filter { it.isNotBlank() }

    /**
     * Token Similarity based on Jaro-Winkler Distance Algorithm
     */
    internal fun jaroWinkler(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0

        val maxDist = (maxOf(s1.length, s2.length) / 2) - 1
        if (maxDist < 0) return 0.0

        var matches = 0
        val s1Matches = BooleanArray(s1.length)
        val s2Matches = BooleanArray(s2.length)

        for (i in s1.indices) {
            val start = maxOf(0, i - maxDist)
            val end = minOf(s2.length - 1, i + maxDist)
            for (j in start..end) {
                if (!s2Matches[j] && s1[i] == s2[j]) {
                    s1Matches[i] = true
                    s2Matches[j] = true
                    matches++
                    break
                }
            }
        }
        if (matches == 0) return 0.0

        var transpositions = 0
        var k = 0
        for (i in s1.indices) {
            if (s1Matches[i]) {
                while (!s2Matches[k]) k++
                if (s1[i] != s2[k]) transpositions++
                k++
            }
        }

        val jaro = (
                matches.toDouble() / s1.length +
                        matches.toDouble() / s2.length +
                        (matches - transpositions / 2.0) / matches
                ) / 3.0

        val prefix = s1.commonPrefixWith(s2).length.coerceAtMost(4)
        val p = 0.1
        val boostThreshold = 0.7
        return if (jaro > boostThreshold) jaro + (prefix * p * (1.0 - jaro)) else jaro
    }
}