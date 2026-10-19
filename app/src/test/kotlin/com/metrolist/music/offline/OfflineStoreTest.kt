package com.metrolist.music.offline

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OfflineStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = OfflineStore(context)

    @Test
    fun existingFile_isPresent_andDeletedFile_isMissing() {
        val file = File.createTempFile("song", ".m4a", context.cacheDir)
        val uri = Uri.fromFile(file)
        assertEquals(OfflineStore.Existence.PRESENT, store.exists(uri))
        file.delete()
        assertEquals(OfflineStore.Existence.MISSING, store.exists(uri))
    }

    @Test
    fun fileFromAnotherInstall_withoutPermission_isNoAccess() {
        Robolectric.setupContentProvider(DenyingProvider::class.java, "vegas.denied")
        assertEquals(OfflineStore.Existence.NO_ACCESS, store.exists(Uri.parse("content://vegas.denied/audio/5")))
    }

    @Test
    @Config(sdk = [28])
    fun publishBeforeAndroid10_movesFileToPrivateMusicFolder() {
        val temp = File(context.cacheDir, "x.part").apply { writeBytes(ByteArray(10) { 1 }) }
        val uri = store.publish(temp, "A - B [abc123DEF_-].m4a", "audio/mp4")
        assertEquals("file", uri.scheme)
        val published = File(uri.path!!)
        assertEquals("A - B [abc123DEF_-].m4a", published.name)
        assertEquals("Vegas", published.parentFile!!.name)
        assertEquals(10L, published.length())
        assertTrue(!temp.exists())
    }

    @Test
    fun storedIdNowPointingToAnotherSong_isMissing() {
        Robolectric.setupContentProvider(NamedProvider::class.java, "vegas.named")
        val uri = Uri.parse("content://vegas.named/audio/7")
        assertEquals(OfflineStore.Existence.PRESENT, store.exists(uri, videoId = "UYUUb6Dl5D8"))
        assertEquals(OfflineStore.Existence.MISSING, store.exists(uri, videoId = "imJSUk0Z3Vg"))
    }

    class NamedProvider : ContentProvider() {
        override fun onCreate() = true
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
            ParcelFileDescriptor.open(File.createTempFile("named", ".m4a"), ParcelFileDescriptor.MODE_READ_ONLY)
        override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor =
            android.database.MatrixCursor(arrayOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME))
                .apply { addRow(arrayOf("Eladio Carrión - Hugo [UYUUb6Dl5D8].m4a")) }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
    }

    class DenyingProvider : ContentProvider() {
        override fun onCreate() = true
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? = throw SecurityException("no read permission")
        override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
    }
}
