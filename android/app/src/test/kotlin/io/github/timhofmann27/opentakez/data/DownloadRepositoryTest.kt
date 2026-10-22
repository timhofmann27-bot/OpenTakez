package io.github.timhofmann27.opentakez.data

import app.cash.turbine.test
import io.github.timhofmann27.opentakez.core.model.DownloadOption
import io.github.timhofmann27.opentakez.core.model.Engine
import io.github.timhofmann27.opentakez.core.model.MediaKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloadRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: DownloadRepository

    @Before
    fun setUp() {
        db = inMemoryDatabase()
        repo = DownloadRepository(db.downloadDao(), clock = TickClock(), newId = sequentialIds())
    }

    @After
    fun tearDown() = db.close()

    private suspend fun newJob(title: String = "t", engine: Engine = Engine.DEVICE) =
        repo.create(mediaInfo(title), DownloadOption.VIDEO_BEST, engine)

    @Test
    fun `create stores a queued row from the media info`() = runTest {
        val row = repo.create(mediaInfo("Hello"), DownloadOption.AUDIO_MP3, Engine.SERVER)

        val stored = repo.get(row.id)!!
        assertEquals("id-0", stored.id)
        assertEquals(DownloadStatus.QUEUED, stored.status)
        assertEquals("Hello", stored.title)
        assertEquals("youtube", stored.platform)
        assertEquals(MediaKind.VIDEO, stored.kind)
        assertEquals("Someone", stored.uploader)
        assertEquals(DownloadOption.AUDIO_MP3, stored.option)
        assertEquals(Engine.SERVER, stored.engine)
        assertEquals(emptyList<String>(), stored.files)
        assertEquals(0, stored.skipped)
        assertNull(stored.finishedAt)
        assertNull(stored.errorCode)
    }

    @Test
    fun `happy path queued running done`() = runTest {
        val row = newJob()

        assertTrue(repo.markRunning(row.id))
        assertEquals(DownloadStatus.RUNNING, repo.get(row.id)!!.status)

        assertTrue(repo.markDone(row.id, listOf("content://a", "content://b"), skipped = 2))
        val done = repo.get(row.id)!!
        assertEquals(DownloadStatus.DONE, done.status)
        assertEquals(listOf("content://a", "content://b"), done.files)
        assertEquals(2, done.skipped)
        assertNotNull(done.finishedAt)
        assertNull(done.errorCode)
    }

    @Test
    fun `failed keeps code message and already saved files`() = runTest {
        val row = newJob()
        repo.markRunning(row.id)

        assertTrue(repo.markFailed(row.id, ErrorCodes.PLATFORM, "Video unavailable", listOf("content://a")))

        val failed = repo.get(row.id)!!
        assertEquals(DownloadStatus.FAILED, failed.status)
        assertEquals("platform", failed.errorCode)
        assertEquals("Video unavailable", failed.errorMessage)
        assertEquals(listOf("content://a"), failed.files)
        assertNotNull(failed.finishedAt)
    }

    @Test
    fun `cancelled works from queued and from running`() = runTest {
        val queued = newJob()
        val running = newJob()
        repo.markRunning(running.id)

        assertTrue(repo.markCancelled(queued.id))
        assertTrue(repo.markCancelled(running.id))

        assertEquals(DownloadStatus.CANCELLED, repo.get(queued.id)!!.status)
        assertEquals(DownloadStatus.CANCELLED, repo.get(running.id)!!.status)
    }

    @Test
    fun `a finished row cannot be overwritten by a late transition`() = runTest {
        val row = newJob()
        repo.markRunning(row.id)
        repo.markDone(row.id, listOf("content://a"), 0)

        assertFalse(repo.markCancelled(row.id))
        assertFalse(repo.markFailed(row.id, ErrorCodes.OTHER, "late"))
        assertFalse(repo.markRunning(row.id))

        val stored = repo.get(row.id)!!
        assertEquals(DownloadStatus.DONE, stored.status)
        assertNull(stored.errorCode)
    }

    @Test
    fun `a cancelled row does not turn running`() = runTest {
        val row = newJob()
        repo.markCancelled(row.id)

        assertFalse(repo.markRunning(row.id))
        assertEquals(DownloadStatus.CANCELLED, repo.get(row.id)!!.status)
    }

    @Test
    fun `requeue retains a failed remote job and saved files for resume`() = runTest {
        val row = newJob()
        repo.markRunning(row.id)
        repo.setServerJobId(row.id, "job-1")
        repo.markFailed(row.id, ErrorCodes.NETWORK, "offline", listOf("content://a"))

        assertTrue(repo.requeue(row.id))

        val queued = repo.get(row.id)!!
        assertEquals(DownloadStatus.QUEUED, queued.status)
        assertNull(queued.errorCode)
        assertNull(queued.errorMessage)
        assertEquals("job-1", queued.serverJobId)
        assertNull(queued.finishedAt)
        assertEquals(listOf("content://a"), queued.files)
        assertFalse(repo.requeue(row.id)) // already queued
    }

    @Test
    fun `requeue clears a cancelled remote job`() = runTest {
        val row = newJob()
        repo.markRunning(row.id)
        repo.setServerJobId(row.id, "job-2")
        repo.markCancelled(row.id)

        assertTrue(repo.requeue(row.id))
        assertNull(repo.get(row.id)?.serverJobId)
        assertEquals(emptyList<String>(), repo.get(row.id)?.files)
    }

    @Test
    fun `missing id is handled`() = runTest {
        assertNull(repo.get("nope"))
        assertFalse(repo.markRunning("nope"))
        assertFalse(repo.markCancelled("nope"))
        repo.remove("nope")
    }

    @Test
    fun `active lists queued running failed cancelled newest first and done separately`() = runTest {
        val a = newJob("a")
        val b = newJob("b")
        val c = newJob("c")
        val d = newJob("d")
        val e = newJob("e")
        repo.markRunning(b.id)
        repo.markRunning(c.id)
        repo.markFailed(c.id, ErrorCodes.OTHER, null)
        repo.markCancelled(d.id)
        repo.markRunning(e.id)
        repo.markDone(e.id, listOf("content://e"), 0)

        assertEquals(listOf("d", "c", "b", "a"), repo.active.first().map { it.title })
        assertEquals(listOf("e"), repo.done.first().map { it.title })
    }

    @Test
    fun `done is ordered by finish time not by creation`() = runTest {
        val first = newJob("first")
        val second = newJob("second")
        repo.markRunning(second.id)
        repo.markDone(second.id, listOf("content://2"), 0)
        repo.markRunning(first.id)
        repo.markDone(first.id, listOf("content://1"), 0) // finished last

        assertEquals(listOf("first", "second"), repo.done.first().map { it.title })
    }

    @Test
    fun `flows emit on every transition`() = runTest {
        val row = newJob("x")

        repo.active.test {
            assertEquals(DownloadStatus.QUEUED, awaitItem().single().status)
            repo.markRunning(row.id)
            assertEquals(DownloadStatus.RUNNING, awaitItem().single().status)
            repo.markFailed(row.id, ErrorCodes.NETWORK, "offline")
            assertEquals(DownloadStatus.FAILED, awaitItem().single().status)
            cancelAndIgnoreRemainingEvents()
        }
        repo.done.test {
            assertTrue(awaitItem().isEmpty())
            repo.requeue(row.id)
            repo.markRunning(row.id)
            repo.markDone(row.id, listOf("content://x"), 0)
            // Intermediate invalidations may re-emit the empty list first.
            var list = awaitItem()
            while (list.isEmpty()) list = awaitItem()
            assertEquals("x", list.single().title)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observe single row emits null after removal`() = runTest {
        val row = newJob()

        repo.observe(row.id).test {
            assertEquals(row.id, awaitItem()!!.id)
            repo.remove(row.id)
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `clearDone removes only finished rows`() = runTest {
        val keep = newJob("keep")
        val gone = newJob("gone")
        repo.markRunning(gone.id)
        repo.markDone(gone.id, listOf("content://g"), 0)

        repo.clearDone()

        assertNotNull(repo.get(keep.id))
        assertNull(repo.get(gone.id))
    }

    @Test
    fun `many rows keep their order`() = runTest {
        repeat(500) { newJob("job $it") }

        val titles = repo.active.first().map { it.title }
        assertEquals(500, titles.size)
        assertEquals("job 499", titles.first())
        assertEquals("job 0", titles.last())
    }
}
