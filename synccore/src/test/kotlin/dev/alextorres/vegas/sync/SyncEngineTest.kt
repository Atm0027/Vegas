package dev.alextorres.vegas.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A playlist library held in memory, with its own row keys, like a device's database. */
private class FakeAdapter(prefix: String) : LibraryAdapter {
    val playlists = linkedMapOf<String, MutableMap<String, Any?>>()
    val rows = linkedMapOf<String, MutableList<Pair<String, SongMeta>>>()
    val settings = linkedMapOf<String, String>()
    private var nextKey = 0
    private val keyPrefix = prefix

    fun newKey() = "$keyPrefix${nextKey++}"

    fun create(id: String, name: String, vararg songs: String) {
        playlists[id] = mutableMapOf("name" to name, "pinned" to false, "order" to 0)
        rows[id] = songs.map { newKey() to song(it) }.toMutableList()
    }

    fun songs(id: String) = rows[id].orEmpty().map { it.second.id }

    override suspend fun snapshot() = LibrarySnapshot(
        playlists = playlists.map { (id, p) ->
            LocalPlaylist(id, p["name"] as String, p["pinned"] as Boolean, p["order"] as Int, rows[id].orEmpty().map { LocalEntry(it.first, it.second) })
        },
        settings = settings.toMap(),
    )

    override suspend fun apply(changes: AppliedChanges): Map<String, String> {
        val bound = mutableMapOf<String, String>()
        changes.deletedPlaylistIds.forEach { playlists.remove(it); rows.remove(it) }
        changes.playlists.forEach { d ->
            playlists[d.reg.id] = mutableMapOf("name" to d.reg.name, "pinned" to d.reg.pinned, "order" to d.reg.customOrder)
            rows[d.reg.id] = d.entries.map { e ->
                val key = e.localKey ?: newKey().also { bound[e.entryId] = it }
                key to e.song
            }.toMutableList()
        }
        settings.putAll(changes.settings)
        return bound
    }
}

private fun song(id: String) = SongMeta(id, "Song $id", "Artist", null, null, null)

private class Device(name: String) {
    var now = 1_000L
    val adapter = FakeAdapter("$name-")
    val engine = SyncEngine(InMemorySyncStore(), adapter, HlcClock(name) { now })
}

/** Phone (client) syncs with the PC (server), the way the real transport does it. */
private fun sync(phone: Device, pc: Device) = runBlocking {
    val request = SyncRequest("phone", phone.engine.prepareOutgoing("pc"), phone.engine.receivedWatermark("pc"))
    val response = pc.engine.respond("phone", request)
    phone.engine.markSent("pc", request.changes.upToSeq)
    phone.engine.receive("pc", response.changes)
}

class SyncEngineTest {
    private val phone = Device("phone")
    private val pc = Device("pc")

    private fun startSynced(vararg songs: String) {
        phone.adapter.create("rock", "Rock", *songs)
        sync(phone, pc)
        assertEquals(songs.toList(), pc.adapter.songs("rock"))
    }

    @Test
    fun alexExampleMergesBothSides() {
        startSynced("A", "B", "X")
        phone.now = 2_000
        phone.adapter.rows["rock"]!!.removeAll { it.second.id in setOf("A", "B") }
        phone.adapter.rows["rock"]!!.add(phone.adapter.newKey() to song("C"))
        pc.now = 2_000
        pc.adapter.rows["rock"]!!.add(pc.adapter.newKey() to song("D"))
        pc.adapter.rows["rock"]!!.add(pc.adapter.newKey() to song("E"))

        sync(phone, pc)
        sync(phone, pc)

        assertEquals(setOf("X", "C", "D", "E"), phone.adapter.songs("rock").toSet())
        assertEquals(4, phone.adapter.songs("rock").size)
        assertEquals(phone.adapter.songs("rock"), pc.adapter.songs("rock"))
    }

    @Test
    fun sameSongTouchedOnBothSidesLatestWins() {
        startSynced("A", "B", "X")
        // PC moves X to the top at t=10, phone removes X later at t=20: removal wins.
        pc.now = 10_000
        pc.adapter.rows["rock"]!!.let { it.add(0, it.removeAt(2)) }
        phone.now = 20_000
        phone.adapter.rows["rock"]!!.removeAll { it.second.id == "X" }
        sync(phone, pc)
        sync(phone, pc)
        assertEquals(listOf("A", "B"), phone.adapter.songs("rock"))
        assertEquals(listOf("A", "B"), pc.adapter.songs("rock"))
    }

    @Test
    fun laterMoveBeatsEarlierRemoval() {
        startSynced("A", "B", "X")
        phone.now = 10_000
        phone.adapter.rows["rock"]!!.removeAll { it.second.id == "X" }
        pc.now = 20_000
        pc.adapter.rows["rock"]!!.let { it.add(0, it.removeAt(2)) }
        sync(phone, pc)
        sync(phone, pc)
        assertEquals(listOf("X", "A", "B"), phone.adapter.songs("rock"))
        assertEquals(listOf("X", "A", "B"), pc.adapter.songs("rock"))
    }

    @Test
    fun deleteVsAddLatestWins() {
        startSynced("A")
        pc.now = 20_000
        pc.adapter.rows["rock"]!!.add(pc.adapter.newKey() to song("B"))
        phone.now = 30_000
        phone.adapter.playlists.remove("rock")
        phone.adapter.rows.remove("rock")
        sync(phone, pc)
        sync(phone, pc)
        assertFalse("rock" in pc.adapter.playlists)
        assertFalse("rock" in phone.adapter.playlists)
    }

    @Test
    fun firstSyncUnitesBothLibraries() {
        phone.adapter.create("rock", "Rock", "A", "B", "C")
        pc.adapter.create("test", "Prueba PC", "Z")
        sync(phone, pc)
        assertEquals(setOf("Rock", "Prueba PC"), phone.adapter.playlists.values.map { it["name"] }.toSet())
        assertEquals(setOf("Rock", "Prueba PC"), pc.adapter.playlists.values.map { it["name"] }.toSet())
        assertEquals(listOf("A", "B", "C"), pc.adapter.songs("rock"))
        assertEquals(listOf("Z"), phone.adapter.songs("test"))
    }

    @Test
    fun syncIsIdempotent() {
        startSynced("A", "B")
        val before = pc.adapter.songs("rock")
        sync(phone, pc)
        sync(phone, pc)
        assertEquals(before, pc.adapter.songs("rock"))
        val outgoing = runBlocking { phone.engine.prepareOutgoing("pc") }
        assertTrue(outgoing.playlists.isEmpty() && outgoing.entries.isEmpty() && outgoing.settings.isEmpty())
    }

    @Test
    fun editorRewriteKeepsOrderWithoutDuplicates() {
        startSynced("A", "B", "C")
        phone.now = 5_000
        // The phone's editor deletes and re-inserts every row: new local keys, same songs and order.
        phone.adapter.rows["rock"] = phone.adapter.rows["rock"]!!.map { phone.adapter.newKey() to it.second }.toMutableList()
        sync(phone, pc)
        assertEquals(listOf("A", "B", "C"), pc.adapter.songs("rock"))
    }

    @Test
    fun duplicatesAreSeparateEntries() {
        phone.adapter.create("rock", "Rock", "A", "A")
        sync(phone, pc)
        assertEquals(listOf("A", "A"), pc.adapter.songs("rock"))
        phone.now = 5_000
        phone.adapter.rows["rock"]!!.removeAt(0)
        sync(phone, pc)
        assertEquals(listOf("A"), pc.adapter.songs("rock"))
    }

    @Test
    fun renameAndPinTravel() {
        startSynced("A")
        pc.now = 5_000
        pc.adapter.playlists["rock"]!!["name"] = "Rock clásico"
        pc.adapter.playlists["rock"]!!["pinned"] = true
        sync(phone, pc)
        assertEquals("Rock clásico", phone.adapter.playlists["rock"]!!["name"])
        assertEquals(true, phone.adapter.playlists["rock"]!!["pinned"])
    }

    @Test
    fun settingsLatestWins() {
        phone.now = 10_000
        phone.adapter.settings["audioQuality"] = "LOW"
        pc.now = 20_000
        pc.adapter.settings["audioQuality"] = "HIGH"
        sync(phone, pc)
        sync(phone, pc)
        assertEquals("HIGH", phone.adapter.settings["audioQuality"])
        assertEquals("HIGH", pc.adapter.settings["audioQuality"])
    }

    @Test
    fun manyMovesStayOrdered() {
        startSynced("A", "B", "C", "D", "E")
        repeat(40) { i ->
            phone.now = 2_000L + i
            phone.adapter.rows["rock"]!!.let { it.add(1, it.removeAt(if (i % 2 == 0) 4 else 3)) }
        }
        sync(phone, pc)
        assertEquals(phone.adapter.songs("rock"), pc.adapter.songs("rock"))
    }

    @Test
    fun reusedRowKeysWithOtherSongsAreNotMistakenForTheOldRows() {
        startSynced("A", "B", "C")
        phone.now = 5_000
        // SQLite hands the same row ids out again after the editor deletes every row.
        val keys = phone.adapter.rows["rock"]!!.map { it.first }
        phone.adapter.rows["rock"] = mutableListOf(keys[0] to song("C"), keys[1] to song("A"), keys[2] to song("B"))
        sync(phone, pc)
        assertEquals(listOf("C", "A", "B"), pc.adapter.songs("rock"))
    }
}
