@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.timhofmann27.opentakez.engine.device

import io.github.timhofmann27.opentakez.core.model.EngineError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceEngineLifecycleTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 100 * day
    private val runner = FakeYtDlpRunner()
    private val store = FakeUpdateStore()
    private val engine = DeviceEngine(runner, store, clock = { now })
    private val url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"

    private fun resolvesVideo() {
        runner.handler = { _, _, _ -> stdout(fixture("youtube_video.json")) }
    }

    @Test
    fun `state starts initializing`() {
        assertEquals(DeviceEngineState.Initializing, engine.state.value)
    }

    @Test
    fun `initialize publishes ready with the version`() = runTest {
        engine.initialize()

        assertEquals(DeviceEngineState.Ready("2025.01.01"), engine.state.value)
    }

    @Test
    fun `initialize is idempotent`() = runTest {
        engine.initialize()
        engine.initialize()

        assertEquals(1, runner.initCalls)
    }

    @Test
    fun `concurrent initialize calls init once`() = runTest {
        List(5) { async { engine.initialize() } }.awaitAll()

        assertEquals(1, runner.initCalls)
    }

    @Test
    fun `a failing init is published and can be retried`() = runTest {
        runner.initError = IllegalStateException("no python")

        engine.initialize()

        assertEquals(DeviceEngineState.Failed("no python"), engine.state.value)

        runner.initError = null
        engine.initialize()

        assertEquals(DeviceEngineState.Ready("2025.01.01"), engine.state.value)
        assertEquals(2, runner.initCalls)
    }

    @Test
    fun `ytDlpVersion reads the runner`() = runTest {
        assertEquals("2025.01.01", engine.ytDlpVersion())
        runner.versionValue = null
        assertNull(engine.ytDlpVersion())
    }

    @Test
    fun `update that installs a new version is stored and published`() = runTest {
        val result = engine.updateYtDlp()

        assertEquals(UpdateResult.Updated("2026.10.21"), result)
        assertEquals(now, store.millis)
        assertEquals(DeviceEngineState.Ready("2026.10.21"), engine.state.value)
    }

    @Test
    fun `update that finds nothing new still counts as a check`() = runTest {
        runner.updateOutcome = UpdateOutcome.ALREADY_LATEST

        val result = engine.updateYtDlp()

        assertEquals(UpdateResult.AlreadyLatest("2025.01.01"), result)
        assertEquals(now, store.millis)
    }

    @Test
    fun `a failed update reports the message and keeps the old timestamp`() = runTest {
        store.millis = 5L
        runner.updateError = java.io.IOException("offline")

        val result = engine.updateYtDlp()

        assertEquals(UpdateResult.Failed("offline"), result)
        assertEquals(5L, store.millis)
    }

    @Test
    fun `a fresh install updates before the first resolve`() = runTest {
        resolvesVideo()

        engine.resolve(url)

        assertEquals(listOf("update", "execute"), runner.log)
        assertEquals(now, store.millis)
    }

    @Test
    fun `a recent update is not repeated`() = runTest {
        store.millis = now - 6 * day
        resolvesVideo()

        engine.resolve(url)

        assertEquals(0, runner.updateCalls)
    }

    @Test
    fun `an update older than seven days runs before resolve`() = runTest {
        store.millis = now - 7 * day - 1
        resolvesVideo()

        engine.resolve(url)

        assertEquals(listOf("update", "execute"), runner.log)
    }

    @Test
    fun `a failed best effort update does not fail the resolve`() = runTest {
        store.millis = now - 30 * day
        runner.updateError = java.io.IOException("offline")
        resolvesVideo()

        val info = engine.resolve(url)

        assertEquals("Never Gonna Give You Up", info.title)
        assertEquals(now - 30 * day, store.millis)
    }

    @Test
    fun `a failed update on a fresh install stops before resolve`() = runTest {
        runner.updateError = java.io.IOException("offline")
        resolvesVideo()

        assertTrue(engineError { engine.resolve(url) } is EngineError.Network)

        assertNull(store.millis)
        assertEquals(0, runner.calls.size)
    }

    @Test
    fun `concurrent resolves update once`() = runTest {
        resolvesVideo()
        runner.updateAction = { delay(100) }

        List(3) { async { engine.resolve(url) } }.awaitAll()

        assertEquals(1, runner.updateCalls)
        assertEquals(3, runner.calls.size)
    }

    @Test
    fun `a resolve waits while an update is running`() = runTest {
        store.millis = now
        resolvesVideo()
        val release = CompletableDeferred<Unit>()
        runner.updateAction = { release.await() }

        val updating = launch { engine.updateYtDlp() }
        runCurrent()
        val resolving = async { engine.resolve(url) }
        runCurrent()

        assertFalse(resolving.isCompleted)
        assertTrue(runner.calls.isEmpty())

        release.complete(Unit)
        advanceUntilIdle()

        assertTrue(updating.isCompleted)
        assertEquals("Never Gonna Give You Up", resolving.await().title)
    }

    @Test
    fun `resolve does not block on a running download`() = runTest {
        store.millis = now
        val release = CompletableDeferred<Unit>()
        val dir = java.nio.file.Files.createTempDirectory("dl").toFile()
        runner.handler = { args, _, _ ->
            if ("--dump-single-json" in args) stdout(fixture("youtube_video.json")) else {
                release.await()
                java.io.File(dir, "a.mp4").writeText("x")
                RunResult(0, "", "")
            }
        }

        val downloading = launch {
            engine.download(url, io.github.timhofmann27.opentakez.core.model.DownloadOption.VIDEO_BEST, dir)
                .collect { }
        }
        runCurrent()
        val info = engine.resolve(url)

        assertEquals("Never Gonna Give You Up", info.title)
        release.complete(Unit)
        advanceUntilIdle()
        downloading.cancel()
        dir.deleteRecursively()
    }

    @Test
    fun `a start failure surfaces as an engine error on resolve`() = runTest {
        runner.initError = IllegalStateException("no python")

        assertTrue(engineError { engine.resolve(url) } is EngineError.Other)
    }
}
