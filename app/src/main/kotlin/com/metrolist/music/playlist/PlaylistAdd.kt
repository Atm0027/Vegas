package com.metrolist.music.playlist

import com.metrolist.music.db.DatabaseDao
import com.metrolist.music.db.entities.Song

fun Song.toCandidate() = Candidate(id, song.title, orderedArtists.firstOrNull()?.name, song.duration)

/** Plans adding [songIds] (already in the database) to a playlist, comparing against its current songs. */
suspend fun DatabaseDao.planPlaylistAdd(
    playlistId: String,
    songIds: List<String>,
): AddPlan {
    val existing = playlistSongs(playlistId, Int.MAX_VALUE, 0).map { it.song.toCandidate() }
    val candidates = songIds.mapNotNull { getSongById(it)?.toCandidate() }
    return PlaylistAddPlanner.plan(candidates, existing)
}
