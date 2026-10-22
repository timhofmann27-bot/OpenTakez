package io.github.timhofmann27.opentakez.work

import io.github.timhofmann27.opentakez.core.model.DownloadOption
import io.github.timhofmann27.opentakez.core.model.DownloadedFile
import io.github.timhofmann27.opentakez.core.model.Engine
import io.github.timhofmann27.opentakez.core.model.EngineError
import io.github.timhofmann27.opentakez.core.model.JobProgress
import io.github.timhofmann27.opentakez.core.model.MediaInfo
import io.github.timhofmann27.opentakez.core.model.Phase
import io.github.timhofmann27.opentakez.data.AppDatabase
import io.github.timhofmann27.opentakez.data.DownloadRepository
import io.github.timhofmann27.opentakez.data.DownloadStatus
import io.github.timhofmann27.opentakez.data.ErrorCodes
import io.github.timhofmann27.opentakez.data.inMemoryDatabase
import io.github.timhofmann27.opentakez.data.mediaInfo
import io.github.timhofmann27.opentakez.engine.EngineEvent
import io.github.timhofmann27.opentakez.engine.MediaEngine
import io.github.timhofmann27.opentakez.storage.FileSaver
import io.github.timhofmann27.opentakez.storage.SavedFile
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class FakeEngine(private val block: suspend kotlinx.coroutines.flow.FlowCollector<EngineEvent>.(File) -> Unit) :
    MediaEngine {
    var calls = 0
    var lastUrl: String? = null
    var lastOption: DownloadOption? = null
    var existingJobId: String? = null
    val released = mutableListOf<String>()
    var onRelease: suspend (String) -> Unit = {}

    override suspend fun resolve(url: String): MediaInfo = error("not used")

    override fun download(
        url: String,
        option: DownloadOption,
        workDir: File,
        existingJobId: String?,
    ): Flow<EngineEvent> {
        calls++
        lastUrl = url
        lastOption = option
        this.existingJobId = existingJobId
        return flow { block(workDir) }
    }

    override suspend fun releaseRemoteJob(id: String) {
        released += id
        onRelease(id)
    }
}

private class FakeSaver(private val failOnCall: Int? = null) : FileSaver {
    val saved = mutableListOf<Pair<String, String>>() // displayName -> content
    private var calls = 0

    override suspend fun save(file: File, displayName: String): SavedFile {
        calls++
        if (calls == failOnCall) throw IOException("disk write failed")
        saved += displayName to file.readText()
        return SavedFile("content://media/$displayName", displayName)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloadRunnerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var repo: DownloadRepository
    private var freeBytes = Long.MAX_VALUE

    @Before
    fun setUp() {
        db = inMemoryDatabase()
        repo = DownloadRepository(db.downloadDao())
    }

    @After
    fun tearDown() = db.close()

    private fun runner(engine: MediaEngine, saver: FileSaver = FakeSaver(), engines: MutableList<Engine>? = null) =
        DownloadRunner(
            repository = repo,
            engines = EngineProvider {
                engines?.add(it)
                engine
            },
            saver = saver,
            workDirs = CacheWorkDirFactory(tmp.root),
            freeSpace = FreeSpaceChecker { freeBytes },
        )

    private suspend fun newJob(engine: Engine = Engine.DEVICE) =
        repo.create(mediaInfo(), DownloadOption.VIDEO_BEST, engine).id

    private fun jobsDir() = File(tmp.root, "jobs")

    private fun assertWorkDirGone(id: String) = assertFalse(File(jobsDir(), id).exists())

    private suspend fun kotlinx.coroutines.flow.FlowCollector<EngineEvent>.deliver(
        workDir: File,
        vararg names: String,
        skipped: Int = 0,
    ) {
        val files = names.map { name ->
            DownloadedFile(File(workDir, name).apply { writeText("content of $name") }, name)
        }
        emit(EngineEvent.Done(files, skipped))
    }

    @Test
    fun `happy path with one file`() = runTest {
        val id = newJob()
        val used = mutableListOf<Engine>()
        val engine = FakeEngine { dir -> deliver(dir, "Title [abc].mp4") }
        val saver = FakeSaver()

        val outcome = runner(engine, saver, used).run(id)

        assertEquals(DownloadRunner.Outcome.DONE, outcome)
        assertEquals(listOf(Engine.DEVICE), used)
        assertEquals("https://www.youtube.com/watch?v=abc", engine.lastUrl)
        assertEquals(DownloadOption.VIDEO_BEST, engine.lastOption)
        assertEquals(listOf("Title [abc].mp4" to "content of Title [abc].mp4"), saver.saved)
        val row = repo.get(id)!!
        assertEquals(DownloadStatus.DONE, row.status)
        assertEquals(listOf("content://media/Title [abc].mp4"), row.files)
        assertEquals(0, row.skipped)
        assertNotNull(row.finishedAt)
        assertWorkDirGone(id)
    }

    @Test
    fun `server jobs ask the provider for the server engine`() = runTest {
        val id = newJob(Engine.SERVER)
        val used = mutableListOf<Engine>()

        runner(FakeEngine { deliver(it, "a.mp3") }, engines = used).run(id)

        assertEquals(listOf(Engine.SERVER), used)
    }

    @Test
    fun `remote job id is persisted before file transfer and released after durable save`() = runTest {
        val id = newJob(Engine.SERVER)
        val saver = FakeSaver()
        val engine = FakeEngine { dir ->
            emit(EngineEvent.RemoteJobStarted("remote-1"))
            assertEquals("remote-1", repo.get(id)?.serverJobId)
            deliver(dir, "a.mp3")
        }
        engine.onRelease = {
            assertEquals(DownloadStatus.DONE, repo.get(id)?.status)
            assertEquals(1, saver.saved.size)
        }

        assertEquals(DownloadRunner.Outcome.DONE, runner(engine, saver).run(id))
        assertEquals(listOf("remote-1"), engine.released)
    }

    @Test
    fun `server retry reattaches and does not save an already saved file twice`() = runTest {
        val id = newJob(Engine.SERVER)
        val saver = FakeSaver(failOnCall = 2)
        val first = FakeEngine { dir ->
            emit(EngineEvent.RemoteJobStarted("remote-2"))
            deliver(dir, "01 a.mp3", "02 b.mp3")
        }

        assertEquals(DownloadRunner.Outcome.FAILED, runner(first, saver).run(id))
        assertEquals("remote-2", repo.get(id)?.serverJobId)
        assertEquals(listOf("content://media/01 a.mp3"), repo.get(id)?.files)
        assertTrue(first.released.isEmpty())
        assertTrue(repo.requeue(id))

        val secondSaver = FakeSaver()
        val second = FakeEngine { dir -> deliver(dir, "01 a.mp3", "02 b.mp3") }
        assertEquals(DownloadRunner.Outcome.DONE, runner(second, secondSaver).run(id))
        assertEquals("remote-2", second.existingJobId)
        assertEquals(listOf("02 b.mp3"), secondSaver.saved.map { it.first })
        assertEquals(listOf("content://media/01 a.mp3", "content://media/02 b.mp3"), repo.get(id)?.files)
        assertEquals(listOf("remote-2"), second.released)
    }

    @Test
    fun `many files keep names and order and skipped is stored`() = runTest {
        val id = newJob()
        val names = (1..12).map { "%02d Title [id$it].mp4".format(it) }
        val saver = FakeSaver()
        val engine = FakeEngine { dir -> deliver(dir, *names.toTypedArray(), skipped = 3) }

        runner(engine, saver).run(id)

        assertEquals(names, saver.saved.map { it.first })
        val row = repo.get(id)!!
        assertEquals(names.map { "content://media/$it" }, row.files)
        assertEquals(3, row.skipped)
        assertEquals(DownloadStatus.DONE, row.status)
    }

    @Test
    fun `progress is mirrored in order`() = runTest {
        val id = newJob()
        val seen = mutableListOf<JobProgress>()
        val steps = listOf(
            JobProgress(Phase.DOWNLOADING, null),
            JobProgress(Phase.DOWNLOADING, 0.5, 1_000_000.0, 20),
            JobProgress(Phase.PROCESSING, 1.0),
        )
        val engine = FakeEngine { dir ->
            steps.forEach { emit(EngineEvent.Progress(it)) }
            deliver(dir, "a.mp4")
        }

        runner(engine).run(id, onProgress = { seen += it })

        assertEquals(steps, seen)
    }

    @Test
    fun `not enough free space fails before the engine starts`() = runTest {
        val id = newJob()
        freeBytes = StorageRequirement.UNKNOWN_SIZE_BYTES - 1
        val engine = FakeEngine { deliver(it, "a.mp4") }

        val outcome = runner(engine).run(id)

        assertEquals(DownloadRunner.Outcome.FAILED, outcome)
        assertEquals(0, engine.calls)
        val row = repo.get(id)!!
        assertEquals(DownloadStatus.FAILED, row.status)
        assertEquals(ErrorCodes.STORAGE_FULL, row.errorCode)
        assertWorkDirGone(id)
    }

    @Test
    fun `exactly enough free space runs`() = runTest {
        val id = newJob()
        freeBytes = StorageRequirement.UNKNOWN_SIZE_BYTES

        assertEquals(DownloadRunner.Outcome.DONE, runner(FakeEngine { deliver(it, "a.mp4") }).run(id))
    }

    @Test
    fun `required space is twice the expected size or 300 MB when unknown`() {
        assertEquals(300L * 1024 * 1024, StorageRequirement.requiredBytes(null))
        assertEquals(300L * 1024 * 1024, StorageRequirement.requiredBytes(0))
        assertEquals(2_000L, StorageRequirement.requiredBytes(1_000))
    }

    @Test
    fun `every engine error maps to its code`() = runTest {
        val cases = listOf<Pair<EngineError, String>>(
            EngineError.Network("offline") to ErrorCodes.NETWORK,
            EngineError.Unauthorized() to ErrorCodes.UNAUTHORIZED,
            EngineError.Lost() to ErrorCodes.LOST,
            EngineError.Conflict() to ErrorCodes.CONFLICT,
            EngineError.NoLink() to ErrorCodes.NO_LINK,
            EngineError.Unsupported() to ErrorCodes.UNSUPPORTED,
            EngineError.LoginRequired("instagram") to ErrorCodes.LOGIN_REQUIRED,
            EngineError.RateLimited(30) to ErrorCodes.RATE_LIMITED,
            EngineError.Busy() to ErrorCodes.BUSY,
            EngineError.NeedsServer() to ErrorCodes.NEEDS_SERVER,
            EngineError.YoutubeBlocked() to ErrorCodes.YOUTUBE_BLOCKED,
            EngineError.StorageFull() to ErrorCodes.STORAGE_FULL,
            EngineError.Platform("Video unavailable") to ErrorCodes.PLATFORM,
            EngineError.Other(500, "boom") to ErrorCodes.OTHER,
        )
        for ((error, code) in cases) {
            val id = newJob()
            val outcome = runner(FakeEngine { throw error }).run(id)

            assertEquals(code, DownloadRunner.Outcome.FAILED, outcome)
            val row = repo.get(id)!!
            assertEquals(code, DownloadStatus.FAILED, row.status)
            assertEquals(code, row.errorCode)
            assertEquals(error.message, row.errorMessage)
            assertNotNull(row.finishedAt)
            assertWorkDirGone(id)
        }
    }

    @Test
    fun `an engine error after progress still fails the row`() = runTest {
        val id = newJob()
        val engine = FakeEngine {
            emit(EngineEvent.Progress(JobProgress(Phase.DOWNLOADING, 0.4)))
            throw EngineError.Network("connection reset")
        }

        runner(engine).run(id)

        assertEquals(ErrorCodes.NETWORK, repo.get(id)!!.errorCode)
    }

    @Test
    fun `unexpected exceptions are stored short and without a stack trace`() = runTest {
        val id = newJob()
        val secret = "tok-12345"
        val long = "x".repeat(2_000)
        val engine = FakeEngine { throw IllegalStateException("first line $long\nat com.example.Foo.bar(Foo.kt:1)\nsecond $secret") }

        runner(engine).run(id)

        val row = repo.get(id)!!
        assertEquals(ErrorCodes.OTHER, row.errorCode)
        val message = row.errorMessage!!
        assertTrue(message.startsWith("first line"))
        assertFalse(message.contains("\n"))
        assertFalse(message.contains("com.example.Foo"))
        assertFalse(message.contains(secret))
        assertTrue(message.length <= 310)
    }

    @Test
    fun `an exception without a message stores its class name`() = runTest {
        val id = newJob()

        runner(FakeEngine { throw IllegalArgumentException() }).run(id)

        assertEquals("IllegalArgumentException", repo.get(id)!!.errorMessage)
    }

    @Test
    fun `finishing without files fails`() = runTest {
        val id = newJob()

        runner(FakeEngine { emit(EngineEvent.Done(emptyList(), skipped = 2)) }).run(id)

        val row = repo.get(id)!!
        assertEquals(DownloadStatus.FAILED, row.status)
        assertEquals(ErrorCodes.OTHER, row.errorCode)
    }

    @Test
    fun `ending without a done event fails`() = runTest {
        val id = newJob()

        runner(FakeEngine { emit(EngineEvent.Progress(JobProgress(Phase.DOWNLOADING))) }).run(id)

        assertEquals(DownloadStatus.FAILED, repo.get(id)!!.status)
    }

    @Test
    fun `a save failing midway fails the job and keeps the already saved uris`() = runTest {
        val id = newJob()
        val saver = FakeSaver(failOnCall = 2)
        val engine = FakeEngine { deliver(it, "01 a.mp4", "02 b.mp4", "03 c.mp4") }

        val outcome = runner(engine, saver).run(id)

        assertEquals(DownloadRunner.Outcome.FAILED, outcome)
        assertEquals(listOf("01 a.mp4"), saver.saved.map { it.first })
        val row = repo.get(id)!!
        assertEquals(DownloadStatus.FAILED, row.status)
        assertEquals(listOf("content://media/01 a.mp4"), row.files)
        assertEquals("disk write failed", row.errorMessage)
        assertWorkDirGone(id)
    }

    @Test
    fun `a missing storage permission has its own code`() = runTest {
        val id = newJob()
        val saver = object : FileSaver {
            override suspend fun save(file: File, displayName: String): SavedFile =
                throw io.github.timhofmann27.opentakez.storage.StoragePermissionException()
        }

        runner(FakeEngine { deliver(it, "a.mp4") }, saver).run(id)

        assertEquals(ErrorCodes.STORAGE_PERMISSION, repo.get(id)!!.errorCode)
    }

    @Test
    fun `cancelling marks the row cancelled and deletes the work dir`() = runTest {
        val id = newJob()
        val started = CompletableDeferred<File>()
        val engine = FakeEngine { dir ->
            File(dir, "partial.mp4.part").writeText("half")
            started.complete(dir)
            awaitCancellation()
        }
        val job = launch { runner(engine).run(id) }

        val dir = started.await()
        assertTrue(File(dir, "partial.mp4.part").exists())
        job.cancelAndJoin()

        assertEquals(DownloadStatus.CANCELLED, repo.get(id)!!.status)
        assertNull(repo.get(id)!!.errorCode)
        assertWorkDirGone(id)
    }

    @Test
    fun `a system stop fails the row so Retry shows up`() = runTest {
        val id = newJob()
        val started = CompletableDeferred<Unit>()
        val engine = FakeEngine {
            started.complete(Unit)
            awaitCancellation()
        }
        val job = launch { runner(engine).run(id, stoppedBySystem = { true }) }

        started.await()
        job.cancelAndJoin()

        val row = repo.get(id)!!
        assertEquals(DownloadStatus.FAILED, row.status)
        assertEquals(ErrorCodes.STOPPED, row.errorCode)
        assertWorkDirGone(id)
    }

    @Test
    fun `cancellation during saving keeps nothing half done`() = runTest {
        val id = newJob()
        val saving = CompletableDeferred<Unit>()
        val saver = object : FileSaver {
            override suspend fun save(file: File, displayName: String): SavedFile {
                saving.complete(Unit)
                awaitCancellation()
            }
        }
        val job = launch { runner(FakeEngine { deliver(it, "a.mp4") }, saver).run(id) }

        saving.await()
        job.cancelAndJoin()

        assertEquals(DownloadStatus.CANCELLED, repo.get(id)!!.status)
        assertWorkDirGone(id)
    }

    @Test
    fun `unknown or finished rows are not run`() = runTest {
        val engine = FakeEngine { deliver(it, "a.mp4") }
        assertEquals(DownloadRunner.Outcome.NOT_RUNNABLE, runner(engine).run("missing"))

        val done = newJob()
        runner(engine).run(done)
        assertEquals(DownloadRunner.Outcome.NOT_RUNNABLE, runner(engine).run(done))

        val cancelled = newJob()
        repo.markCancelled(cancelled)
        assertEquals(DownloadRunner.Outcome.NOT_RUNNABLE, runner(engine).run(cancelled))

        assertEquals(1, engine.calls)
    }

    @Test
    fun `a row that was cancelled while running stays cancelled when the engine then fails`() = runTest {
        val id = newJob()
        val engine = FakeEngine {
            repo.markCancelled(id)
            throw EngineError.Network("late")
        }

        runner(engine).run(id)

        assertEquals(DownloadStatus.CANCELLED, repo.get(id)!!.status)
    }

    @Test
    fun `a retried job runs again from a clean work dir`() = runTest {
        val id = newJob()
        runner(FakeEngine { throw EngineError.Network("offline") }).run(id)
        assertTrue(repo.requeue(id))
        File(jobsDir(), id).mkdirs()
        File(jobsDir(), "$id/stale.part").writeText("old")
        var sawStale = true
        val engine = FakeEngine { dir ->
            sawStale = File(dir, "stale.part").exists()
            deliver(dir, "a.mp4")
        }

        val outcome = runner(engine).run(id)

        assertEquals(DownloadRunner.Outcome.DONE, outcome)
        assertFalse(sawStale)
    }
}
