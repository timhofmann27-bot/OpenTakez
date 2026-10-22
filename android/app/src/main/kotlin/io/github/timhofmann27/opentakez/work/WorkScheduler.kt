package io.github.timhofmann27.opentakez.work

import io.github.timhofmann27.opentakez.core.model.Engine
import io.github.timhofmann27.opentakez.core.model.JobProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the scheduler needs from WorkManager, so the queue policy is testable on the JVM. */
interface WorkBackend {
    /** Work in the unique queue [queue] that has not finished yet (enqueued, blocked, running). */
    suspend fun pendingCount(queue: String): Int

    /** Appends the job to [queue]; a failed or cancelled predecessor does not block it. */
    fun enqueue(queue: String, rowId: String)

    fun cancel(rowId: String)

    fun observeProgress(rowId: String): Flow<JobProgress?>

    fun schedulePeriodicYtDlpUpdate()
}

/**
 * Queue policy. Device jobs share one unique queue, so they run one at a time (yt-dlp and ffmpeg
 * are heavy, and the engine serialises updates and downloads anyway). Server jobs run in
 * [SERVER_LANES] unique queues of their own, which caps them at 3 in parallel. A new server job
 * joins the lane with the fewest unfinished jobs. WorkManager has no "max N concurrent" for one
 * queue, and a semaphore in the worker would hold a foreground notification while it waits;
 * lanes avoid both. The trade-off: a job can sit behind a long one in its lane while another
 * lane frees up first.
 */
class WorkScheduler(private val backend: WorkBackend) {
    private val mutex = Mutex()

    suspend fun enqueue(rowId: String, engine: Engine) {
        mutex.withLock {
            val queue = when (engine) {
                Engine.DEVICE -> DEVICE_QUEUE
                Engine.SERVER -> serverQueues.minBy { backend.pendingCount(it) }
            }
            backend.enqueue(queue, rowId)
        }
    }

    /** Stops the job whether it is waiting or running. The row's status is the repository's job. */
    fun cancel(rowId: String) = backend.cancel(rowId)

    /** Live progress of a running job (not stored in the database), null while unknown. */
    fun progress(rowId: String): Flow<JobProgress?> = backend.observeProgress(rowId)

    /** Idempotent: keeps an existing schedule. */
    fun scheduleYtDlpUpdates() = backend.schedulePeriodicYtDlpUpdate()

    companion object {
        const val DEVICE_QUEUE = "device"
        const val SERVER_LANES = 3
        val serverQueues = List(SERVER_LANES) { "server-$it" }
    }
}
