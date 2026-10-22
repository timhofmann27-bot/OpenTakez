package io.github.timhofmann27.opentakez.engine.device

import io.github.timhofmann27.opentakez.core.model.EngineError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceErrorMapperTest {
    private val loginMessages = listOf(
        "[Instagram] abc: Instagram API is not granting access. Use --cookies for auth",
        "[Instagram] abc: You need to log in to access this content",
        "[Instagram] abc: Instagram sent an empty media response. Check if this post is accessible",
        "[Instagram] abc: There is no video in this post",
        "ERROR: [Facebook] 1: Cannot parse data. login required",
        "ERROR: [TikTok] 1: This video is private. Only for registered users",
    )

    @Test
    fun `login hints on cookie platforms ask for a login`() {
        for (platform in listOf("instagram", "tiktok", "facebook")) {
            for (message in loginMessages) {
                val error = DeviceErrorMapper.map("ERROR: $message", platform)
                assertTrue("$platform: $message", error is EngineError.LoginRequired)
                assertEquals(platform, (error as EngineError.LoginRequired).platform)
            }
        }
    }

    @Test
    fun `login hints on youtube and soundcloud are plain platform errors`() {
        for (platform in listOf("youtube", "soundcloud")) {
            val error = DeviceErrorMapper.map("ERROR: [x] a: Please log in", platform)
            assertTrue(error is EngineError.Platform)
            assertEquals("[x] a: Please log in", error.message)
        }
    }

    @Test
    fun `ambiguous messages are not login required`() {
        listOf(
            "[Facebook] abc: This content isn't available right now",
            "[Instagram] abc: Video unavailable",
        ).forEach {
            val error = DeviceErrorMapper.map("ERROR: $it", "instagram")
            assertTrue(it, error is EngineError.Platform)
        }
    }

    @Test
    fun `rate limit wins over login hints`() {
        val error = DeviceErrorMapper.map(
            "ERROR: [Instagram] abc: You have exceeded the rate-limit for accessing posts anonymously, log in",
            "instagram",
        )

        assertTrue(error is EngineError.RateLimited)
        assertNull((error as EngineError.RateLimited).retryAfterSeconds)
    }

    @Test
    fun `youtube bot check and 403 mean blocked`() {
        assertTrue(
            DeviceErrorMapper.map("ERROR: [youtube] abc: Sign in to confirm you're not a bot", "youtube")
                is EngineError.YoutubeBlocked,
        )
        assertTrue(
            DeviceErrorMapper.map("ERROR: unable to download video data: HTTP Error 403: Forbidden", "youtube")
                is EngineError.YoutubeBlocked,
        )
    }

    @Test
    fun `a 403 or bot check elsewhere is a plain platform error`() {
        assertTrue(
            DeviceErrorMapper.map("ERROR: HTTP Error 403: Forbidden", "tiktok") is EngineError.Platform,
        )
        assertTrue(
            DeviceErrorMapper.map("ERROR: Sign in to confirm you're not a bot", "soundcloud")
                is EngineError.Platform,
        )
    }

    @Test
    fun `unsupported url`() {
        assertTrue(
            DeviceErrorMapper.map("ERROR: Unsupported URL: https://example.com/x", null)
                is EngineError.Unsupported,
        )
    }

    @Test
    fun `no space left`() {
        assertTrue(
            DeviceErrorMapper.map("ERROR: unable to write data: [Errno 28] No space left on device", "youtube")
                is EngineError.StorageFull,
        )
    }

    @Test
    fun `matching ignores case`() {
        assertTrue(DeviceErrorMapper.map("ERROR: NO SPACE LEFT ON DEVICE", null) is EngineError.StorageFull)
    }

    @Test
    fun `other errors carry the cleaned last error line`() {
        val error = DeviceErrorMapper.map(
            "ERROR: \u001b[0;31m[youtube] abc: Video unavailable\u001b[0m\n",
            "youtube",
        )

        assertTrue(error is EngineError.Platform)
        assertEquals("[youtube] abc: Video unavailable", error.message)
    }

    @Test
    fun `the last error line is used, trailing noise is skipped`() {
        val error = DeviceErrorMapper.map(
            "ERROR: [youtube] 1: Private video\nERROR: [youtube] 2: Video unavailable\nsome trailing info\n",
            "youtube",
        )

        assertEquals("[youtube] 2: Video unavailable", error.message)
    }

    @Test
    fun `without an error prefix the last line is used`() {
        assertEquals("Traceback problem", DeviceErrorMapper.map("first\nTraceback problem\n\n", null).message)
    }

    @Test
    fun `empty stderr still gives a message`() {
        val error = DeviceErrorMapper.map("  \n", "youtube")

        assertTrue(error is EngineError.Platform)
        assertTrue(!error.message.isNullOrBlank())
    }
}
