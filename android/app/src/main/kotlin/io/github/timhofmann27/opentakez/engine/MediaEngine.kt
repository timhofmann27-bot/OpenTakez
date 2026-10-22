package io.github.timhofmann27.opentakez.engine

import io.github.timhofmann27.opentakez.core.model.DownloadOption
import io.github.timhofmann27.opentakez.core.model.DownloadedFile
import io.github.timhofmann27.opentakez.core.model.JobProgress
import io.github.timhofmann27.opentakez.core.model.MediaInfo
import java.io.File
import kotlinx.coroutines.flow.Flow

sealed interface EngineEvent {
    data class Progress(val progress: JobProgress) : EngineEvent

    /** The server job is durable; persist its ID before following progress or fetching files. */
    data class RemoteJobStarted(val id: String) : EngineEvent

    /** Emitted once, last. [skipped] counts playlist/carousel items that could not be fetched. */
    data class Done(val files: List<DownloadedFile>, val skipped: Int = 0) : EngineEvent
}

/**
 * One way of downloading: on the device (yt-dlp) or through the user's server. Both deliver plain
 * files, so saving to shared storage happens once, in [io.github.timhofmann27.opentakez.storage.FileSaver].
 *
 * Failures are thrown as [io.github.timhofmann27.opentakez.core.model.EngineError]. Cancelling
 * the collecting coroutine cancels the download and removes what it started.
 */
interface MediaEngine {
    suspend fun resolve(url: String): MediaInfo

    /**
     * Downloads into [workDir] (owned by the caller, deleted afterwards). A server engine resumes
     * [existingJobId] after process death; a device engine ignores it.
     */
    fun download(
        url: String,
        option: DownloadOption,
        workDir: File,
        existingJobId: String? = null,
    ): Flow<EngineEvent>

    /** Release server files only after the caller has saved every file to shared storage. */
    suspend fun releaseRemoteJob(id: String) = Unit
}
