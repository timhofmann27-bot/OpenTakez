package io.github.timhofmann27.opentakez.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlExtractorTest {
    @Test
    fun `bare link is returned unchanged`() {
        assertEquals("https://youtu.be/abc", UrlExtractor.extract("https://youtu.be/abc"))
    }

    @Test
    fun `link inside shared text loses trailing punctuation`() {
        assertEquals(
            "https://youtu.be/abc?si=x",
            UrlExtractor.extract("Schau mal! https://youtu.be/abc?si=x."),
        )
    }

    @Test
    fun `closing parenthesis around a link is stripped`() {
        assertEquals("https://youtu.be/abc", UrlExtractor.extract("Clip (https://youtu.be/abc)"))
    }

    @Test
    fun `text without a link gives null`() {
        assertNull(UrlExtractor.extract("no link here"))
        assertNull(UrlExtractor.extract(""))
    }

    @Test
    fun `only the first link is used and quotes or angle brackets end it`() {
        assertEquals(
            "http://a.example/1",
            UrlExtractor.extract("<http://a.example/1> and https://b.example/2"),
        )
        assertEquals("https://a.example/x", UrlExtractor.extract("\"https://a.example/x\""))
    }

    @Test
    fun `several trailing punctuation characters are all stripped`() {
        assertEquals("https://a.example/x", UrlExtractor.extract("https://a.example/x?!.,;:)"))
    }

    @Test
    fun `non http schemes are ignored`() {
        assertNull(UrlExtractor.extract("ftp://a.example/x file:///etc/passwd"))
    }

    @Test
    fun `unicode whitespace ends the link like on the server`() {
        assertEquals("https://a.example/x", UrlExtractor.extract("https://a.example/x more"))
    }
}
