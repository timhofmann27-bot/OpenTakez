package io.github.timhofmann27.opentakez.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class SaveLocationTest {

    @Test
    fun `maps extensions to folders`() {
        val table = mapOf(
            "01 Title [abc].mp4" to Triple(MediaCategory.VIDEO, "Movies/OpenTakez", "video/mp4"),
            "clip.webm" to Triple(MediaCategory.VIDEO, "Movies/OpenTakez", "video/webm"),
            "clip.MKV" to Triple(MediaCategory.VIDEO, "Movies/OpenTakez", "video/x-matroska"),
            "song.mp3" to Triple(MediaCategory.AUDIO, "Music/OpenTakez", "audio/mpeg"),
            "song.m4a" to Triple(MediaCategory.AUDIO, "Music/OpenTakez", "audio/mp4"),
            "song.opus" to Triple(MediaCategory.AUDIO, "Music/OpenTakez", "audio/opus"),
            "photo.jpg" to Triple(MediaCategory.IMAGE, "Pictures/OpenTakez", "image/jpeg"),
            "photo.JPEG" to Triple(MediaCategory.IMAGE, "Pictures/OpenTakez", "image/jpeg"),
            "photo.webp" to Triple(MediaCategory.IMAGE, "Pictures/OpenTakez", "image/webp"),
            "notes.txt" to Triple(MediaCategory.OTHER, "Download/OpenTakez", "application/octet-stream"),
            "archive.zip" to Triple(MediaCategory.OTHER, "Download/OpenTakez", "application/octet-stream"),
            "noextension" to Triple(MediaCategory.OTHER, "Download/OpenTakez", "application/octet-stream"),
            "" to Triple(MediaCategory.OTHER, "Download/OpenTakez", "application/octet-stream"),
            "trailingdot." to Triple(MediaCategory.OTHER, "Download/OpenTakez", "application/octet-stream"),
        )
        for ((name, expected) in table) {
            val location = SaveLocation.forFile(name)
            assertEquals(name, expected.first, location.category)
            assertEquals(name, expected.second, location.relativePath)
            assertEquals(name, expected.third, location.mimeType)
        }
    }

    @Test
    fun `only the last extension counts`() {
        assertEquals(MediaCategory.AUDIO, SaveLocation.forFile("movie.mp4.mp3").category)
        assertEquals(MediaCategory.OTHER, SaveLocation.forFile("song.mp3.part").category)
    }

    @Test
    fun `unique name adds numbered suffixes before the extension`() {
        assertEquals("a.mp4", uniqueName("a.mp4") { false })
        assertEquals("a (1).mp4", uniqueName("a.mp4") { it == "a.mp4" })
        assertEquals("a (2).mp4", uniqueName("a.mp4") { it in setOf("a.mp4", "a (1).mp4") })
        assertEquals("noext (1)", uniqueName("noext") { it == "noext" })
        assertEquals("a.b (1).mp4", uniqueName("a.b.mp4") { it == "a.b.mp4" })
        assertEquals(".hidden (1)", uniqueName(".hidden") { it == ".hidden" })
    }

    @Test
    fun `sanitize keeps names inside their folder`() {
        assertEquals("a_b.mp4", sanitizeFileName("a/b.mp4"))
        assertEquals("a_b.mp4", sanitizeFileName("a\\b.mp4"))
        assertEquals("_.._etc_passwd", sanitizeFileName("/../etc/passwd"))
        assertEquals("hidden.mp4", sanitizeFileName("..hidden.mp4"))
        assertEquals("download", sanitizeFileName("   "))
        assertEquals("download", sanitizeFileName(""))
        assertEquals("Titel äöü 😀 [id].mp4", sanitizeFileName("Titel äöü 😀 [id].mp4"))
        assertEquals("a_b.mp4", sanitizeFileName("a\nb.mp4"))
    }
}
