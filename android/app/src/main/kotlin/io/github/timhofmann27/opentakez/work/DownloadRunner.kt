package io.github.timhofmann27.opentakez.work

import io.github.timhofmann27.opentakez.core.model.EngineError
import io.github.timhofmann27.opentakez.core.model.JobProgress
import io.github.timhofmann27.opentakez.data.DownloadRepository
import io.github.timhofmann27.opentakez.data.ErrorCodes
import io.github.timhofmann27.opentakez.engine.EngineEvent
import io.github.timhofmann27.opentakez.storage.FileSaver
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** The business logic of one download job, free of WorkManager so it runs on the JVM. */
class DownloadRunner(
    private val repository: DownloadRepository,
    private val engines: EngineProvider,
    private val saver: FileSaver,
    private val workDirs: WorkDirFactory,
    private val freeSpace: FreeSpaceChecker,
) {
    enum class Outcome {
        DONE,
        FAILED,

        /** The row is gone or already finished (removed, cancelled or done); nothing was run. */
        NOT_RUNNABLE,
    }

    /**
     * Runs the job [id] to its end and records the result in the repository.
     *
     * [onProgress] gets every progress event (for `setProgress` and the notification).
     * On cancellation the coroutine is cancelled again after bookkeeping; [stoppedBySystem] tells
     * a user cancel (row becomes cancelled) from Android stopping the job, e.g. the dataSync time
     * limit (row becomes failed so Retry shows up).
     */
    suspend fun run(
        id: String,
        onProgress: (JobProgress) -> Unit = {},
        stoppedBySystem: () -> Boolean = { false },
    ): Outcome {
        val job = repository.get(id) ?: return Outcome.NOT_RUNNABLE
        if (!repository.markRunning(id)) return Outcome.NOT_RUNNABLE

        val workDir = workDirs.create(id)
        val saved = job.files.toMutableList()
        var remoteJobId = job.serverJobId
        var engine: io.github.timhofmann27.opentakez.engine.MediaEngine? = null
        try {
            val needed = StorageRequirement.requiredBytes(null)
            if (freeSpace.freeBytes(workDir) < needed) throw EngineError.StorageFull()

            val selectedEngine = engines.engineFor(job.engine)
            engine = selectedEngine
            var done: EngineEvent.Done? = null
            selectedEngine.download(job.url, job.option, workDir, remoteJobId).collect { event ->
                when (event) {
                    is EngineEvent.Progress -> onProgress(event.progress)
                    is EngineEvent.RemoteJobStarted -> {
                        remoteJobId = event.id
                        withContext(NonCancellable) { repository.setServerJobId(id, event.id) }
                    }
                    is EngineEvent.Done -> done = event
                }
            }
            val result = done ?: throw EngineError.Other(0, "The download finished without a result")
            if (result.files.isEmpty()) throw EngineError.Other(0, "No files were downloaded")
            if (saved.size > result.files.size) throw EngineError.Other(0, "Saved file count exceeds the download")

            for (file in result.files.drop(saved.size)) {
                saved += saver.save(file.file, file.displayName).uri
            }
            if (!repository.markDone(id, saved.toList(), result.skipped)) {
                remoteJobId?.let { remoteId -> runCatching { selectedEngine.releaseRemoteJob(remoteId) } }
                return Outcome.NOT_RUNNABLE
            }
            remoteJobId?.let { remoteId ->
                // The local copy is durable. A failed DELETE must not turn a completed job into a
                // failure; the server's own retention will eventually reclaim it.
                runCatching { selectedEngine.releaseRemoteJob(remoteId) }
            }
            return Outcome.DONE
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                if (stoppedBySystem()) {
                    repository.markFailed(
                        id,
                        ErrorCodes.STOPPED,
                        "Android stopped the download",
                        saved.toList(),
                    )
                } else {
                    repository.markCancelled(id)
                    remoteJobId?.let { remoteId ->
                        runCatching { engine?.releaseRemoteJob(remoteId) }
                    }
                }
            }
            throw e
        } catch (e: Exception) {
            // Only code and a one-line message are kept: never a stack trace, never the token.
            withContext(NonCancellable) {
                repository.markFailed(id, ErrorCodes.codeOf(e), ErrorCodes.messageOf(e), saved.toList())
            }
            return Outcome.FAILED
        } finally {
            withContext(NonCancellable) { workDir.deleteRecursively() }
        }
    }
}
