@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.timhofmann27.opentakez.engine.device

import app.cash.turbine.test
import io.github.timhofmann27.opentakez.core.model.DownloadOption
import io.github.timhofmann27.opentakez.core.model.EngineError
import io.github.timhofmann27.opentakez.core.model.Phase
import io.github.timhofmann27.opentakez.engine.EngineEvent
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DeviceEngineDownloadTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val runner = FakeYtDlpRunner()
    private val engine = DeviceEngine(runner, FakeUpdateStore(millis = 1_000L), clock = { 2_000L })
    private val video = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"

    /** Answers the flat pre-check with a plain video, then runs [download] for the real run. */
    private fun script(download: RunHandler) {
        runner.handler = { args, processId, onLine ->
            if ("--dump-single-json" in args) {
                stdout(fixture("youtube_video.json"))
            } else {
                download(args, processId, onLine)
            }
        }
    }

    private fun File.touch(name: String) = File(this, name).also { it.writeText("x") }

    private suspend fun run(
        url: String = video,
        option: DownloadOption = DownloadOption.VIDEO_BEST,
        dir: File = tmp.newFolder(),
    ) = engine.download(url, option, dir).toList()

    @Test
    fun `download runs the contract arguments in the work directory`() = runTest {
        val dir = tmp.newFolder()
        script { _, _, _ -> dir.touch("Song [abc].mp4"); RunResult(0, "", "") }

        run(dir = dir)

        val args = runner.calls.last().args
        assertEquals(YtDlpArgs.download(video, DownloadOption.VIDEO_BEST, dir), args)
        assertTrue(runner.calls.last().redirectErrorStream)
    }

    @Test
    fun `progress lines become events and done lists the files`() = runTest {
        val dir = tmp.newFolder()
        script { _, _, onLine ->
            onLine("[youtube] Extracting URL: $video")
            onLine("[download]  42.0% of   10.00MiB at    1.50MiB/s ETA 00:05")
            dir.touch("Song [abc].mp4")
            onLine("[Merger] Merging formats into \"x\"")
            RunResult(0, "", "")
        }

        engine.download(video, DownloadOption.VIDEO_BEST, dir).test {
            val downloading = (awaitItem() as EngineEvent.Progress).progress
            assertEquals(Phase.DOWNLOADING, downloading.phase)
            assertEquals(0.42, downloading.fraction!!, 1e-9)
            assertEquals(5, downloading.etaSeconds)
            assertEquals(Phase.PROCESSING, (awaitItem() as EngineEvent.Progress).progress.phase)
            val done = awaitItem() as EngineEvent.Done
            assertEquals(listOf("Song [abc].mp4"), done.files.map { it.displayName })
            assertEquals(File(dir, "Song [abc].mp4"), done.files.single().file)
            assertEquals(0, done.skipped)
            awaitComplete()
        }
    }

    @Test
    fun `temp files are never results`() = runTest {
        val dir = tmp.newFolder()
        script { _, _, _ ->
            listOf("a.mp4", "b.mp4.part", "c.ytdl", "d.temp", "E.PART").forEach { dir.touch(it) }
            RunResult(0, "", "")
        }

        val done = run(dir = dir).last() as EngineEvent.Done

        assertEquals(listOf("a.mp4"), done.files.map { it.displayName })
    }

    @Test
    fun `images are results for video downloads`() = runTest {
        val dir = tmp.newFolder()
        script { _, _, _ ->
            listOf("a.jpg", "b.mp4", "c.webp", "d.png", "e.part").forEach { dir.touch(it) }
            RunResult(0, "", "")
        }

        val done = run(dir = dir, option = DownloadOption.VIDEO_BEST).last() as EngineEvent.Done

        assertEquals(listOf("a.jpg", "b.mp4", "c.webp", "d.png"), done.files.map { it.displayName })
    }

    @Test
    fun `cover images left by audio downloads are not results`() = runTest {
        for (option in listOf(DownloadOption.AUDIO_MP3, DownloadOption.AUDIO_M4A)) {
            val dir = tmp.newFolder()
            script { _, _, _ ->
                listOf("a.jpg", "b.mp3", "c.webp", "d.png", "e.part").forEach { dir.touch(it) }
                RunResult(0, "", "")
            }

            val done = run(dir = dir, option = option).last() as EngineEvent.Done

            assertEquals(listOf("b.mp3"), done.files.map { it.displayName })
        }
    }

    @Test
    fun `files are sorted by name and sub directories are ignored`() = runTest {
        val dir = tmp.newFolder()
        script { _, _, _ ->
            dir.touch("02 b.mp4"); dir.touch("01 a.mp4"); File(dir, "sub").mkdir()
            RunResult(0, "", "")
        }

        val done = run(dir = dir).last() as EngineEvent.Done

        assertEquals(listOf("01 a.mp4", "02 b.mp4"), done.files.map { it.displayName })
    }

    @Test
    fun `files that were there before are not results`() = runTest {
        val dir = tmp.newFolder()
        dir.touch("old.mp4")
        script { _, _, _ -> dir.touch("new.mp4"); RunResult(0, "", "") }

        val done = run(dir = dir).last() as EngineEvent.Done

        assertEquals(listOf("new.mp4"), done.files.map { it.displayName })
    }

    @Test
    fun `failed items are counted when others succeed`() = runTest {
        val dir = tmp.newFolder()
        script { _, _, _ ->
            dir.touch("01 a.mp4")
            RunResult(1, "ERROR: [youtube] 2: Private video\nERROR: [youtube] 3: Video unavailable\n", "")
        }

        val done = run(dir = dir).last() as EngineEvent.Done

        assertEquals(listOf("01 a.mp4"), done.files.map { it.displayName })
        assertEquals(2, done.skipped)
    }

    @Test
    fun `no files and a failure is the mapped error`() = runTest {
        script { _, _, _ -> RunResult(1, "ERROR: [youtube] abc: Private video", "") }

        val error = engineError { run() }

        assertTrue(error is EngineError.Platform)
        assertEquals("[youtube] abc: Private video", error.message)
    }

    @Test
    fun `a failure removes partial files`() = runTest {
        val dir = tmp.newFolder()
        script { _, _, _ ->
            dir.touch("a.mp4.part")
            RunResult(1, "ERROR: [youtube] abc: HTTP Error 403: Forbidden", "")
        }

        assertTrue(engineError { run(dir = dir) } is EngineError.YoutubeBlocked)
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test
    fun `no files and no error is reported`() = runTest {
        script { _, _, _ -> RunResult(0, "", "") }

        val error = engineError { run() }

        assertTrue(error is EngineError.Platform)
        assertTrue(error.message!!.startsWith("No file was downloaded"))
    }

    @Test
    fun `channels are rejected before anything is downloaded`() = runTest {
        runner.handler = { _, _, _ -> stdout(fixture("youtube_channel.json")) }
        val dir = tmp.newFolder()

        val error = engineError { run(url = "https://www.youtube.com/@somechannel/videos", dir = dir) }

        assertTrue(error is EngineError.Platform)
        assertTrue(error.message!!.startsWith("Channels and profiles"))
        assertEquals(1, runner.calls.size)
        assertTrue("--dump-single-json" in runner.calls[0].args)
    }

    @Test
    fun `a valid playlist goes ahead after the check`() = runTest {
        val dir = tmp.newFolder()
        runner.handler = { args, _, _ ->
            if ("--dump-single-json" in args) {
                stdout(fixture("youtube_playlist_flat.json"))
            } else {
                dir.touch("01 One [a].mp4")
                RunResult(0, "", "")
            }
        }

        val done = run(url = "https://www.youtube.com/playlist?list=PL1", dir = dir).last() as EngineEvent.Done

        assertEquals(listOf("01 One [a].mp4"), done.files.map { it.displayName })
        assertEquals(2, runner.calls.size)
        assertFalse("--no-playlist" in runner.calls[0].args)
        assertFalse("--no-playlist" in runner.calls[1].args)
    }

    @Test
    fun `an audio-only link refuses a video option before downloading`() = runTest {
        runner.handler = { _, _, _ -> stdout(fixture("soundcloud_track.json")) }

        val error = engineError {
            run(url = "https://soundcloud.com/an-artist/a-track", option = DownloadOption.VIDEO_BEST)
        }

        assertTrue(error is EngineError.Platform)
        assertEquals(1, runner.calls.size)
    }

    @Test
    fun `options yt-dlp cannot serve are refused`() = runTest {
        val error = engineError { run(option = DownloadOption.IMAGE_ORIGINAL) }

        assertEquals("This download option isn't available for this link.", error.message)
        assertTrue(runner.calls.isEmpty())
    }

    @Test
    fun `photo and unsupported links never reach yt-dlp`() = runTest {
        assertTrue(engineError { run(url = "https://www.instagram.com/p/abc/") } is EngineError.NeedsServer)
        assertTrue(engineError { run(url = "https://example.com/x") } is EngineError.Unsupported)
        assertTrue(runner.calls.isEmpty())
    }

    @Test
    fun `every download gets a fresh process id`() = runTest {
        val dirs = List(2) { tmp.newFolder() }
        script { args, _, _ ->
            File(args[args.indexOf("-o") + 1]).parentFile!!.touch("a.mp4")
            RunResult(0, "", "")
        }

        dirs.forEach { engine.download(video, DownloadOption.VIDEO_BEST, it).toList() }

        val ids = runner.calls.filter { "--dump-single-json" !in it.args }.map { it.processId }
        assertEquals(2, ids.size)
        assertNotEquals(ids[0], ids[1])
        ids.forEach { java.util.UUID.fromString(it) }
    }

    @Test
    fun `cancelling cancels the process and removes partial files`() = runTest {
        val dir = tmp.newFolder()
        dir.touch("keep-me.txt")
        val started = CompletableDeferred<Unit>()
        script { _, _, _ ->
            dir.touch("Song [abc].f137.mp4.part")
            started.complete(Unit)
            CompletableDeferred<Unit>().await()
            RunResult(0, "", "")
        }

        val job = launch { engine.download(video, DownloadOption.VIDEO_BEST, dir).collect { } }
        runCurrent()
        assertTrue(started.isCompleted)
        job.cancelAndJoin()

        val downloadId = runner.calls.last().processId
        assertEquals(listOf(downloadId), runner.cancelled)
        assertEquals(listOf("keep-me.txt"), dir.list()!!.toList())
    }

    @Test
    fun `a download holds the gate so an update waits for it`() = runTest {
        val release = CompletableDeferred<Unit>()
        val dir = tmp.newFolder()
        script { _, _, _ -> dir.touch("a.mp4"); release.await(); RunResult(0, "", "") }

        val downloading = launch { engine.download(video, DownloadOption.VIDEO_BEST, dir).collect { } }
        runCurrent()
        val updating = launch { engine.updateYtDlp() }
        runCurrent()

        assertEquals(0, runner.updateCalls)
        assertFalse(updating.isCompleted)

        release.complete(Unit)
        advanceUntilIdle()

        assertTrue(downloading.isCompleted)
        assertEquals(1, runner.updateCalls)
        assertTrue(updating.isCompleted)
    }
}
