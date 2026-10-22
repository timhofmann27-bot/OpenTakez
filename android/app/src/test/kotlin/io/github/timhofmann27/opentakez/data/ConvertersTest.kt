package io.github.timhofmann27.opentakez.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ConvertersTest {
    private val converters = Converters()

    @Test
    fun `file list round trips`() {
        val files = listOf(
            "content://media/external/video/media/1",
            "content://media/external/audio/media/2",
        )
        assertEquals(files, converters.toFiles(converters.fromFiles(files)))
    }

    @Test
    fun `empty list is a json array`() {
        assertEquals("[]", converters.fromFiles(emptyList()))
        assertEquals(emptyList<String>(), converters.toFiles("[]"))
    }

    @Test
    fun `quotes unicode and separators survive`() {
        val files = listOf("file:///a \"b\" \\ c, d.mp4", "content://x/äöü😀", "[,]")
        assertEquals(files, converters.toFiles(converters.fromFiles(files)))
    }

    @Test
    fun `corrupt json reads as empty instead of crashing the list`() {
        assertEquals(emptyList<String>(), converters.toFiles("not json"))
        assertEquals(emptyList<String>(), converters.toFiles(""))
    }
}
