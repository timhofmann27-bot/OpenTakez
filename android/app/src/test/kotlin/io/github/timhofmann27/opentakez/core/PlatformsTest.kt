package io.github.timhofmann27.opentakez.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformsTest {
    private val domains = listOf("youtube.com", "youtu.be")

    @Test
    fun `platform hosts and subdomains are allowed`() {
        listOf(
            "https://www.youtube.com/watch?v=abc",
            "https://youtube.com/shorts/abc",
            "https://m.youtube.com/watch?v=abc",
            "https://music.youtube.com/watch?v=abc",
            "http://youtu.be/abc",
            "https://WWW.YouTube.com./watch?v=abc",
        ).forEach { assertTrue(it, Platforms.hostMatches(it, domains)) }
    }

    @Test
    fun `other hosts, schemes and ports are rejected`() {
        listOf(
            "https://evilyoutube.com/watch",
            "https://youtube.com.evil.com/watch",
            "https://127.0.0.1/",
            "http://localhost:8000/api/health",
            "https://youtube.com:8443/watch",
            "file:///etc/passwd",
            "ftp://youtube.com/x",
            "https://[::1/",
            "youtube.com/watch?v=abc",
            "https://youtube.com@evil.com/",
            "https://evil.com\\@youtube.com/",
            "",
        ).forEach { assertFalse(it, Platforms.hostMatches(it, domains)) }
    }

    @Test
    fun `registry maps every supported host to its platform`() {
        val expected = mapOf(
            "https://www.youtube.com/watch?v=a" to "youtube",
            "https://youtu.be/a" to "youtube",
            "https://www.youtube-nocookie.com/embed/a" to "youtube",
            "https://www.tiktok.com/@u/video/1" to "tiktok",
            "https://vm.tiktok.com/ZM123/" to "tiktok",
            "https://vt.tiktok.com/ZS123/" to "tiktok",
            "https://www.instagram.com/reel/abc/" to "instagram",
            "https://www.facebook.com/watch?v=1" to "facebook",
            "https://fb.watch/abc/" to "facebook",
            "https://fb.com/x" to "facebook",
            "https://soundcloud.com/artist/track" to "soundcloud",
            "https://on.soundcloud.com/abc" to "soundcloud",
        )
        expected.forEach { (url, platform) -> assertEquals(url, platform, Platforms.platformOf(url)) }
    }

    @Test
    fun `unknown hosts have no platform`() {
        assertNull(Platforms.platformOf("https://example.com/watch"))
        assertNull(Platforms.platformOf("https://youtube.com:444/watch"))
        assertNull(Platforms.platformOf("javascript:alert(1)"))
    }

    @Test
    fun `there are exactly the five platforms`() {
        assertEquals(
            setOf("youtube", "tiktok", "instagram", "facebook", "soundcloud"),
            Platforms.names.toSet(),
        )
    }

    @Test
    fun `thumbnails only from the platform cdn over https`() {
        val ok = "https://i.ytimg.com/vi/a/hq.jpg"
        assertEquals(ok, Platforms.trustedThumbnail(ok, "youtube"))
        assertNull(Platforms.trustedThumbnail("https://evil.example/x.jpg", "youtube"))
        assertNull(Platforms.trustedThumbnail("https://i.ytimg.com.evil.example/x.jpg", "youtube"))
        assertNull(Platforms.trustedThumbnail("http://i.ytimg.com/x.jpg", "youtube"))
        assertNull(Platforms.trustedThumbnail("javascript:alert(1)", "youtube"))
        assertNull(Platforms.trustedThumbnail(null, "youtube"))
        assertNull(Platforms.trustedThumbnail("", "youtube"))
    }

    @Test
    fun `one platform's cdn is not trusted for another`() {
        assertNull(Platforms.trustedThumbnail("https://i.ytimg.com/a.jpg", "tiktok"))
    }

    @Test
    fun `cdn lists match the server`() {
        assertNotNull(Platforms.trustedThumbnail("https://p16.tiktokcdn-us.com/a.jpg", "tiktok"))
        assertNotNull(Platforms.trustedThumbnail("https://x.byteimg.com/a.jpg", "tiktok"))
        assertNotNull(Platforms.trustedThumbnail("https://scontent.cdninstagram.com/a.jpg", "instagram"))
        assertNotNull(Platforms.trustedThumbnail("https://scontent.xx.fbcdn.net/a.jpg", "facebook"))
        assertNotNull(Platforms.trustedThumbnail("https://yt3.ggpht.com/a.jpg", "youtube"))
        assertNotNull(Platforms.trustedThumbnail("https://i1.sndcdn.com/a.jpg", "soundcloud"))
    }

    @Test
    fun `without a platform any known cdn is accepted`() {
        assertNotNull(Platforms.trustedThumbnail("https://i.ytimg.com/a.jpg"))
        assertNull(Platforms.trustedThumbnail("https://evil.example/a.jpg"))
    }
}
