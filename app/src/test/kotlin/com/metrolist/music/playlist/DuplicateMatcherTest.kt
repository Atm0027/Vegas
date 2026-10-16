package com.metrolist.music.playlist

import org.junit.Assert.assertEquals
import org.junit.Test

class DuplicateMatcherTest {
    private fun c(id: String, t: String, a: String?, d: Int) = Candidate(id, t, a, d)

    @Test fun sameId_isExact() =
        assertEquals(Match.Exact("A1"), DuplicateMatcher.match(c("A1", "Africa", "Toto", 295), listOf(c("A1", "Africa", "Toto", 295))))

    @Test fun videoVsAlbumVersion_isProbable() {
        val existing = c("A1", "Africa", "Toto", 295)
        val m = DuplicateMatcher.match(c("V9", "Africa (Official HD Video)", "TOTO", 297), listOf(existing))
        assertEquals(Match.Probable(existing), m)
    }

    @Test fun sameTitleOtherArtist_isNew() =
        assertEquals(Match.New, DuplicateMatcher.match(c("B2", "Hold On", "Justin Bieber", 170), listOf(c("C3", "Hold On", "Chord Overstreet", 198))))

    @Test fun sameSongVeryDifferentDuration_isNew() =
        assertEquals(Match.New, DuplicateMatcher.match(c("L1", "Africa (Live)", "Toto", 420), listOf(c("A1", "Africa", "Toto", 295))))

    @Test fun unknownDuration_isNeverProbable() =
        assertEquals(Match.New, DuplicateMatcher.match(c("V9", "Africa", "Toto", 0), listOf(c("A1", "Africa", "Toto", 295))))

    @Test fun existingWithUnknownDuration_isNeverProbable() =
        assertEquals(Match.New, DuplicateMatcher.match(c("V9", "Africa", "Toto", 295), listOf(c("A1", "Africa", "Toto", 0))))

    @Test fun missingArtist_isNeverProbable() =
        assertEquals(Match.New, DuplicateMatcher.match(c("V9", "Africa", null, 295), listOf(c("A1", "Africa", null, 295))))

    @Test fun normalize_stripsAccentsVersionTagsAndFeat() =
        assertEquals("me quedare", DuplicateMatcher.normalize("Me Quedaré (Remastered 2011) [Lyrics] feat. Fito"))
}
