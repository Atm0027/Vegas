/**
 * Vegas sync: where a device keeps its registers, the entry id ↔ local row bindings and the
 * watermarks of what it has exchanged with the other device. Each platform implements it on Room;
 * [InMemorySyncStore] serves tests.
 * Licensed under GPL-3.0
 */

package dev.alextorres.vegas.sync

interface SyncStore {
    suspend fun playlists(): Map<String, PlaylistReg>

    suspend fun entries(): Map<String, EntryReg>

    suspend fun settings(): Map<String, SettingReg>

    suspend fun putPlaylist(reg: PlaylistReg, seq: Long)

    suspend fun putEntry(reg: EntryReg, seq: Long)

    suspend fun putSetting(reg: SettingReg, seq: Long)

    /** Registers written after [seq]; `upToSeq` is the current counter. */
    suspend fun changedSince(seq: Long): Changes

    suspend fun nextSeq(): Long

    suspend fun entryIdFor(localKey: String): String?

    suspend fun localKeyFor(entryId: String): String?

    suspend fun bind(entryId: String, localKey: String)

    suspend fun unbind(entryId: String)

    suspend fun watermark(key: String): Long

    suspend fun setWatermark(key: String, seq: Long)

    suspend fun <T> transaction(block: suspend () -> T): T
}

class InMemorySyncStore : SyncStore {
    private val playlists = linkedMapOf<String, Pair<PlaylistReg, Long>>()
    private val entries = linkedMapOf<String, Pair<EntryReg, Long>>()
    private val settings = linkedMapOf<String, Pair<SettingReg, Long>>()
    private val keyById = mutableMapOf<String, String>()
    private val idByKey = mutableMapOf<String, String>()
    private val watermarks = mutableMapOf<String, Long>()
    private var seq = 0L

    override suspend fun playlists() = playlists.mapValues { it.value.first }

    override suspend fun entries() = entries.mapValues { it.value.first }

    override suspend fun settings() = settings.mapValues { it.value.first }

    override suspend fun putPlaylist(reg: PlaylistReg, seq: Long) {
        playlists[reg.id] = reg to seq
    }

    override suspend fun putEntry(reg: EntryReg, seq: Long) {
        entries[reg.entryId] = reg to seq
    }

    override suspend fun putSetting(reg: SettingReg, seq: Long) {
        settings[reg.key] = reg to seq
    }

    override suspend fun changedSince(seq: Long) =
        Changes(
            playlists = playlists.values.filter { it.second > seq }.map { it.first },
            entries = entries.values.filter { it.second > seq }.map { it.first },
            settings = settings.values.filter { it.second > seq }.map { it.first },
            upToSeq = this.seq,
        )

    override suspend fun nextSeq() = ++seq

    override suspend fun entryIdFor(localKey: String) = idByKey[localKey]

    override suspend fun localKeyFor(entryId: String) = keyById[entryId]

    override suspend fun bind(entryId: String, localKey: String) {
        keyById.remove(entryId)?.let(idByKey::remove)
        keyById[entryId] = localKey
        idByKey[localKey] = entryId
    }

    override suspend fun unbind(entryId: String) {
        keyById.remove(entryId)?.let(idByKey::remove)
    }

    override suspend fun watermark(key: String) = watermarks[key] ?: 0L

    override suspend fun setWatermark(key: String, seq: Long) {
        watermarks[key] = seq
    }

    override suspend fun <T> transaction(block: suspend () -> T): T = block()
}
