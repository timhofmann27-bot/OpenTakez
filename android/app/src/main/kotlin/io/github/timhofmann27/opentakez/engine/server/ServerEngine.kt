package io.github.timhofmann27.opentakez.engine.server

import io.github.timhofmann27.opentakez.core.model.DownloadOption
import io.github.timhofmann27.opentakez.core.model.DownloadedFile
import io.github.timhofmann27.opentakez.core.model.EngineError
import io.github.timhofmann27.opentakez.core.model.JobProgress
import io.github.timhofmann27.opentakez.core.model.MediaInfo
import io.github.timhofmann27.opentakez.core.model.Phase
import io.github.timhofmann27.opentakez.core.model.ServerConfig
import io.github.timhofmann27.opentakez.engine.EngineEvent
import io.github.timhofmann27.opentakez.engine.MediaEngine
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.OkHttpClient
import okio.buffer
import okio.sink

/** Waits before reconnecting the event stream, as in web/src/lib/jobs.ts. */
private val BACKOFF_MS = longArrayOf(1_000, 2_000, 5_000, 10_000, 30_000)
private const val POLL_MS = 2_000L
private const val STREAM_FAILURES_BEFORE_POLLING = 2

/** Quiet queues send nothing for a while; a longer silence than this means a dead connection. */
private const val STREAM_READ_TIMEOUT_SECONDS = 90L
private const val DELETE_TIMEOUT_SECONDS = 10L
private const val MAX_NAME_LENGTH = 200

/** Downloads through the user's own OpenTakez server (docs/design/m6-contract.md, section 5). */
class ServerEngine(
    config: ServerConfig,
    client: OkHttpClient = OkHttpClient(),
    allowCleartextForTests: Boolean = false,
) : MediaEngine {
    private val http = ServerHttp(config, client, allowCleartextForTests)
    private val streamClient = client.newBuilder()
        .readTimeout(STREAM_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    private val deleteClient = client.newBuilder()
        .callTimeout(DELETE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    override suspend fun resolve(url: String): MediaInfo = http.fetch(
        http.request("POST", "api", "resolve", json = ServerJson.encodeToString(ResolveRequest(url))),
        MediaInfo.serializer(),
    )

    override fun download(
        url: String,
        option: DownloadOption,
        workDir: File,
        existingJobId: String?,
    ): Flow<EngineEvent> = flow {
        val job = if (existingJobId == null) {
            http.fetch(
                http.request(
                    "POST", "api", "jobs",
                    json = ServerJson.encodeToString(JobRequest(url, option.wire)),
                ),
                JobDto.serializer(),
            )
        } else {
            getJob(existingJobId)
        }
        emit(EngineEvent.RemoteJobStarted(job.id))
        val finished = follow(job) { emit(EngineEvent.Progress(it.progress.toJobProgress())) }
        if (finished.status == "error") throw finished.toError()
        val files = fetchFiles(finished, workDir)
        emit(EngineEvent.Done(files, finished.skipped))
    }

    /** Called by the worker only after all files have reached shared storage. */
    override suspend fun releaseRemoteJob(id: String) = deleteQuietly(id)

    /** Follows the job until it is done or failed; reconnects and falls back to polling. */
    private suspend fun follow(first: JobDto, onUpdate: suspend (JobDto) -> Unit): JobDto {
        var job = first
        onUpdate(job)
        var streamFailures = 0
        var attempt = 0
        while (!job.isFinished) {
            if (streamFailures >= STREAM_FAILURES_BEFORE_POLLING) {
                delay(POLL_MS)
                try {
                    job = getJob(job.id)
                    onUpdate(job)
                } catch (e: EngineError) {
                    pollFailure(e)?.let { delay(it) }
                }
                continue
            }

            var sawEvent = false
            var minWaitMs = 0L
            try {
                streamEvents(job.id) { event ->
                    sawEvent = true
                    job = event
                    onUpdate(event)
                    event.isFinished
                }
            } catch (e: EngineError) {
                if (e is EngineError.Unauthorized || e is EngineError.Lost) throw e
                minWaitMs = retryAfterMs(e)
            }
            if (job.isFinished) break
            if (sawEvent) {
                streamFailures = 0
                attempt = 0
            }
            streamFailures++

            // The stream dropped: ask the server what became of the job before reconnecting.
            try {
                job = getJob(job.id)
                onUpdate(job)
            } catch (e: EngineError) {
                if (e is EngineError.Lost || e is EngineError.Unauthorized) throw e
                minWaitMs = maxOf(minWaitMs, retryAfterMs(e))
            }
            if (job.isFinished) continue
            if (streamFailures >= STREAM_FAILURES_BEFORE_POLLING) {
                if (minWaitMs > POLL_MS) delay(minWaitMs - POLL_MS)
                continue
            }
            delay(maxOf(BACKOFF_MS[minOf(attempt, BACKOFF_MS.lastIndex)], minWaitMs))
            attempt++
        }
        return job
    }

    /** How long to wait after a failed poll; a gone job or a refused token ends the download. */
    private fun pollFailure(e: EngineError): Long? {
        if (e is EngineError.Lost || e is EngineError.Unauthorized) throw e
        return retryAfterMs(e).takeIf { it > 0 }
    }

    private fun retryAfterMs(e: EngineError): Long =
        ((e as? EngineError.RateLimited)?.retryAfterSeconds ?: 0) * 1000L

    private suspend fun getJob(id: String): JobDto =
        http.fetch(http.request("GET", "api", "jobs", id), JobDto.serializer())

    /** Calls [onEvent] for each event until it returns true or the server ends the stream. */
    private suspend fun streamEvents(id: String, onEvent: suspend (JobDto) -> Boolean) {
        val request = http.request("GET", "api", "jobs", id, "events", accept = "text/event-stream")
        val exchange = http.open(request, streamClient)
        exchange.use {
            while (true) {
                val data = it.read { body -> body.source().nextSseData() } ?: return
                val event = try {
                    ServerJson.decodeFromString(JobDto.serializer(), data)
                } catch (_: kotlinx.serialization.SerializationException) {
                    continue
                }
                if (onEvent(event)) return
            }
        }
    }

    private suspend fun fetchFiles(job: JobDto, workDir: File): List<DownloadedFile> {
        workDir.mkdirs()
        val root = workDir.canonicalFile
        val taken = mutableSetOf<String>()
        return job.files.mapIndexed { index, serverName ->
            val target = File(root, uniqueName(safeFileName(serverName), taken))
            check(target.canonicalFile.parentFile == root) { "File name escapes the work directory" }
            http.open(http.request("GET", "api", "jobs", job.id, "files", index.toString())).use { exchange ->
                exchange.read { body -> target.sink().buffer().use { it.writeAll(body.source()) } }
            }
            DownloadedFile(target, target.name)
        }
    }

    private suspend fun deleteQuietly(id: String) {
        try {
            http.open(http.request("DELETE", "api", "jobs", id), deleteClient).close()
        } catch (_: EngineError) {
            // Gone already, still running (409), or unreachable: nothing more to do.
        }
    }

    private fun JobDto.toError(): EngineError =
        if (errorCode == "login_required") {
            EngineError.LoginRequired(platform.ifEmpty { null })
        } else {
            EngineError.Platform(error ?: "")
        }

    private fun ProgressDto?.toJobProgress(): JobProgress =
        this?.toProgress() ?: JobProgress(Phase.DOWNLOADING)
}

/** The next event's data, or null when the stream ends. Comments and other fields are ignored. */
internal fun okio.BufferedSource.nextSseData(): String? {
    val data = StringBuilder()
    var hasData = false
    while (true) {
        val line = readUtf8Line() ?: return null
        when {
            line.isEmpty() -> if (hasData) return data.toString()
            line.startsWith("data:") -> {
                if (hasData) data.append('\n')
                data.append(line.removePrefix("data:").removePrefix(" "))
                hasData = true
            }
        }
    }
}

/** The server's file name without any directory part, safe to create inside the work directory. */
internal fun safeFileName(raw: String): String {
    val name = raw.substringAfterLast('/').substringAfterLast('\\')
        .filterNot { it.isISOControl() }
        .trim()
    if (name.isEmpty() || name.all { it == '.' }) return "file"
    if (name.length <= MAX_NAME_LENGTH) return name
    val extension = name.substringAfterLast('.', "").take(10).let { if (it.isEmpty()) "" else ".$it" }
    return name.take(MAX_NAME_LENGTH - extension.length) + extension
}

private fun uniqueName(name: String, taken: MutableSet<String>): String {
    val stem = name.substringBeforeLast('.', name)
    val extension = name.removePrefix(stem)
    var candidate = name
    var n = 1
    while (!taken.add(candidate.lowercase())) candidate = "$stem (${++n})$extension"
    return candidate
}
