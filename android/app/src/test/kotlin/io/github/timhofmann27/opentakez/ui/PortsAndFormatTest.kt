package io.github.timhofmann27.opentakez.ui

import io.github.timhofmann27.opentakez.ui.ports.ShareInbox
import io.github.timhofmann27.opentakez.ui.ports.SharedLink
import io.github.timhofmann27.opentakez.ui.settings.ServerUrl
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedLinkTest {
    @Test
    fun `takes the first link and strips trailing punctuation`() {
        assertEquals("https://youtu.be/x", SharedLink.from("look https://youtu.be/x!", null))
        assertEquals("https://a.example/p", SharedLink.from("(see https://a.example/p).", null))
        assertEquals("https://a.example/p?x=1", SharedLink.from("https://a.example/p?x=1, then http://b.example", null))
    }

    @Test
    fun `stops at whitespace, angle brackets and quotes`() {
        assertEquals("https://a.example/p", SharedLink.from("<https://a.example/p>", null))
        assertEquals("https://a.example/p", SharedLink.from("\"https://a.example/p\"", null))
        assertEquals("http://a.example/p", SharedLink.from("x http://a.example/p\nsecond line", null))
    }

    @Test
    fun `falls back to the subject`() {
        assertEquals("https://youtu.be/s", SharedLink.from("no link here", "https://youtu.be/s"))
        assertEquals("https://youtu.be/t", SharedLink.from("https://youtu.be/t", "https://youtu.be/s"))
    }

    @Test
    fun `no link gives null for empty, null and non-http text`() {
        assertNull(SharedLink.from(null, null))
        assertNull(SharedLink.from("", ""))
        assertNull(SharedLink.from("just words, ftp://x.example and javascript:alert(1)", null))
        assertNull(SharedLink.from("https://", null))
    }

    @Test
    fun `inbox keeps an event until it is consumed and ignores a stale consume`() {
        val inbox = ShareInbox()
        inbox.offer("https://youtu.be/1")
        val first = inbox.events.value!!
        inbox.offer("https://youtu.be/2")
        val second = inbox.events.value!!
        assertNotEquals(first.id, second.id)
        inbox.consume(first)
        assertEquals(second, inbox.events.value)
        inbox.consume(second)
        assertNull(inbox.events.value)
    }

    @Test
    fun `the same link twice is two distinct events`() {
        val inbox = ShareInbox()
        inbox.offer("https://youtu.be/1")
        val first = inbox.events.value
        inbox.offer("https://youtu.be/1")
        assertNotEquals(first, inbox.events.value)
    }
}

class FormatTest {
    private val original = java.util.Locale.getDefault()

    @Before
    fun usEnglish() = java.util.Locale.setDefault(java.util.Locale.US)

    @After
    fun restore() = java.util.Locale.setDefault(original)

    @Test
    fun duration() {
        assertEquals("", Format.duration(null as Double?))
        assertEquals("0:05", Format.duration(5.0))
        assertEquals("3:33", Format.duration(212.6))
        assertEquals("1:02:05", Format.duration(3725.0))
        assertEquals("", Format.duration(Double.NaN))
        assertEquals("1:15", Format.duration(75))
    }

    @Test
    fun speed() {
        assertEquals("", Format.speed(null))
        assertEquals("", Format.speed(0.0))
        assertEquals("512 B/s", Format.speed(512.0))
        assertEquals("1.5 KB/s", Format.speed(1536.0))
        assertEquals("2.5 MB/s", Format.speed(2_621_440.0))
        assertEquals("25 MB/s", Format.speed(26_214_400.0))
    }

    @Test
    fun percent() {
        assertEquals("", Format.percent(null))
        assertEquals("42 %", Format.percent(0.426))
        assertEquals("100 %", Format.percent(1.7))
        assertEquals("0 %", Format.percent(-1.0))
    }
}

class ServerUrlTest {
    @Test
    fun `empty input means no server`() {
        assertEquals("", ServerUrl.normalize(""))
        assertEquals("", ServerUrl.normalize("   "))
    }

    @Test
    fun `keeps origin and path, drops trailing slash and api suffix`() {
        assertEquals("https://dl.example.com", ServerUrl.normalize(" https://dl.example.com/ "))
        assertEquals("https://dl.example.com:8443/app", ServerUrl.normalize("https://DL.example.com:8443/app//"))
        assertEquals("https://dl.example.com", ServerUrl.normalize("https://dl.example.com/api"))
        assertEquals("https://dl.example.com/app", ServerUrl.normalize("https://dl.example.com/app/api/"))
    }

    @Test
    fun `rejects cleartext, other schemes, credentials and garbage`() {
        assertNull(ServerUrl.normalize("http://dl.example.com"))
        assertNull(ServerUrl.normalize("ftp://dl.example.com"))
        assertNull(ServerUrl.normalize("dl.example.com"))
        assertNull(ServerUrl.normalize("https://user:pw@dl.example.com"))
        assertNull(ServerUrl.normalize("https://dl.example.com/?token=1"))
        assertNull(ServerUrl.normalize("https://dl.example.com/#fragment"))
        assertNull(ServerUrl.normalize("https://dl.example.com\\other"))
        assertNull(ServerUrl.normalize("https://"))
        assertNull(ServerUrl.normalize("https://exa mple.com"))
    }

    @Test
    fun `guesses the platform for login errors`() {
        assertEquals("instagram", guessPlatform("https://www.instagram.com/p/x/"))
        assertEquals("tiktok", guessPlatform("https://vm.tiktok.com/abc"))
        assertEquals("facebook", guessPlatform("https://fb.watch/abc"))
        assertEquals("", guessPlatform("https://youtu.be/abc"))
        assertEquals("", guessPlatform("not a url"))
    }
}
