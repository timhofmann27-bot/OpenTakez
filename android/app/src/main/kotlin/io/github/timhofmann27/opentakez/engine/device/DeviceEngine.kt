package io.github.timhofmann27.opentakez.engine.device

import io.github.timhofmann27.opentakez.core.Platforms
import io.github.timhofmann27.opentakez.core.RoutingPolicy
import io.github.timhofmann27.opentakez.core.model.DownloadOption
import io.github.timhofmann27.opentakez.core.model.DownloadedFile
import io.github.timhofmann27.opentakez.core.model.EngineError
import io.github.timhofmann27.opentakez.core.model.MediaInfo
import io.github.timhofmann27.opentakez.engine.EngineEvent
import io.github.timhofmann27.opentakez.engine.MediaEngine
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Downloads on the phone with yt-dlp. Ports the rules of backend/app/providers/ytdlp.py. */
class DeviceEngine(
    private val runner: YtDlpRunner,
    private val store: YtDlpUpdateStore,
    private val gate: YtDlpGate = YtDlpGate(),
    private val clock: () -> Long = System::currentTimeMillis,
) : MediaEngine {
    private val _state = MutableStateFlow<DeviceEngineState>(DeviceEngineState.Initializing)
    val state: StateFlow<DeviceEngineState> = _state.asStateFlow()

    private val initLock = Mutex()
    private val updateCheckLock = Mutex()

    /** Idempotent. A failed start is retried by the next call. */
    suspend fun initialize() {
        initLock.withLock {
            if (_state.value is DeviceEngineState.Ready) return
            _state.value = DeviceEngineState.Initializing
            _state.value = try {
                runner.init()
                DeviceEngineState.Ready(safeVersion())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DeviceEngineState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    suspend fun ytDlpVersion(): String? = safeVersion()

    /** Never overlaps with a download or a resolve (see [YtDlpGate]). */
    suspend fun updateYtDlp(): UpdateResult {
        initialize()
        (_state.value as? DeviceEngineState.Failed)?.let { return UpdateResult.Failed(it.message) }
        return gate.exclusive {
            try {
                val outcome = runner.update()
                store.setLastUpdateMillis(clock())
                val version = safeVersion()
                _state.value = DeviceEngineState.Ready(version)
                when (outcome) {
                    UpdateOutcome.UPDATED -> UpdateResult.Updated(version)
                    UpdateOutcome.ALREADY_LATEST -> UpdateResult.AlreadyLatest(version)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UpdateResult.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    override suspend fun resolve(url: String): MediaInfo {
        val platform = checkSupported(url)
        ensureReady()
        ensureUpdated()
        val result = gate.shared { runner.runMapped(YtDlpArgs.resolve(url), UUID.randomUUID().toString(), platform) }
        return ParsedInfo.parse(result.stdout, platform).toMediaInfo(url)
    }

    override fun download(
        url: String,
        option: DownloadOption,
        workDir: File,
        existingJobId: String?,
    ): Flow<EngineEvent> =
        channelFlow {
            val platform = checkSupported(url)
            if (option == DownloadOption.IMAGE_ORIGINAL) {
                throw EngineError.Platform("This download option isn't available for this link.")
            }
            ensureReady()
            ensureUpdated()

            val processId = UUID.randomUUID().toString()
            val before = workDir.listFiles().orEmpty().map { it.name }.toSet()
            // Cancelling must stop the process right away; execute() may be blocked in native code.
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    if (!this@channelFlow.isActive) runner.cancel(processId)
                }
            }
            try {
                gate.shared {
                    // Like the server: a channel must not turn into 50 downloads.
                    val flat = runner.runMapped(YtDlpArgs.resolve(url), processId, platform)
                    val info = ParsedInfo.parse(flat.stdout, platform).toMediaInfo(url)
                    if (option !in info.options) {
                        throw EngineError.Platform("This download option isn't available for this link.")
                    }

                    val parser = ProgressParser()
                    val result = runner.execute(
                        YtDlpArgs.download(url, option, workDir),
                        processId,
                        redirectErrorStream = true,
                    ) { line -> parser.parse(line)?.let { trySend(EngineEvent.Progress(it)) } }
                    val files = finalFiles(workDir, before, option)
                    if (files.isEmpty()) {
                        if (result.exitCode != 0 || result.stderr.isNotBlank()) {
                            throw DeviceErrorMapper.map(result.stderr.ifBlank { result.stdout }, platform)
                        }
                        throw EngineError.Platform("No file was downloaded.")
                    }
                    // With --ignore-errors every failed item leaves one ERROR line in stderr.
                    val skipped = (result.stderr + "\n" + result.stdout)
                        .lineSequence().count { it.trim().startsWith("ERROR:") }
                    send(EngineEvent.Done(files.map { DownloadedFile(it, it.name) }, skipped))
                }
            } catch (e: Throwable) {
                // Partial files must not be saved or left in the cache if the job ends early.
                withContext(NonCancellable) { removeNewFiles(workDir, before) }
                throw if (e is CancellationException || e is EngineError) e else e.toEngineError()
            } finally {
                watcher.cancel()
            }
        }

    private fun checkSupported(url: String): String {
        val platform = Platforms.platformOf(url) ?: throw EngineError.Unsupported()
        if (RoutingPolicy.isPhotoLink(url)) throw EngineError.NeedsServer()
        return platform
    }

    private suspend fun ensureReady() {
        initialize()
        (_state.value as? DeviceEngineState.Failed)?.let {
            throw EngineError.Other(0, "yt-dlp could not start: ${it.message}")
        }
    }

    /**
     * A fresh install has a bundled yt-dlp that is too old for YouTube, so it must update first.
     * After that a weekly refresh is best effort: a failed one never fails the resolve.
     */
    private suspend fun ensureUpdated() = updateCheckLock.withLock {
        val last = store.lastUpdateMillis()
        if (last == null || clock() - last > UPDATE_INTERVAL_MILLIS) {
            val result = updateYtDlp()
            if (last == null && result is UpdateResult.Failed) {
                throw EngineError.Network("yt-dlp update failed: ${result.message}")
            }
        }
    }

    private suspend fun YtDlpRunner.runMapped(args: List<String>, processId: String, platform: String): RunResult {
        val result = try {
            execute(args, processId, redirectErrorStream = false) { }
        } catch (e: CancellationException) {
            throw e
        } catch (e: EngineError) {
            throw e
        } catch (e: Exception) {
            throw e.toEngineError()
        }
        if (result.exitCode != 0) throw DeviceErrorMapper.map(result.stderr, platform)
        return result
    }

    private fun Throwable.toEngineError(): EngineError =
        if ("no space left" in message.orEmpty().lowercase()) {
            EngineError.StorageFull()
        } else {
            EngineError.Other(0, message ?: javaClass.simpleName)
        }

    private suspend fun safeVersion(): String? = try {
        runner.version()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun finalFiles(workDir: File, before: Set<String>, option: DownloadOption): List<File> =
        workDir.listFiles().orEmpty()
            .filter { it.isFile && it.name !in before && !isLeftover(it, option) }
            .sortedBy { it.name }

    private fun removeNewFiles(workDir: File, before: Set<String>) {
        workDir.listFiles().orEmpty().filter { it.isFile && it.name !in before }.forEach { it.delete() }
    }

    // Leftovers yt-dlp may keep next to the final file.
    private fun isLeftover(file: File, option: DownloadOption): Boolean {
        val suffix = file.extension.lowercase()
        if (suffix in TEMP_SUFFIXES) return true
        // Audio downloads fetch the thumbnail only to embed it; elsewhere an image is a result.
        return option.isAudio && suffix in COVER_SUFFIXES
    }

    private companion object {
        const val UPDATE_INTERVAL_MILLIS = 7L * 24 * 60 * 60 * 1000
        val TEMP_SUFFIXES = setOf("part", "ytdl", "temp")
        val COVER_SUFFIXES = setOf("jpg", "webp", "png")
    }
}
