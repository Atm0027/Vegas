/**
 * Vegas desktop: the sync registers in the app's own database (tables sync_*).
 * Licensed under GPL-3.0
 */

package dev.alextorres.vegas.desktop.sync

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import dev.alextorres.vegas.desktop.db.SyncBindingEntity
import dev.alextorres.vegas.desktop.db.SyncEntryEntity
import dev.alextorres.vegas.desktop.db.SyncMetaEntity
import dev.alextorres.vegas.desktop.db.SyncPlaylistEntity
import dev.alextorres.vegas.desktop.db.SyncSettingEntity
import dev.alextorres.vegas.desktop.db.VegasDatabase
import dev.alextorres.vegas.sync.Changes
import dev.alextorres.vegas.sync.EntryReg
import dev.alextorres.vegas.sync.Hlc
import dev.alextorres.vegas.sync.PlaylistReg
import dev.alextorres.vegas.sync.SettingReg
import dev.alextorres.vegas.sync.SongMeta
import dev.alextorres.vegas.sync.SyncStore
import kotlinx.serialization.json.Json

class DesktopSyncStore(private val db: VegasDatabase) : SyncStore {
    private val dao = db.sync()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun playlists() = dao.playlists().associate { it.id to it.toReg() }

    override suspend fun entries() = dao.entries().associate { it.entryId to it.toReg() }

    override suspend fun settings() = dao.settings().associate { it.key to it.toReg() }

    override suspend fun putPlaylist(reg: PlaylistReg, seq: Long) =
        dao.put(SyncPlaylistEntity(reg.id, reg.name, reg.pinned, reg.customOrder, reg.deleted, reg.hlc.wallMs, reg.hlc.counter, reg.hlc.device, seq))

    override suspend fun putEntry(reg: EntryReg, seq: Long) =
        dao.put(
            SyncEntryEntity(
                reg.entryId, reg.playlistId, json.encodeToString(SongMeta.serializer(), reg.song), reg.sortKey, reg.deleted,
                reg.hlc.wallMs, reg.hlc.counter, reg.hlc.device, seq,
            ),
        )

    override suspend fun putSetting(reg: SettingReg, seq: Long) =
        dao.put(SyncSettingEntity(reg.key, reg.value, reg.hlc.wallMs, reg.hlc.counter, reg.hlc.device, seq))

    override suspend fun changedSince(seq: Long) =
        Changes(
            playlists = dao.playlistsSince(seq).map { it.toReg() },
            entries = dao.entriesSince(seq).map { it.toReg() },
            settings = dao.settingsSince(seq).map { it.toReg() },
            upToSeq = dao.meta(SEQ) ?: 0,
        )

    override suspend fun nextSeq(): Long = ((dao.meta(SEQ) ?: 0) + 1).also { dao.put(SyncMetaEntity(SEQ, it)) }

    override suspend fun entryIdFor(localKey: String) = dao.entryIdFor(localKey)

    override suspend fun localKeyFor(entryId: String) = dao.localKeyFor(entryId)

    override suspend fun bind(entryId: String, localKey: String) {
        dao.clearBinding(entryId, localKey)
        dao.put(SyncBindingEntity(entryId, localKey))
    }

    override suspend fun unbind(entryId: String) = dao.unbind(entryId)

    override suspend fun watermark(key: String) = dao.meta("wm:$key") ?: 0

    override suspend fun setWatermark(key: String, seq: Long) = dao.put(SyncMetaEntity("wm:$key", seq))

    override suspend fun <T> transaction(block: suspend () -> T): T =
        db.useWriterConnection { it.immediateTransaction { block() } }

    private fun SyncPlaylistEntity.toReg() = PlaylistReg(id, name, pinned, customOrder, deleted, Hlc(hlcWall, hlcCounter, hlcDevice))

    private fun SyncEntryEntity.toReg() =
        EntryReg(entryId, playlistId, json.decodeFromString(SongMeta.serializer(), song), sortKey, deleted, Hlc(hlcWall, hlcCounter, hlcDevice))

    private fun SyncSettingEntity.toReg() = SettingReg(key, value, Hlc(hlcWall, hlcCounter, hlcDevice))

    private companion object {
        const val SEQ = "seq"
    }
}
