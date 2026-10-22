package io.github.timhofmann27.opentakez.storage

import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LegacySaverTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scanned = mutableListOf<Pair<String, String>>()

    private fun saver(permission: Boolean = true, scanUri: String? = "content://media/external/file/1") =
        LegacySaver(
            hasPermission = { permission },
            publicDir = { File(tmp.root, "public/$it") },
            scan = { file, mime ->
                scanned += file.name to mime
                scanUri
            },
        )

    private fun source(content: String = "data"): File =
        tmp.newFile().apply { writeText(content) }

    @Test
    fun `saves into the folder for the type and scans the file`() = runTest {
        val saved = saver().save(source("video-bytes"), "01 Clip [x].mp4")

        val target = File(tmp.root, "public/Movies/OpenTakez/01 Clip [x].mp4")
        assertEquals("video-bytes", target.readText())
        assertEquals("01 Clip [x].mp4", saved.displayName)
        assertEquals("content://media/external/file/1", saved.uri)
        assertEquals(listOf("01 Clip [x].mp4" to "video/mp4"), scanned)
    }

    @Test
    fun `audio images and others go to their own folders`() = runTest {
        saver().save(source(), "a.mp3")
        saver().save(source(), "b.jpg")
        saver().save(source(), "c.txt")

        assertTrue(File(tmp.root, "public/Music/OpenTakez/a.mp3").exists())
        assertTrue(File(tmp.root, "public/Pictures/OpenTakez/b.jpg").exists())
        assertTrue(File(tmp.root, "public/Download/OpenTakez/c.txt").exists())
    }

    @Test
    fun `never overwrites an existing file`() = runTest {
        val first = saver().save(source("one"), "song.mp3")
        val second = saver().save(source("two"), "song.mp3")
        val third = saver().save(source("three"), "song.mp3")

        assertEquals("song.mp3", first.displayName)
        assertEquals("song (1).mp3", second.displayName)
        assertEquals("song (2).mp3", third.displayName)
        val dir = File(tmp.root, "public/Music/OpenTakez")
        assertEquals("one", File(dir, "song.mp3").readText())
        assertEquals("two", File(dir, "song (1).mp3").readText())
    }

    @Test
    fun `scanner failure removes the unshareable file`() = runTest {
        val error = runCatching { saver(scanUri = null).save(source(), "a.mp3") }.exceptionOrNull()

        assertTrue(error is java.io.IOException)
        assertFalse(File(tmp.root, "public/Music/OpenTakez/a.mp3").exists())
    }

    @Test
    fun `fails clearly without the storage permission and writes nothing`() = runTest {
        val error = runCatching { saver(permission = false).save(source(), "a.mp3") }.exceptionOrNull()

        assertTrue(error is StoragePermissionException)
        assertFalse(File(tmp.root, "public").exists())
    }

    @Test
    fun `a failed copy leaves no partial file`() = runTest {
        val missing = File(tmp.root, "does-not-exist.mp4")

        val error = runCatching { saver().save(missing, "a.mp4") }.exceptionOrNull()

        assertTrue(error is FileNotFoundException)
        assertFalse(File(tmp.root, "public/Movies/OpenTakez/a.mp4").exists())
        assertTrue(scanned.isEmpty())
    }

    @Test
    fun `a name with slashes cannot escape the folder`() = runTest {
        val saved = saver().save(source(), "../../evil.mp3")

        assertEquals("_.._evil.mp3", saved.displayName)
        assertTrue(File(tmp.root, "public/Music/OpenTakez/${saved.displayName}").exists())
        assertFalse(File(tmp.root, "evil.mp3").exists())
    }
}
