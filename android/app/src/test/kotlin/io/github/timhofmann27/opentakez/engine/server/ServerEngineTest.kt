@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.timhofmann27.opentakez.engine.server

import app.cash.turbine.test
import io.github.timhofmann27.opentakez.core.model.DownloadOption
import io.github.timhofmann27.opentakez.core.model.EngineError
import io.github.timhofmann27.opentakez.core.model.JobProgress
import io.github.timhofmann27.opentakez.core.model.MediaKind
import io.github.timhofmann27.opentakez.core.model.Phase
import io.github.timhofmann27.opentakez.core.model.ServerConfig
import io.github.timhofmann27.opentakez.engine.EngineEvent
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ServerEngineTest {
    @get:Rule val tmp = TemporaryFolder()

    private val server = FakeServer()
    private val engine = ServerEngine(server.config, allowCleartextForTests = true)
    private val url = "https://www.tiktok.com/@a/video/1"

    @After fun tearDown() = server.close()

    private fun workDir() = File(tmp.root, "work")

    private fun happyServer(files: List<String> = listOf("Clip [1].mp4")) {
        server.on("POST", "/api/jobs", json(201, jobJson("queued")))
        server.on(
            "GET", "/api/jobs/job1/events",
            sse(
                jobJson("queued"),
                jobJson("running", progress = """{"phase":"downloading","fraction":0.4,"speed":1000.0,"eta":12.4}"""),
                jobJson("running", progress = """{"phase":"processing","fraction":null}"""),
                jobJson("done", files = files),
            ),
        )
        files.indices.forEach { server.on("GET", "/api/jobs/job1/files/$it", fileBody("content-$it")) }
        server.on("DELETE", "/api/jobs/job1", status(204))
    }

    // resolve

    @Test fun `resolve posts the link and parses the shared MediaInfo`() = runTest {
        server.on("POST", "/api/resolve") {
            json(
                200,
                """{"url":"https://x","platform":"tiktok","kind":"video","title":"Clip","uploader":"a",
                    "thumbnail":null,"duration":12.5,"options":["video-best","audio-mp3"],"item_count":null,
                    "total_items":null,"extra":true}""",
            )
        }

        val info = engine.resolve(url)

        assertEquals("Clip", info.title)
        assertEquals(MediaKind.VIDEO, info.kind)
        assertEquals(listOf(DownloadOption.VIDEO_BEST, DownloadOption.AUDIO_MP3), info.options)
        val request = server.requests.single()
        assertEquals("POST", request.method)
        assertTrue(request.body!!.utf8().contains(""""url":"$url""""))
        assertTrue(request.headers["Content-Type"]!!.startsWith("application/json"))
    }

    @Test fun `resolve maps unreadable answers to Other`() = runTest {
        server.on("POST", "/api/resolve", json(200, "<html>not json</html>"))

        val error = assertFailsWith<EngineError.Other> { engine.resolve(url) }
        assertEquals(200, error.status)
    }

    // the token

    @Test fun `every request carries the Bearer token and no URL mentions token or ticket`() = runTest {
        happyServer()
        server.on("POST", "/api/resolve", json(422, """{"detail":"nope"}"""))

        runCatching { engine.resolve(url) }
        engine.download(url, DownloadOption.VIDEO_BEST, workDir()).toList()
        engine.releaseRemoteJob("job1")

        assertTrue(server.requests.size >= 5)
        server.requests.forEach { request ->
            assertEquals("Bearer $TOKEN", request.headers["Authorization"])
            val target = request.url.toString().lowercase()
            assertFalse(target, "token" in target || "ticket" in target)
            assertNull(request.url.query)
        }
    }

    @Test fun `a token with line breaks fails as Unauthorized without echoing it`() = runTest {
        val bad = ServerEngine(ServerConfig(server.baseUrl, "abc\ndef-secret"), allowCleartextForTests = true)

        val error = assertFailsWith<EngineError.Unauthorized> { bad.resolve(url) }

        assertFalse(error.toString().contains("secret"))
        assertTrue(server.requests.isEmpty())
    }

    @Test fun `cleartext servers are refused unless the test flag is set`() = runTest {
        val strict = ServerEngine(server.config)

        val error = assertFailsWith<EngineError.Other> { strict.resolve(url) }

        assertEquals(0, error.status)
        assertTrue(server.requests.isEmpty())
    }

    @Test fun `API redirects are refused before a Bearer token reaches another path`() = runTest {
        server.on("POST", "/api/resolve",
            status(307, "Location" to "${server.baseUrl}/leak"))

        assertFailsWith<EngineError.Other> { engine.resolve(url) }

        assertEquals(1, server.calls("POST", "/api/resolve"))
        assertEquals(0, server.calls("POST", "/leak"))
    }

    // job sequence

    @Test fun `follows queued, running, processing and done over SSE and saves the file`() = runTest {
        happyServer()

        val events = engine.download(url, DownloadOption.VIDEO_BEST, workDir()).toList()

        assertEquals("job1", (events.first() as EngineEvent.RemoteJobStarted).id)

        assertEquals(
            listOf(
                JobProgress(Phase.DOWNLOADING), // job created, queued
                JobProgress(Phase.DOWNLOADING), // queued event
                JobProgress(Phase.DOWNLOADING, 0.4, 1000.0, 12),
                JobProgress(Phase.PROCESSING),
                JobProgress(Phase.DOWNLOADING), // done event carries no progress
            ),
            events.filterIsInstance<EngineEvent.Progress>().map { it.progress },
        )
        val done = events.last() as EngineEvent.Done
        assertEquals(0, done.skipped)
        assertEquals(listOf("Clip [1].mp4"), done.files.map { it.displayName })
        assertEquals("content-0", done.files.single().file.readText())
        assertEquals(workDir().canonicalFile, done.files.single().file.parentFile)
        assertEquals(
            listOf("POST /api/jobs", "GET /api/jobs/job1/events", "GET /api/jobs/job1/files/0"),
            server.requests.map { "${it.method} ${it.url.encodedPath}" },
        )
        engine.releaseRemoteJob("job1")
        assertEquals(1, server.calls("DELETE", "/api/jobs/job1"))
        val body = server.requests.first().body!!.utf8()
        assertTrue(body, body.contains(""""option":"video-best""""))
        assertEquals("text/event-stream", server.requests[1].headers["Accept"])
    }

    @Test fun `several files and skipped items arrive in one Done`() = runTest {
        happyServer(listOf("01 A [1].jpg", "02 B [2].jpg", "03 C [3].jpg"))
        server.on("GET", "/api/jobs/job1/events", sse(jobJson("done", files = listOf("01 A [1].jpg", "02 B [2].jpg", "03 C [3].jpg"), skipped = 2)))

        val events = engine.download(url, DownloadOption.IMAGE_ORIGINAL, workDir()).toList()

        val done = events.filterIsInstance<EngineEvent.Done>().single()
        assertEquals(2, done.skipped)
        assertEquals(listOf("01 A [1].jpg", "02 B [2].jpg", "03 C [3].jpg"), done.files.map { it.displayName })
        assertEquals(listOf("content-0", "content-1", "content-2"), done.files.map { it.file.readText() })
        assertTrue(events.last() is EngineEvent.Done)
    }

    @Test fun `releases the server job only after the caller saves files, ignoring 409`() = runTest {
        happyServer()
        server.on("DELETE", "/api/jobs/job1", json(409, """{"detail":"Job is still running"}"""))

        val events = engine.download(url, DownloadOption.VIDEO_BEST, workDir()).toList()

        assertTrue(events.last() is EngineEvent.Done)
        assertEquals(0, server.calls("DELETE", "/api/jobs/job1"))
        engine.releaseRemoteJob("job1")
        assertEquals(1, server.calls("DELETE", "/api/jobs/job1"))
    }

    @Test fun `a vanished server after success does not fail the download`() = runTest {
        happyServer()
        server.on("DELETE", "/api/jobs/job1", json(404, """{"detail":"Job not found"}"""))

        val events = engine.download(url, DownloadOption.VIDEO_BEST, workDir()).toList()

        assertTrue(events.last() is EngineEvent.Done)
        engine.releaseRemoteJob("job1")
    }

    @Test fun `a persisted server job is reattached without creating another job`() = runTest {
        server.on("GET", "/api/jobs/job1", json(200, jobJson("done", files = listOf("a.mp4"))))
        server.on("GET", "/api/jobs/job1/files/0", fileBody("kept"))

        val events = engine.download(url, DownloadOption.VIDEO_BEST, workDir(), "job1").toList()

        assertEquals("job1", (events.first() as EngineEvent.RemoteJobStarted).id)
        assertEquals("kept", (events.last() as EngineEvent.Done).files.single().file.readText())
        assertEquals(0, server.calls("POST", "/api/jobs"))
        assertEquals(0, server.calls("DELETE", "/api/jobs/job1"))
    }

    @Test fun `a persisted job forgotten by the server is Lost`() = runTest {
        server.on("GET", "/api/jobs/job1", json(404, """{"detail":"Job not found"}"""))

        assertFailsWith<EngineError.Lost> {
            engine.download(url, DownloadOption.VIDEO_BEST, workDir(), "job1").toList()
        }
        assertEquals(0, server.calls("POST", "/api/jobs"))
    }

    // file names

    @Test fun `unsafe server file names stay inside the work directory`() = runTest {
        val names = listOf("../x", "a/b", "..\\evil.txt", "..", "", "ok.mp4")
        happyServer(names)

        val done = engine.download(url, DownloadOption.VIDEO_BEST, workDir()).toList().last() as EngineEvent.Done

        val root = workDir().canonicalFile
        done.files.forEach { assertEquals(root, it.file.canonicalFile.parentFile) }
        assertEquals(listOf("x", "b", "evil.txt", "file", "file (2)", "ok.mp4"), done.files.map { it.displayName })
        assertEquals(setOf("work"), tmp.root.list()!!.toSet())
        assertEquals(6, workDir().list()!!.size)
    }

    @Test fun `duplicate names do not overwrite each other`() = runTest {
        happyServer(listOf("a.mp4", "A.mp4"))

        val done = engine.download(url, DownloadOption.VIDEO_BEST, workDir()).toList().last() as EngineEvent.Done

        assertEquals(listOf("a.mp4", "A (2).mp4"), done.files.map { it.displayName })
        assertEquals(listOf("content-0", "content-1"), done.files.map { it.file.readText() })
    }

    @Test fun `very long names are shortened but keep their extension`() {
        val name = safeFileName("x".repeat(500) + ".mp4")
        assertEquals(200, name.length)
        assertTrue(name.endsWith(".mp4"))
    }

    // failing jobs

    @Test fun `an error job with login_required becomes LoginRequired with the platform`() = runTest {
        server.on("POST", "/api/jobs", json(201, jobJson("queued", platform = "instagram")))
        server.on(
            "GET", "/api/jobs/job1/events",
            sse(jobJson("error", platform = "instagram", error = "Login required", errorCode = "login_required")),
        )
        server.on("DELETE", "/api/jobs/job1", status(204))

        val error = assertFailsWith<EngineError.LoginRequired> {
            engine.download(url, DownloadOption.VIDEO_BEST, workDir()).toList()
        }

        assertEquals("instagram", error.platform)
        assertEquals(0, server.calls("DELETE", "/api/jobs/job1"))
    }

    @Test fun `an error job without code becomes Platform with the server's text`() = runTest {
        server.on("POST", "/api/jobs", json(201, jobJson("queued")))
        server.on("GET", "/api/jobs/job1/events", sse(jobJson("error", error = "Video unavailable")))
        server.on("DELETE", "/api/jobs/job1", status(204))

        val error = assertFailsWith<EngineError.Platform> {
            engine.download(url, DownloadOption.VIDEO_BEST, workDir()).toList()
        }

        assertEquals("Video unavailable", error.message)
    }

    @Test fun `creating a job maps 422 and 503`() = runTest {
        server.on("POST", "/api/jobs", json(503, """{"detail":"Too many downloads in progress, try again later"}"""))
        assertFailsWith<EngineError.Busy> { engine.download(url, DownloadOption.VIDEO_BEST, workDir()).toList() }

        server.on("POST", "/api/jobs", json(422, """{"detail":"This download option isn't available for this link"}"""))
        val error = assertFailsWith<EngineError.Platform> {
            engine.download(url, DownloadOption.AUDIO_MP3, workDir()).toList()
        }
        assertEquals("This download option isn't available for this link", error.message)
        assertEquals(0, server.calls("DELETE", "/api/jobs/job1"))
    }

    // reconnect and polling

    @Test fun `a dropped stream is reconnected after the backoff`() = runTest {
        server.on("POST", "/api/jobs", json(201, jobJson("queued")))
        server.onSequence(
            "GET", "/api/jobs/job1/events",
            sse(jobJson("running", progress = """{"phase":"downloading","fraction":0.1}""")), // then EOF
            sse(jobJson("done", files = listOf("a.mp4"))),
        )
        server.on("GET", "/api/jobs/job1", json(200, jobJson("running")))
        server.on("GET", "/api/jobs/job1/files/0", fileBody("x"))
        server.on("DELETE", "/api/jobs/job1", status(204))

        val events = engine.download(url, DownloadOption.VIDEO_BEST, workDir()).toList()

        assertTrue(events.last() is EngineEvent.Done)
        assertEquals(2, server.calls("GET", "/api/jobs/job1/events"))
        assertEquals(1, server.calls("GET", "/api/jobs/job1"))
        assertEquals(1_000, testScheduler.currentTime)
    }

    @Test fun `a job that finished during the drop is applied without reconnecting`() = runTest {
        server.on("POST", "/api/jobs", json(201, jobJson("queued")))
        server.on("GET", "/api/jobs/job1/events", sse(jobJson("running")))
        server.on("GET", "/api/jobs/job1", json(200, jobJson("done", files = listOf("a.mp4"))))
        server.on("GET", "/api/jobs/job1/files/0", fileBody("x"))
        server.on("DELETE", "/api/jobs/job1", status(204))

        val events = engine.download(url, DownloadOption.VIDEO_BEST, workDir()).toList()

        assertTrue(events.last() is EngineEvent.Done)
        assertEquals(1, server.calls("GET", "/api/jobs/job1/events"))
        assertEquals(0, testScheduler.currentTime)
    }

    @Test fun `the first reconnect waits one second, then polling takes over`() = runTest {
        server.on("POST", "/api/jobs", json(201, jobJson("queued")))
        server.on("GET", "/api/jobs/job1/events", status(500))
        var polls = 0
        server.on("GET", "/api/jobs/job1") {
            if (++polls < 3) json(200, jobJson("running")) else json(200, jobJson("done"))
        }
        server.on("DELETE", "/api/jobs/job1", status(204))

        // Failure 1: wait 1 s. Failure 2: go straight to polling (2 s), which finds the job done.
        engine.download(url, DownloadOption.VIDEO_BEST, workDir()).toList()

        assertEquals(1_000L + 2_000L, testScheduler.currentTime)
    }

    @Test fun `after two failed streams it polls every two seconds`() = runTest {
        server.on("POST", "/api/jobs", json(201, jobJson("queued")))
        server.on("GET", "/api/jobs/job1/events", status(500))
        var polls = 0
        server.on("GET", "/api/jobs/job1") {
            // One answer for each failed stream, then three polls.
            when (++polls) {
                in 1..4 -> json(200, jobJson("running", progress = """{"phase":"downloading","fraction":0.${polls}}"""))
                else -> json(200, jobJson("done", files = listOf("a.mp4")))
            }
        }
        server.on("GET", "/api/jobs/job1/files/0", fileBody("x"))
        server.on("DELETE", "/api/jobs/job1", status(204))

        val events = engine.download(url, DownloadOption.VIDEO_BEST, workDir()).toList()

        assertTrue(events.last() is EngineEvent.Done)
        assertEquals(2, server.calls("GET", "/api/jobs/job1/events"))
        assertEquals(5, server.calls("GET", "/api/jobs/job1"))
        // 1 s backoff after the first failure, then 3 polls of 2 s each.
        assertEquals(1_000L + 3 * 2_000L, testScheduler.currentTime)
    }

    @Test fun `a 429 on the stream makes the wait at least Retry-After`() = runTest {
        server.on("POST", "/api/jobs", json(201, jobJson("queued")))
        server.onSequence(
            "GET", "/api/jobs/job1/events",
            status(429, "Retry-After" to "20"),
            sse(jobJson("done", files = listOf("a.mp4"))),
        )
        server.on("GET", "/api/jobs/job1", json(200, jobJson("running")))
        server.on("GET", "/api/jobs/job1/files/0", fileBody("x"))
        server.on("DELETE", "/api/jobs/job1", status(204))

        engine.download(url, DownloadOption.VIDEO_BEST, workDir()).toList()

        assertEquals(20_000, testScheduler.currentTime)
    }

    @Test fun `a 429 while checking the job also delays the reconnect`() = runTest {
        server.on("POST", "/api/jobs", json(201, jobJson("queued")))
        server.onSequence("GET", "/api/jobs/job1/events", status(500), sse(jobJson("done")))
        server.on("GET", "/api/jobs/job1", status(429, "Retry-After" to "7"))
        server.on("DELETE", "/api/jobs/job1", status(204))

        engine.download(url, DownloadOption.VIDEO_BEST, workDir()).toList()

        assertEquals(7_000, testScheduler.currentTime)
    }

    @Test fun `404 for the job after a drop is Lost`() = runTest {
        server.on("POST", "/api/jobs", json(201, jobJson("queued")))
        server.on("GET", "/api/jobs/job1/events", status(404))
        server.on("GET", "/api/jobs/job1", json(404, """{"detail":"Job not found"}"""))

        assertFailsWith<EngineError.Lost> { engine.download(url, DownloadOption.VIDEO_BEST, workDir()).toList() }
    }

    @Test fun `401 for the job after a drop is Unauthorized`() = runTest {
        server.on("POST", "/api/jobs", json(201, jobJson("queued")))
        server.on("GET", "/api/jobs/job1/events", status(401))
        server.on("GET", "/api/jobs/job1", json(401, """{"detail":"Invalid token"}"""))

        assertFailsWith<EngineError.Unauthorized> {
            engine.download(url, DownloadOption.VIDEO_BEST, workDir()).toList()
        }
    }

    @Test fun `401 on the event stream stops before another job request`() = runTest {
        server.on("POST", "/api/jobs", json(201, jobJson("queued")))
        server.on("GET", "/api/jobs/job1/events", status(401))

        assertFailsWith<EngineError.Unauthorized> {
            engine.download(url, DownloadOption.VIDEO_BEST, workDir()).toList()
        }
        assertEquals(0, server.calls("GET", "/api/jobs/job1"))
    }

    @Test fun `a second stream 429 is respected before polling`() = runTest {
        server.on("POST", "/api/jobs", json(201, jobJson("queued")))
        server.onSequence("GET", "/api/jobs/job1/events", status(500), status(429, "Retry-After" to "8"))
        server.onSequence("GET", "/api/jobs/job1",
            json(200, jobJson("running")), json(200, jobJson("running")), json(200, jobJson("done")))

        engine.download(url, DownloadOption.VIDEO_BEST, workDir()).toList()

        assertEquals(1_000L + 8_000L, testScheduler.currentTime)
        assertEquals(2, server.calls("GET", "/api/jobs/job1/events"))
    }

    @Test fun `polling stops with Lost when the server forgot the job`() = runTest {
        server.on("POST", "/api/jobs", json(201, jobJson("queued")))
        server.on("GET", "/api/jobs/job1/events", status(500))
        server.onSequence(
            "GET", "/api/jobs/job1",
            json(200, jobJson("running")), json(200, jobJson("running")),
            json(404, """{"detail":"Job not found"}"""),
        )

        assertFailsWith<EngineError.Lost> { engine.download(url, DownloadOption.VIDEO_BEST, workDir()).toList() }
    }

    @Test fun `a file that is gone is Lost and the files of a 409 are Conflict`() = runTest {
        server.on("POST", "/api/jobs", json(201, jobJson("queued")))
        server.on("GET", "/api/jobs/job1/events", sse(jobJson("done", files = listOf("a.mp4"))))
        server.on("DELETE", "/api/jobs/job1", status(204))
        server.on("GET", "/api/jobs/job1/files/0", json(409, """{"detail":"Download isn't finished"}"""))
        assertFailsWith<EngineError.Conflict> { engine.download(url, DownloadOption.VIDEO_BEST, workDir()).toList() }

        server.on("GET", "/api/jobs/job1/files/0", json(404, """{"detail":"File not found"}"""))
        assertFailsWith<EngineError.Lost> { engine.download(url, DownloadOption.VIDEO_BEST, workDir()).toList() }
    }

    // cancellation

    @Test fun `cancelling leaves the server job available for reattachment and stops all work`() = runTest {
        server.on("POST", "/api/jobs", json(201, jobJson("queued")))
        // Headers arrive, then the body stalls: the engine is blocked reading the stream.
        server.on(
            "GET", "/api/jobs/job1/events",
            MockResponse.Builder().code(200).addHeader("Content-Type", "text/event-stream")
                .body(": hi\n\n").bodyDelay(30, TimeUnit.SECONDS).build(),
        )
        server.on("DELETE", "/api/jobs/job1", json(409, """{"detail":"Job is still running"}"""))

        engine.download(url, DownloadOption.VIDEO_BEST, workDir()).test {
            awaitItem() // created
            assertNotNull(server.awaitRequest("GET", "/api/jobs/job1/events"))
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(0, server.calls("DELETE", "/api/jobs/job1"))
        assertEquals(0, server.calls("GET", "/api/jobs/job1/files/0"))
        assertEquals(0, server.calls("GET", "/api/jobs/job1"))
        assertEquals(1, server.calls("GET", "/api/jobs/job1/events"))
    }

    // error mapping of plain calls

    @Test fun `HTTP errors map like the PWA`() = runTest {
        val cases = listOf(
            json(400, """{"detail":"No link found"}""") to { e: EngineError -> e is EngineError.NoLink },
            json(400, """{"detail":"This site isn't supported"}""") to { e: EngineError -> e is EngineError.Unsupported },
            json(400, """{"detail":"Odd"}""") to { e -> e is EngineError.Other && e.status == 400 && e.message == "Odd" },
            json(401, """{"detail":"Invalid token"}""") to { e -> e is EngineError.Unauthorized },
            json(404, "") to { e -> e is EngineError.Lost },
            json(409, """{"detail":"x"}""") to { e -> e is EngineError.Conflict },
            json(422, """{"detail":"Video unavailable"}""") to { e -> e is EngineError.Platform && e.message == "Video unavailable" },
            json(422, """{"detail":{"message":"Login needed","code":"login_required"}}""") to { e -> e is EngineError.LoginRequired },
            json(422, """{"detail":{"message":"Other thing","code":"zzz"}}""") to { e -> e is EngineError.Platform && e.message == "Other thing" },
            status(429, "Retry-After" to "42") to { e -> e is EngineError.RateLimited && e.retryAfterSeconds == 42 },
            status(429) to { e -> e is EngineError.RateLimited && e.retryAfterSeconds == null },
            json(503, """{"detail":"Server is busy, try again"}""") to { e -> e is EngineError.Busy },
            json(500, "boom") to { e -> e is EngineError.Other && e.status == 500 },
        )
        for ((response, matches) in cases) {
            server.on("POST", "/api/resolve", response)
            val error = assertFailsWith<EngineError> { engine.resolve(url) }
            assertTrue("$response -> $error", matches(error))
        }
    }

    @Test fun `an unreachable server is a Network error without the address or token`() = runTest {
        val offline = ServerEngine(server.config, allowCleartextForTests = true)
        server.shutdown()

        val error = assertFailsWith<EngineError.Network> { offline.resolve(url) }

        assertFalse(error.toString().contains(TOKEN))
        assertFalse(error.toString().contains(server.baseUrl))
    }
}
