/**
 * Vegas sync: turns local edits into registers (by comparing the library with the last known state)
 * and merges the other device's registers, last change wins per register.
 *
 * The phone is the client: [prepareOutgoing] → send → [markSent], then [receive] the answer.
 * The PC is the server: [respond].
 * Licensed under GPL-3.0
 */

package dev.alextorres.vegas.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class SyncEngine(
    private val store: SyncStore,
    private val adapter: LibraryAdapter,
    private val clock: HlcClock,
) {
    private val mutex = Mutex()
    private var clockLoaded = false

    suspend fun prepareOutgoing(peer: String): Changes =
        locked {
            capture()
            store.changedSince(store.watermark(sentKey(peer)))
        }

    suspend fun markSent(peer: String, upToSeq: Long) = locked { store.setWatermark(sentKey(peer), upToSeq) }

    suspend fun receivedWatermark(peer: String): Long = locked { store.watermark(receivedKey(peer)) }

    suspend fun receive(peer: String, changes: Changes) =
        locked {
            merge(changes)
            store.setWatermark(receivedKey(peer), changes.upToSeq)
        }

    suspend fun respond(peer: String, request: SyncRequest): SyncResponse =
        locked {
            merge(request.changes)
            SyncResponse(store.changedSince(request.sinceSeq))
        }

    /** Records local edits now (e.g. before showing «synced»). */
    suspend fun captureLocal() = locked { capture() }

    private suspend fun <T> locked(block: suspend () -> T): T =
        mutex.withLock {
            store.transaction {
                if (!clockLoaded) {
                    clockLoaded = true
                    latestHlc()?.let(clock::observe)
                }
                block()
            }
        }

    private suspend fun latestHlc(): Hlc? =
        (store.playlists().values.map { it.hlc } + store.entries().values.map { it.hlc } + store.settings().values.map { it.hlc }).maxOrNull()

    // ---- Local edits → registers ----

    private suspend fun capture() {
        val snapshot = adapter.snapshot()
        val playlists = store.playlists()
        val entries = store.entries()
        val localIds = snapshot.playlists.mapTo(HashSet()) { it.id }

        for (local in snapshot.playlists) {
            val old = playlists[local.id]
            if (old == null || old.deleted || old.name != local.name || old.pinned != local.pinned || old.customOrder != local.customOrder) {
                store.putPlaylist(PlaylistReg(local.id, local.name, local.pinned, local.customOrder, false, clock.tick()), store.nextSeq())
            }
            captureEntries(local, entries)
        }

        for (reg in playlists.values) {
            if (reg.deleted || reg.id in localIds) continue
            store.putPlaylist(reg.copy(deleted = true, hlc = clock.tick()), store.nextSeq())
            entries.values.filter { it.playlistId == reg.id && !it.deleted }.forEach { tombstone(it) }
        }

        val settings = store.settings()
        for ((key, value) in snapshot.settings) {
            if (settings[key]?.value != value) store.putSetting(SettingReg(key, value, clock.tick()), store.nextSeq())
        }
    }

    private suspend fun captureEntries(local: LocalPlaylist, entries: Map<String, EntryReg>) {
        val alive = entries.values.filter { it.playlistId == local.id && !it.deleted }
        val aliveIds = alive.mapTo(HashSet()) { it.entryId }
        val ids = arrayOfNulls<String>(local.entries.size)
        // A binding only counts while the row still holds that song: databases hand row ids out again.
        local.entries.forEachIndexed { i, e ->
            ids[i] = store.entryIdFor(e.localKey)?.takeIf { it in aliveIds && entries[it]?.song?.id == e.song.id }
        }

        // Rows the device re-created (the phone's editor deletes and re-inserts) keep their entry
        // when an unclaimed entry of the same song is waiting, instead of churning remove + add.
        val claimed = ids.filterNotNull().toHashSet()
        val waiting = alive.filter { it.entryId !in claimed }.sortedBy { it.sortKey }.toMutableList()
        local.entries.forEachIndexed { i, e ->
            if (ids[i] != null) return@forEachIndexed
            val reuse = waiting.firstOrNull { it.song.id == e.song.id } ?: return@forEachIndexed
            waiting.remove(reuse)
            ids[i] = reuse.entryId
            store.bind(reuse.entryId, e.localKey)
        }
        waiting.forEach { tombstone(it) }

        val oldKeys = ids.map { id -> id?.let { entries[it]?.sortKey } }
        val newKeys = SortKeys.assign(oldKeys)
        local.entries.forEachIndexed { i, e ->
            val id = ids[i] ?: UUID.randomUUID().toString().also { store.bind(it, e.localKey) }
            if (ids[i] == null || newKeys[i] != oldKeys[i]) {
                store.putEntry(EntryReg(id, local.id, e.song, newKeys[i], false, clock.tick()), store.nextSeq())
            }
        }
    }

    private suspend fun tombstone(reg: EntryReg) {
        store.putEntry(reg.copy(deleted = true, hlc = clock.tick()), store.nextSeq())
        store.unbind(reg.entryId)
    }

    // ---- Remote registers → library ----

    private suspend fun merge(changes: Changes) {
        // Local edits first: applying the other side's state without recording ours would erase them.
        capture()
        val playlists = store.playlists()
        val entries = store.entries()
        val settings = store.settings()
        val affected = LinkedHashSet<String>()
        val changedSettings = linkedMapOf<String, String>()

        for (reg in changes.playlists) {
            val old = playlists[reg.id]
            if (old != null && reg.hlc <= old.hlc) continue
            store.putPlaylist(reg, store.nextSeq())
            affected += reg.id
        }
        for (reg in changes.entries) {
            val old = entries[reg.entryId]
            if (old != null && reg.hlc <= old.hlc) continue
            store.putEntry(reg, store.nextSeq())
            if (reg.deleted) store.unbind(reg.entryId)
            affected += reg.playlistId
        }
        for (reg in changes.settings) {
            val old = settings[reg.key]
            if (old != null && reg.hlc <= old.hlc) continue
            store.putSetting(reg, store.nextSeq())
            changedSettings[reg.key] = reg.value
        }
        (changes.playlists.map { it.hlc } + changes.entries.map { it.hlc } + changes.settings.map { it.hlc }).maxOrNull()?.let(clock::observe)
        if (affected.isEmpty() && changedSettings.isEmpty()) return

        val nowPlaylists = store.playlists()
        val nowEntries = store.entries().values
        val desired = mutableListOf<DesiredPlaylist>()
        val deleted = mutableListOf<String>()
        for (id in affected) {
            val reg = nowPlaylists[id] ?: continue
            if (reg.deleted) {
                deleted += id
                continue
            }
            val rows = nowEntries.filter { it.playlistId == id && !it.deleted }
                .sortedWith(compareBy<EntryReg> { it.sortKey }.thenBy { it.entryId })
                .map { DesiredEntry(it.entryId, store.localKeyFor(it.entryId), it.song) }
            desired += DesiredPlaylist(reg, rows)
        }
        adapter.apply(AppliedChanges(desired, deleted, changedSettings)).forEach { (entryId, key) -> store.bind(entryId, key) }
    }

    private fun sentKey(peer: String) = "sent:$peer"

    private fun receivedKey(peer: String) = "recv:$peer"
}

/** Fractional ordering: keep the keys that are still in order, give new or moved rows a key between neighbours. */
internal object SortKeys {
    private const val MIN_GAP = 1e-9

    fun assign(old: List<Double?>): List<Double> {
        val keep = longestIncreasing(old)
        val result = DoubleArray(old.size)
        var renumber = false
        for (i in old.indices) {
            if (i in keep) {
                result[i] = old[i]!!
                continue
            }
            val prev = if (i > 0) result[i - 1] else null
            val next = (i + 1 until old.size).firstOrNull { it in keep }?.let { old[it]!! }
            val key = when {
                prev != null && next != null -> (prev + next) / 2
                prev != null -> prev + 1.0
                next != null -> next - 1.0
                else -> 1.0
            }
            if ((prev != null && key - prev < MIN_GAP) || (next != null && next - key < MIN_GAP)) renumber = true
            result[i] = key
        }
        return if (renumber) old.indices.map { (it + 1).toDouble() } else result.toList()
    }

    /** Indices of a longest strictly increasing run of the known keys, in order. */
    private fun longestIncreasing(keys: List<Double?>): Set<Int> {
        val idx = keys.indices.filter { keys[it] != null }
        if (idx.isEmpty()) return emptySet()
        val tails = mutableListOf<Int>()
        val parent = IntArray(keys.size) { -1 }
        for (i in idx) {
            val k = keys[i]!!
            var lo = 0
            var hi = tails.size
            while (lo < hi) {
                val mid = (lo + hi) / 2
                if (keys[tails[mid]]!! < k) lo = mid + 1 else hi = mid
            }
            if (lo > 0) parent[i] = tails[lo - 1]
            if (lo == tails.size) tails += i else tails[lo] = i
        }
        val out = HashSet<Int>()
        var cur = tails.last()
        while (cur != -1) {
            out += cur
            cur = parent[cur]
        }
        return out
    }
}
