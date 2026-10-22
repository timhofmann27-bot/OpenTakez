package io.github.timhofmann27.opentakez.engine.device

import io.github.timhofmann27.opentakez.core.model.DownloadOption
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class YtDlpArgsTest {
    private val url = "https://www.youtube.com/watch?v=abc"
    private val outTemplate = "/work/job/%(playlist_index&{:02d} |)s%(title).150B [%(id)s].%(ext)s"

    private val common = listOf(
        "-o", outTemplate,
        "--windows-filenames",
        "--playlist-items", "1:50",
        "--ignore-errors",
        "--socket-timeout", "20",
        "--retries", "3",
        "--newline",
        "--no-warnings",
        "--no-playlist",
    )

    private fun download(option: DownloadOption) =
        YtDlpArgs.download(url, option, File("/work/job"))

    @Test
    fun `resolve asks for flat capped json and a single item`() {
        assertEquals(
            listOf(
                "--dump-single-json", "--flat-playlist", "--playlist-end", "50",
                "--no-playlist", "--no-warnings", "--socket-timeout", "20", url,
            ),
            YtDlpArgs.resolve(url),
        )
    }

    @Test
    fun `YouTube playlist and SoundCloud set retain playlist traversal`() {
        for (playlist in listOf(
            "https://www.youtube.com/playlist?list=PL1",
            "https://soundcloud.com/artist/sets/album",
        )) {
            assertEquals(false, "--no-playlist" in YtDlpArgs.resolve(playlist))
            assertEquals(false, "--no-playlist" in YtDlpArgs.download(playlist, DownloadOption.AUDIO_MP3, File("/work/job")))
            assertEquals("1:50", YtDlpArgs.download(playlist, DownloadOption.AUDIO_MP3, File("/work/job"))
                .let { it[it.indexOf("--playlist-items") + 1] })
        }
    }

    @Test
    fun `video best prefers mp4 and merges to mp4`() {
        assertEquals(
            listOf("-f", "bv*+ba/b", "-S", "ext:mp4:m4a", "--merge-output-format", "mp4") + common + url,
            download(DownloadOption.VIDEO_BEST),
        )
    }

    @Test
    fun `video 1080 and 720 sort by resolution first`() {
        assertEquals(
            listOf("-f", "bv*+ba/b", "-S", "res:1080,ext:mp4:m4a", "--merge-output-format", "mp4") + common + url,
            download(DownloadOption.VIDEO_1080),
        )
        assertEquals(
            listOf("-f", "bv*+ba/b", "-S", "res:720,ext:mp4:m4a", "--merge-output-format", "mp4") + common + url,
            download(DownloadOption.VIDEO_720),
        )
    }

    @Test
    fun `audio m4a prefers a native m4a stream`() {
        assertEquals(
            listOf(
                "-f", "ba[ext=m4a]/ba/b", "-x", "--audio-format", "m4a", "--audio-quality", "0",
                "--embed-metadata", "--embed-thumbnail", "--convert-thumbnails", "jpg",
            ) + common + url,
            download(DownloadOption.AUDIO_M4A),
        )
    }

    @Test
    fun `audio mp3 takes the best audio and converts`() {
        assertEquals(
            listOf(
                "-f", "ba/b", "-x", "--audio-format", "mp3", "--audio-quality", "0",
                "--embed-metadata", "--embed-thumbnail", "--convert-thumbnails", "jpg",
            ) + common + url,
            download(DownloadOption.AUDIO_MP3),
        )
    }

    @Test
    fun `percent signs in the work directory are escaped for the output template`() {
        val args = YtDlpArgs.download(url, DownloadOption.VIDEO_BEST, File("/work/100%/job"))

        assertEquals(
            "/work/100%%/job/%(playlist_index&{:02d} |)s%(title).150B [%(id)s].%(ext)s",
            args[args.indexOf("-o") + 1],
        )
    }

    @Test
    fun `images are not a yt-dlp download`() {
        assertThrows(IllegalArgumentException::class.java) { download(DownloadOption.IMAGE_ORIGINAL) }
    }
}
