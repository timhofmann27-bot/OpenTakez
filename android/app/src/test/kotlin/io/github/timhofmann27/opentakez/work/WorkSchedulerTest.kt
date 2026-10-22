package io.github.timhofmann27.opentakez.work

import io.github.timhofmann27.opentakez.core.model.Engine
import io.github.timhofmann27.opentakez.core.model.JobProgress
import io.github.timhofmann27.opentakez.core.model.Phase
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class FakeBackend : WorkBackend {
    val enqueued = mutableListOf<Pair<String, String>>() // queue -> rowId
    val cancelled = mutableListOf<String>()
    val pending = mutableMapOf<String, Int>()
    var periodic = 0

    override suspend fun pendingCount(queue: String): Int = pending[queue] ?: 0

    override fun enqueue(queue: String, rowId: String) {
        enqueued += queue to rowId
        pending[queue] = (pending[queue] ?: 0) + 1
    }

    override fun cancel(rowId: String) {
        cancelled += rowId
    }

    override fun observeProgress(rowId: String): Flow<JobProgress?> = flowOf(null)

    override fun schedulePeriodicYtDlpUpdate() {
        periodic++
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorkSchedulerTest {
    private val backend = FakeBackend()
    private val scheduler = WorkScheduler(backend)

    @Test
    fun `device jobs all use the single device queue`() = runTest {
        scheduler.enqueue("a", Engine.DEVICE)
        scheduler.enqueue("b", Engine.DEVICE)
        scheduler.enqueue("c", Engine.DEVICE)

        assertEquals(listOf("device"), backend.enqueued.map { it.first }.distinct())
        assertEquals(listOf("a", "b", "c"), backend.enqueued.map { it.second })
    }

    @Test
    fun `server jobs spread over three lanes`() = runTest {
        repeat(6) { scheduler.enqueue("s$it", Engine.SERVER) }

        val perLane = backend.enqueued.groupBy({ it.first }, { it.second })
        assertEquals(setOf("server-0", "server-1", "server-2"), perLane.keys)
        assertTrue(perLane.values.all { it.size == 2 })
    }

    @Test
    fun `a new server job joins the emptiest lane`() = runTest {
        backend.pending["server-0"] = 2
        backend.pending["server-1"] = 1
        backend.pending["server-2"] = 0

        scheduler.enqueue("x", Engine.SERVER)

        assertEquals(listOf("server-2" to "x"), backend.enqueued)
    }

    @Test
    fun `server jobs never use the device queue and the reverse`() = runTest {
        scheduler.enqueue("s", Engine.SERVER)
        scheduler.enqueue("d", Engine.DEVICE)

        assertTrue(backend.enqueued.first().first.startsWith("server-"))
        assertEquals("device", backend.enqueued.last().first)
    }

    @Test
    fun `concurrent enqueues all get a lane`() = runTest {
        val jobs = (0 until 30).map { i ->
            async { scheduler.enqueue("j$i", Engine.SERVER) }
        }
        jobs.forEach { it.await() }

        val sizes = backend.enqueued.groupBy { it.first }.mapValues { it.value.size }.values
        assertEquals(30, backend.enqueued.size)
        assertTrue(sizes.all { it == 10 })
    }

    @Test
    fun `cancel and periodic scheduling are delegated`() {
        scheduler.cancel("a")
        scheduler.scheduleYtDlpUpdates()

        assertEquals(listOf("a"), backend.cancelled)
        assertEquals(1, backend.periodic)
    }

    @Test
    fun `download request is expedited, needs network and is tagged by row`() {
        val request = DownloadWorker.request("row-1")

        assertTrue(request.workSpec.expedited)
        assertEquals("row-1", request.workSpec.input.getString(DownloadWorker.KEY_ROW_ID))
        assertTrue(DownloadWorker.tagFor("row-1") in request.tags)
        assertEquals(androidx.work.NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
    }

    @Test
    fun `yt-dlp update request is weekly on an unmetered network`() {
        val request = YtDlpUpdateWorker.periodicRequest()

        assertEquals(java.util.concurrent.TimeUnit.DAYS.toMillis(7), request.workSpec.intervalDuration)
        assertEquals(androidx.work.NetworkType.UNMETERED, request.workSpec.constraints.requiredNetworkType)
    }

    @Test
    fun `progress survives the work data round trip`() {
        val full = JobProgress(Phase.PROCESSING, 0.25, 2048.0, 17)
        val unknown = JobProgress(Phase.DOWNLOADING)

        assertEquals(full, full.toData().toJobProgress())
        assertEquals(unknown, unknown.toData().toJobProgress())
        assertNull(androidx.work.Data.EMPTY.toJobProgress())
    }
}
