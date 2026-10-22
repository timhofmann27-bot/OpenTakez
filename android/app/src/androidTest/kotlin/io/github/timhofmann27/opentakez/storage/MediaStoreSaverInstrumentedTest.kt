package io.github.timhofmann27.opentakez.storage

import android.content.ContentResolver
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Real MediaStore on API 29+; writes into Music/OpenTakez and Download/OpenTakez. */
class MediaStoreSaverInstrumentedTest {
    private lateinit var resolver: ContentResolver
    private lateinit var saver: FileSaver
    private lateinit var tempDir: File
    private val created = mutableListOf<Uri>()

    @Before
    fun setUp() {
        assumeTrue(android.os.Build.VERSION.SDK_INT >= 29)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        resolver = context.contentResolver
        saver = AndroidFileSaver(context)
        tempDir = File(context.cacheDir, "saver-test").apply { mkdirs() }
    }

    @After
    fun cleanUp() {
        created.forEach { runCatching { resolver.delete(it, null, null) } }
        if (::tempDir.isInitialized) tempDir.deleteRecursively()
    }

    private fun source(content: String) = File(tempDir, "src-${System.nanoTime()}").apply { writeText(content) }

    private fun relativePathOf(uri: Uri): String? =
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    private fun isPending(uri: Uri): Int? =
        resolver.query(
            MediaStore.setIncludePending(uri),
            arrayOf(MediaStore.MediaColumns.IS_PENDING),
            null,
            null,
            null,
        )?.use { if (it.moveToFirst()) it.getInt(0) else null }

    @Test
    fun audioLandsInMusicOpenTakezAndIsNotPending() = runBlocking {
        val name = "opentakez-test-${System.nanoTime()}.mp3"
        val saved = saver.save(source("audio-bytes"), name)
        val uri = Uri.parse(saved.uri).also { created += it }

        assertEquals(name, saved.displayName)
        assertEquals("Music/OpenTakez/", relativePathOf(uri))
        assertEquals(0, isPending(uri))
        assertEquals("audio-bytes", resolver.openInputStream(uri)!!.use { String(it.readBytes()) })
    }

    @Test
    fun unknownTypesGoToDownloadOpenTakez() = runBlocking {
        val saved = saver.save(source("x"), "opentakez-test-${System.nanoTime()}.txt")
        val uri = Uri.parse(saved.uri).also { created += it }

        assertEquals("Download/OpenTakez/", relativePathOf(uri))
    }

    @Test
    fun sameNameTwiceKeepsBothFiles() = runBlocking {
        val name = "opentakez-collision-${System.nanoTime()}.mp3"
        val first = saver.save(source("one"), name).also { created += Uri.parse(it.uri) }
        val second = saver.save(source("two"), name).also { created += Uri.parse(it.uri) }

        assertEquals(name, first.displayName)
        assertNotEquals(first.uri, second.uri)
        assertTrue(second.displayName.endsWith("(1).mp3"))
        assertEquals("one", resolver.openInputStream(Uri.parse(first.uri))!!.use { String(it.readBytes()) })
    }

    @Test
    fun aFailedCopyLeavesNoRowBehind() = runBlocking {
        val name = "opentakez-fail-${System.nanoTime()}.mp3"
        val missing = File(tempDir, "missing")

        val error = runCatching { saver.save(missing, name) }.exceptionOrNull()

        assertTrue(error != null)
        val rows = resolver.query(
            MediaStore.setIncludePending(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)),
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf(name),
            null,
        )?.use { it.count }
        assertEquals(0, rows)
    }
}
