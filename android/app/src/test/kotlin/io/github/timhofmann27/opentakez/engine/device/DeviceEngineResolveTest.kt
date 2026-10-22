package io.github.timhofmann27.opentakez.engine.device

import io.github.timhofmann27.opentakez.core.model.DownloadOption
import io.github.timhofmann27.opentakez.core.model.EngineError
import io.github.timhofmann27.opentakez.core.model.MediaKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceEngineResolveTest {
    private val runner = FakeYtDlpRunner()
    private val engine = DeviceEngine(runner, FakeUpdateStore(millis = 1_000L), clock = { 2_000L })

    private val videoOptions = listOf(
        DownloadOption.VIDEO_BEST,
        DownloadOption.VIDEO_1080,
        DownloadOption.VIDEO_720,
        DownloadOption.AUDIO_MP3,
        DownloadOption.AUDIO_M4A,
    )
    private val audioOptions = listOf(DownloadOption.AUDIO_MP3, DownloadOption.AUDIO_M4A)
    private val channelsMessage =
        "Channels and profiles aren't supported yet. Share a single post, video, or playlist."

    private fun respondWith(json: String) {
        runner.handler = { _, _, _ -> stdout(json) }
    }

    @Test
    fun `resolve runs the flat capped json command`() = runTest {
        respondWith(fixture("youtube_video.json"))

        engine.resolve("https://youtu.be/dQw4w9WgXcQ")

        assertEquals(1, runner.calls.size)
        assertEquals(YtDlpArgs.resolve("https://youtu.be/dQw4w9WgXcQ"), runner.calls[0].args)
        assertFalse(runner.calls[0].redirectErrorStream)
    }

    @Test
    fun `youtube video offers video and audio options`() = runTest {
        respondWith(fixture("youtube_video.json"))

        val info = engine.resolve("https://youtu.be/dQw4w9WgXcQ")

        assertEquals("youtube", info.platform)
        assertEquals(MediaKind.VIDEO, info.kind)
        assertEquals(videoOptions, info.options)
        assertEquals("Never Gonna Give You Up", info.title)
        assertEquals("Rick Astley", info.uploader)
        assertEquals(213.0, info.duration!!, 0.0)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", info.url)
        assertEquals("https://i.ytimg.com/vi_webp/dQw4w9WgXcQ/maxresdefault.webp", info.thumbnail)
        assertNull(info.itemCount)
        assertNull(info.totalItems)
    }

    @Test
    fun `single items are requested without their playlist`() = runTest {
        respondWith(fixture("youtube_video.json"))

        engine.resolve("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PL1")

        assertTrue("--no-playlist" in runner.calls[0].args)
    }

    @Test
    fun `youtube playlist is capped at 50 and keeps the total`() = runTest {
        respondWith(fixture("youtube_playlist_flat.json"))

        val info = engine.resolve("https://www.youtube.com/playlist?list=PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG")

        assertEquals(MediaKind.VIDEO, info.kind)
        assertEquals(videoOptions, info.options)
        assertEquals(50, info.itemCount)
        assertEquals(120, info.totalItems)
        assertEquals("Some mix", info.title)
        assertEquals("Some Channel", info.uploader)
        assertEquals("https://i.ytimg.com/vi/aaa/maxresdefault.jpg", info.thumbnail)
    }

    @Test
    fun `playlist count wins over the entries seen and small lists are not padded`() = runTest {
        respondWith(fixture("youtube_playlist_flat.json").replace("\"playlist_count\":120,", "\"playlist_count\":7,"))
        val info = engine.resolve("https://www.youtube.com/playlist?list=PL1")
        assertEquals(7, info.itemCount)
        assertEquals(7, info.totalItems)

        respondWith(fixture("youtube_playlist_flat.json").replace("\"playlist_count\":120,", ""))
        val counted = engine.resolve("https://www.youtube.com/playlist?list=PL1")
        assertEquals(3, counted.itemCount)
        assertEquals(3, counted.totalItems)
    }

    @Test
    fun `playlist thumbnail falls back to the first entry that has one`() = runTest {
        val json = fixture("youtube_playlist_flat.json")
            .replace(Regex("\"thumbnails\":\\[\\{.*?}\\],\"entries\""), "\"entries\"")
            .replace(
                "\"title\":\"Two\"",
                "\"title\":\"Two\",\"thumbnails\":[{\"url\":\"https://i.ytimg.com/vi/b/a.jpg\"},{\"url\":\"https://i.ytimg.com/vi/b/z.jpg\"}]",
            )
        respondWith(json)

        val info = engine.resolve("https://www.youtube.com/playlist?list=PL1")

        assertEquals("https://i.ytimg.com/vi/b/z.jpg", info.thumbnail)
    }

    @Test
    fun `youtube channel tabs are rejected even though their entries are plain videos`() = runTest {
        respondWith(fixture("youtube_channel.json"))

        val error = engineError { engine.resolve("https://www.youtube.com/@somechannel/videos") }

        assertTrue(error is EngineError.Platform)
        assertEquals(channelsMessage, error.message)
    }

    @Test
    fun `only the playlist path counts as a youtube playlist`() = runTest {
        respondWith(
            fixture("youtube_playlist_flat.json")
                .replace("youtube.com/playlist?list=", "youtube.com/channel/UCx/playlists?list="),
        )

        val error = engineError { engine.resolve("https://www.youtube.com/channel/UCx/playlists") }

        assertEquals(channelsMessage, error.message)
    }

    @Test
    fun `playlist entries that are themselves playlists mean a channel`() = runTest {
        respondWith(
            fixture("youtube_playlist_flat.json").replace("\"_type\":\"url\",\"ie_key\":\"Youtube\"", "\"_type\":\"playlist\",\"ie_key\":\"YoutubeTab\""),
        )

        val error = engineError { engine.resolve("https://www.youtube.com/playlist?list=PL1") }

        assertEquals(channelsMessage, error.message)
    }

    @Test
    fun `entries from another extractor or without a key are not leaves`() = runTest {
        respondWith(fixture("youtube_playlist_flat.json").replace("\"ie_key\":\"Youtube\"", "\"ie_key\":\"YoutubeTab\""))
        assertEquals(
            channelsMessage,
            engineError { engine.resolve("https://www.youtube.com/playlist?list=PL1") }.message,
        )

        respondWith(fixture("youtube_playlist_flat.json").replace("\"ie_key\":\"Youtube\",", ""))
        assertEquals(
            channelsMessage,
            engineError { engine.resolve("https://www.youtube.com/playlist?list=PL1") }.message,
        )
    }

    @Test
    fun `entries may carry extractor_key instead of ie_key`() = runTest {
        respondWith(fixture("youtube_playlist_flat.json").replace("\"ie_key\":\"Youtube\"", "\"extractor_key\":\"Youtube\""))

        val info = engine.resolve("https://www.youtube.com/playlist?list=PL1")

        assertEquals(50, info.itemCount)
    }

    @Test
    fun `an empty playlist is an error`() = runTest {
        respondWith(
            """{"_type":"playlist","extractor_key":"YoutubeTab","title":"Empty","webpage_url":"https://www.youtube.com/playlist?list=PL1","entries":[]}""",
        )

        val error = engineError { engine.resolve("https://www.youtube.com/playlist?list=PL1") }

        assertEquals("This playlist is empty.", error.message)
    }

    @Test
    fun `null entries are skipped`() = runTest {
        respondWith(
            """{"_type":"playlist","extractor_key":"YoutubeTab","title":"P","webpage_url":"https://www.youtube.com/playlist?list=PL1","entries":[null,{"ie_key":"Youtube","id":"a"}]}""",
        )

        assertEquals(1, engine.resolve("https://www.youtube.com/playlist?list=PL1").itemCount)
    }

    @Test
    fun `flat entries with formats and no video make an audio playlist`() = runTest {
        respondWith(
            """{"_type":"playlist","extractor_key":"YoutubeTab","title":"P","webpage_url":"https://www.youtube.com/playlist?list=PL1","entries":[{"ie_key":"Youtube","id":"a","formats":[{"vcodec":"none"}]}]}""",
        )

        val info = engine.resolve("https://www.youtube.com/playlist?list=PL1")

        assertEquals(MediaKind.AUDIO, info.kind)
        assertEquals(audioOptions, info.options)
    }

    @Test
    fun `soundcloud track is audio only`() = runTest {
        respondWith(fixture("soundcloud_track.json"))

        val info = engine.resolve("https://soundcloud.com/an-artist/a-track")

        assertEquals("soundcloud", info.platform)
        assertEquals(MediaKind.AUDIO, info.kind)
        assertEquals(audioOptions, info.options)
        assertEquals(186.5, info.duration!!, 0.0)
        assertEquals("https://i1.sndcdn.com/artworks-000-t500x500.jpg", info.thumbnail)
    }

    @Test
    fun `soundcloud set is an audio playlist`() = runTest {
        respondWith(fixture("soundcloud_set.json"))

        val info = engine.resolve("https://soundcloud.com/an-artist/sets/an-album")

        assertEquals(MediaKind.AUDIO, info.kind)
        assertEquals(audioOptions, info.options)
        assertEquals(2, info.itemCount)
        assertEquals(2, info.totalItems)
    }

    @Test
    fun `soundcloud profiles are rejected`() = runTest {
        respondWith(fixture("soundcloud_profile.json"))

        val error = engineError { engine.resolve("https://soundcloud.com/an-artist") }

        assertEquals(channelsMessage, error.message)
    }

    @Test
    fun `tiktok video resolves with a trusted cdn thumbnail`() = runTest {
        respondWith(fixture("tiktok_video.json"))

        val info = engine.resolve("https://vm.tiktok.com/ZM123/")

        assertEquals("tiktok", info.platform)
        assertEquals(MediaKind.VIDEO, info.kind)
        assertEquals(videoOptions, info.options)
        assertEquals("someuser", info.uploader)
        assertTrue(info.thumbnail!!.contains("tiktokcdn.com"))
    }

    @Test
    fun `instagram reel with top level codec fields is a video`() = runTest {
        respondWith(fixture("instagram_reel.json"))

        val info = engine.resolve("https://www.instagram.com/reel/C1abcDEfgH/")

        assertEquals("instagram", info.platform)
        assertEquals(MediaKind.VIDEO, info.kind)
        assertEquals(12.3, info.duration!!, 0.0)
        assertTrue(info.thumbnail!!.contains("cdninstagram.com"))
    }

    @Test
    fun `an item without codec info but with a height is a video`() = runTest {
        respondWith("""{"id":"1","title":"t","extractor_key":"Facebook","height":720}""")
        assertEquals(MediaKind.VIDEO, engine.resolve("https://fb.watch/abc/").kind)

        respondWith("""{"id":"1","title":"t","extractor_key":"Facebook"}""")
        assertEquals(MediaKind.AUDIO, engine.resolve("https://fb.watch/abc/").kind)
    }

    @Test
    fun `thumbnails from untrusted hosts are dropped`() = runTest {
        for (bad in listOf(
            "https://evil.example/x.jpg",
            "https://i.ytimg.com.evil.example/x.jpg",
            "http://i.ytimg.com/x.jpg",
            "javascript:alert(1)",
        )) {
            respondWith(
                fixture("youtube_video.json")
                    .replace("https://i.ytimg.com/vi_webp/dQw4w9WgXcQ/maxresdefault.webp", bad),
            )
            assertNull(bad, engine.resolve("https://youtu.be/dQw4w9WgXcQ").thumbnail)
        }
    }

    @Test
    fun `playlist thumbnails are filtered too`() = runTest {
        respondWith(
            fixture("youtube_playlist_flat.json").replace("https://i.ytimg.com/vi/aaa/", "https://evil.example/"),
        )

        assertNull(engine.resolve("https://www.youtube.com/playlist?list=PL1").thumbnail)
    }

    @Test
    fun `missing title falls back to the id then the url, uploader to the channel`() = runTest {
        respondWith("""{"id":"abc","extractor_key":"Youtube","channel":"Chan","formats":[{"vcodec":"avc1"}]}""")
        val byId = engine.resolve("https://youtu.be/abc")
        assertEquals("abc", byId.title)
        assertEquals("Chan", byId.uploader)
        assertEquals("https://youtu.be/abc", byId.url)

        respondWith("""{"title":"","extractor_key":"Youtube","formats":[{"vcodec":"avc1"}]}""")
        assertEquals("https://youtu.be/abc", engine.resolve("https://youtu.be/abc").title)
    }

    @Test
    fun `unknown json fields and unicode titles are fine`() = runTest {
        respondWith(
            """{"id":"1","title":"Café 🎵 \"quoted\"","extractor_key":"Soundcloud","something_new":{"a":[1,2]},"formats":[{"vcodec":"none","new":1}]}""",
        )

        assertEquals("Café 🎵 \"quoted\"", engine.resolve("https://soundcloud.com/a/b").title)
    }

    @Test
    fun `a failed run is mapped from stderr`() = runTest {
        runner.handler = { _, _, _ ->
            failure("ERROR: [Instagram] abc: You need to log in to access this content")
        }

        val error = engineError { engine.resolve("https://www.instagram.com/reel/abc/") }

        assertTrue(error is EngineError.LoginRequired)
        assertEquals("instagram", (error as EngineError.LoginRequired).platform)
    }

    @Test
    fun `the youtube bot check maps to youtube blocked`() = runTest {
        runner.handler = { _, _, _ -> failure("ERROR: [youtube] abc: Sign in to confirm you're not a bot") }

        assertTrue(engineError { engine.resolve("https://youtu.be/abc") } is EngineError.YoutubeBlocked)
    }

    @Test
    fun `output that is not json is an error`() = runTest {
        respondWith("not json")
        assertTrue(engineError { engine.resolve("https://youtu.be/abc") } is EngineError.Other)

        respondWith("")
        assertTrue(engineError { engine.resolve("https://youtu.be/abc") } is EngineError.Other)
    }

    @Test
    fun `a runner that throws becomes an engine error`() = runTest {
        runner.handler = { _, _, _ -> throw java.io.IOException("No space left on device") }

        assertTrue(engineError { engine.resolve("https://youtu.be/abc") } is EngineError.StorageFull)

        runner.handler = { _, _, _ -> throw IllegalStateException("boom") }
        val other = engineError { engine.resolve("https://youtu.be/abc") }
        assertTrue(other is EngineError.Other)
        assertEquals(0, (other as EngineError.Other).status)
    }

    @Test
    fun `unsupported hosts never reach yt-dlp`() = runTest {
        assertTrue(engineError { engine.resolve("https://example.com/video") } is EngineError.Unsupported)
        assertTrue(engineError { engine.resolve("https://youtube.com:8443/watch?v=a") } is EngineError.Unsupported)
        assertTrue(runner.calls.isEmpty())
    }

    @Test
    fun `photo links need a server and never reach yt-dlp`() = runTest {
        assertTrue(
            engineError { engine.resolve("https://www.tiktok.com/@u/photo/123") } is EngineError.NeedsServer,
        )
        assertTrue(
            engineError { engine.resolve("https://www.instagram.com/p/abc/") } is EngineError.NeedsServer,
        )
        assertTrue(runner.calls.isEmpty())
    }

    @Test
    fun `resolve fails with a clear error when the engine could not start`() = runTest {
        runner.initError = IllegalStateException("python missing")

        val error = engineError { engine.resolve("https://youtu.be/abc") }

        assertTrue(error is EngineError.Other)
        assertTrue(error.message!!.contains("python missing"))
        assertTrue(runner.calls.isEmpty())
    }
}
