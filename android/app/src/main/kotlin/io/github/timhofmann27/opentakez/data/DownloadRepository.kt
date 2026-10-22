package io.github.timhofmann27.opentakez.data

import io.github.timhofmann27.opentakez.core.model.DownloadOption
import io.github.timhofmann27.opentakez.core.model.Engine
import io.github.timhofmann27.opentakez.core.model.MediaInfo
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/** The job table and its state transitions: queued -> running -> done | failed | cancelled. */
class DownloadRepository(
    private val dao: DownloadDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    /** Waiting, running, failed and cancelled jobs, newest first. */
    val active: Flow<List<DownloadEntity>> = dao.observeActive()

    /** Finished jobs, newest first. */
    val done: Flow<List<DownloadEntity>> = dao.observeDone()

    fun observe(id: String): Flow<DownloadEntity?> = dao.observeById(id)

    suspend fun get(id: String): DownloadEntity? = dao.getById(id)

    suspend fun create(info: MediaInfo, option: DownloadOption, engine: Engine): DownloadEntity {
        val entity = DownloadEntity(
            id = newId(),
            url = info.url,
            platform = info.platform,
            kind = info.kind,
            title = info.title,
            uploader = info.uploader,
            thumbnail = info.thumbnail,
            option = option,
            engine = engine,
            serverJobId = null,
            status = DownloadStatus.QUEUED,
            files = emptyList(),
            skipped = 0,
            errorCode = null,
            errorMessage = null,
            createdAt = clock(),
            finishedAt = null,
        )
        dao.insert(entity)
        return entity
    }

    /** True when the row exists and may run (queued, or running again after a process restart). */
    suspend fun markRunning(id: String): Boolean = dao.markRunning(id) > 0

    suspend fun markDone(id: String, files: List<String>, skipped: Int): Boolean =
        dao.markDone(id, files, skipped, clock()) > 0

    /** [files] are URIs that were already saved before the failure, so they are not orphaned. */
    suspend fun markFailed(
        id: String,
        code: String,
        message: String?,
        files: List<String> = emptyList(),
    ): Boolean = dao.markFailed(id, code, message, files, clock()) > 0

    suspend fun markCancelled(id: String): Boolean = dao.markCancelled(id, clock()) > 0

    /** Failed or cancelled -> queued again (Retry). The caller enqueues the work. */
    suspend fun requeue(id: String): Boolean = dao.requeue(id) > 0

    suspend fun setServerJobId(id: String, serverJobId: String?) = dao.setServerJobId(id, serverJobId)

    suspend fun remove(id: String) = dao.delete(id)

    suspend fun clearDone() = dao.clearDone()
}
