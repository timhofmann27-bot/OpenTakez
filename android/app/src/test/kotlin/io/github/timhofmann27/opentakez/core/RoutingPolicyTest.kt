package io.github.timhofmann27.opentakez.core

import io.github.timhofmann27.opentakez.core.model.Engine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingPolicyTest {
    private val tiktokPhoto = "https://www.tiktok.com/@user/photo/7300000000000000000"
    private val instagramPost = "https://www.instagram.com/p/Cabc123/"

    @Test
    fun `photo links go through the server when one is configured`() {
        assertEquals(Engine.SERVER, RoutingPolicy.defaultEngine(tiktokPhoto, serverConfigured = true))
        assertEquals(Engine.SERVER, RoutingPolicy.defaultEngine(instagramPost, serverConfigured = true))
    }

    @Test
    fun `everything else runs on the device`() {
        listOf(
            "https://www.youtube.com/watch?v=a",
            "https://www.tiktok.com/@user/video/1",
            "https://www.instagram.com/reel/abc/",
            "https://soundcloud.com/a/b",
        ).forEach {
            assertEquals(it, Engine.DEVICE, RoutingPolicy.defaultEngine(it, serverConfigured = true))
            assertEquals(it, Engine.DEVICE, RoutingPolicy.defaultEngine(it, serverConfigured = false))
        }
    }

    @Test
    fun `photo links without a server report device so resolve can say NeedsServer`() {
        assertEquals(Engine.DEVICE, RoutingPolicy.defaultEngine(tiktokPhoto, serverConfigured = false))
    }

    @Test
    fun `tiktok photo path needs a user and a numeric id`() {
        assertTrue(RoutingPolicy.isPhotoLink("https://tiktok.com/@u.ser_1/photo/123?lang=en"))
        assertTrue(RoutingPolicy.isPhotoLink("https://m.tiktok.com/@u/photo/1"))
        assertFalse(RoutingPolicy.isPhotoLink("https://www.tiktok.com/@u/photo/abc"))
        assertFalse(RoutingPolicy.isPhotoLink("https://www.tiktok.com/photo/123"))
        assertFalse(RoutingPolicy.isPhotoLink("https://www.tiktok.com/@u/video/123"))
    }

    @Test
    fun `instagram post paths include the user prefixed form`() {
        assertTrue(RoutingPolicy.isPhotoLink("https://www.instagram.com/p/abc"))
        assertTrue(RoutingPolicy.isPhotoLink("https://www.instagram.com/p/abc/"))
        assertTrue(RoutingPolicy.isPhotoLink("https://www.instagram.com/someone/p/abc/"))
        assertTrue(RoutingPolicy.isPhotoLink("https://www.instagram.com/p/abc/?img_index=2"))
        assertFalse(RoutingPolicy.isPhotoLink("https://www.instagram.com/reel/abc/"))
        assertFalse(RoutingPolicy.isPhotoLink("https://www.instagram.com/p/abc/extra"))
        assertFalse(RoutingPolicy.isPhotoLink("https://www.instagram.com/someone/"))
    }

    @Test
    fun `photo paths on other hosts or with a port are not photo links`() {
        assertFalse(RoutingPolicy.isPhotoLink("https://evil.example/p/abc/"))
        assertFalse(RoutingPolicy.isPhotoLink("https://www.instagram.com:8443/p/abc/"))
        assertFalse(RoutingPolicy.isPhotoLink("https://www.youtube.com/@u/photo/1"))
        assertFalse(RoutingPolicy.isPhotoLink("not a url"))
    }
}
