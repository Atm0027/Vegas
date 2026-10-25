/**
 * Vegas sync: what travels between the phone and the PC.
 *
 * Every playlist, playlist entry and synced setting is a last-change-wins register stamped with a
 * hybrid logical clock. Deletions are kept as tombstones so they never come back. Entries have their
 * own global id (a playlist may hold the same song twice) and a fractional sort key, so inserts made on
 * both devices before they meet never collide.
 * Licensed under GPL-3.0
 */

package dev.alextorres.vegas.sync

import kotlinx.serialization.Serializable

@Serializable
data class Hlc(val wallMs: Long, val counter: Int, val device: String) : Comparable<Hlc> {
    override fun compareTo(other: Hlc): Int =
        compareValuesBy(this, other, Hlc::wallMs, Hlc::counter, Hlc::device)
}

/** Hybrid logical clock: wall time when it moves forward, a counter when it does not. */
class HlcClock(private val device: String, private val now: () -> Long = System::currentTimeMillis) {
    private var last = Hlc(0, 0, device)

    @Synchronized
    fun tick(): Hlc {
        val wall = now()
        last = if (wall > last.wallMs) Hlc(wall, 0, device) else Hlc(last.wallMs, last.counter + 1, device)
        return last
    }

    @Synchronized
    fun observe(remote: Hlc) {
        if (remote.wallMs > last.wallMs || (remote.wallMs == last.wallMs && remote.counter > last.counter)) {
            last = Hlc(remote.wallMs, remote.counter, device)
        }
    }
}

@Serializable
data class SongMeta(
    val id: String,
    val title: String,
    val artists: String,
    val album: String? = null,
    val thumbnailUrl: String? = null,
    val durationMs: Long? = null,
)

@Serializable
data class PlaylistReg(
    val id: String,
    val name: String,
    val pinned: Boolean,
    val customOrder: Int,
    val deleted: Boolean,
    val hlc: Hlc,
)

@Serializable
data class EntryReg(
    val entryId: String,
    val playlistId: String,
    val song: SongMeta,
    val sortKey: Double,
    val deleted: Boolean,
    val hlc: Hlc,
)

@Serializable
data class SettingReg(val key: String, val value: String, val hlc: Hlc)

@Serializable
data class Changes(
    val playlists: List<PlaylistReg> = emptyList(),
    val entries: List<EntryReg> = emptyList(),
    val settings: List<SettingReg> = emptyList(),
    /** The sender's change counter after these changes; the receiver echoes it back next time. */
    val upToSeq: Long = 0,
)

@Serializable
data class SyncRequest(val deviceId: String, val changes: Changes, val sinceSeq: Long)

@Serializable
data class SyncResponse(val changes: Changes)

@Serializable
data class PairRequest(val deviceId: String, val deviceName: String, val nonce: String, val proof: String)

@Serializable
data class PairResponse(val token: String, val serverId: String, val serverName: String)

/** A device's own library as it is now, read by its [LibraryAdapter]. */
data class LibrarySnapshot(val playlists: List<LocalPlaylist>, val settings: Map<String, String>)

data class LocalPlaylist(
    val id: String,
    val name: String,
    val pinned: Boolean,
    val customOrder: Int,
    val entries: List<LocalEntry>,
)

/** [localKey] identifies the row in the device's own database (row id on Android, entry id on the PC). */
data class LocalEntry(val localKey: String, val song: SongMeta)

/** How a playlist must look after applying remote changes; [DesiredEntry.localKey] is null for rows to create. */
data class DesiredPlaylist(val reg: PlaylistReg, val entries: List<DesiredEntry>)

data class DesiredEntry(val entryId: String, val localKey: String?, val song: SongMeta)

data class AppliedChanges(
    val playlists: List<DesiredPlaylist>,
    val deletedPlaylistIds: List<String>,
    val settings: Map<String, String>,
)

/** Reads and writes one device's library. */
interface LibraryAdapter {
    suspend fun snapshot(): LibrarySnapshot

    /** Makes the library match; returns the local key of every row it created, by entry id. */
    suspend fun apply(changes: AppliedChanges): Map<String, String>
}
