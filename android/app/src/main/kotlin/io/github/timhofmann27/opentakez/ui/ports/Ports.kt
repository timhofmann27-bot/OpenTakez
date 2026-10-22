package io.github.timhofmann27.opentakez.ui.ports

import io.github.timhofmann27.opentakez.core.model.DownloadOption
import io.github.timhofmann27.opentakez.core.model.Engine
import io.github.timhofmann27.opentakez.core.model.EngineError
import io.github.timhofmann27.opentakez.core.model.JobProgress
import io.github.timhofmann27.opentakez.core.model.MediaInfo
import io.github.timhofmann27.opentakez.core.model.MediaKind
import kotlinx.coroutines.flow.Flow

/*
 * The UI talks to the rest of the app only through these ports. The integrator implements them with
 * the real engines, WorkManager, Room and DataStore; the UI tests implement them with fakes.
 */

interface ResolvePort {
    /** Throws [EngineError]. Cancelling the calling coroutine cancels the lookup. */
    suspend fun resolve(url: String, engine: Engine): MediaInfo
}

/** Where a running or failed download stands. Finished downloads move to history. */
enum class DownloadStatus { QUEUED, RUNNING, FAILED }

data class ActiveDownload(
    val id: String,
    val url: String,
    val title: String,
    val uploader: String?,
    val thumbnail: String?,
    val platform: String,
    val kind: MediaKind,
    val option: DownloadOption,
    val engine: Engine,
    val status: DownloadStatus,
    /** Live progress; null while queued or when unknown. */
    val progress: JobProgress? = null,
    /** Set when [status] is [DownloadStatus.FAILED]. */
    val error: EngineError? = null,
)

data class HistoryEntry(
    val id: String,
    val url: String,
    val title: String,
    val uploader: String?,
    val thumbnail: String?,
    val platform: String,
    val kind: MediaKind,
    val option: DownloadOption,
    val engine: Engine,
    /** Content URIs (or file URIs on API 26-28) of the saved files, in order. */
    val files: List<String>,
    /** Playlist or carousel items that could not be fetched. */
    val skipped: Int = 0,
    val finishedAtMillis: Long,
)

interface DownloadsPort {
    /** Queues a download and returns its id. Throws [EngineError] if it cannot be queued. */
    suspend fun start(url: String, info: MediaInfo, option: DownloadOption, engine: Engine): String

    /** Queued, running, and failed downloads, newest first, with live progress. */
    val active: Flow<List<ActiveDownload>>

    /** Finished downloads, newest first. */
    val history: Flow<List<HistoryEntry>>

    /** Stops a queued or running download and drops it from [active]. */
    suspend fun cancel(id: String)

    /** Runs a failed download again, keeping its id. */
    suspend fun retry(id: String)

    /** Removes a failed download from [active]. */
    suspend fun remove(id: String)

    suspend fun removeHistory(id: String)

    suspend fun clearHistory()

    /** Queues the same url, option, and engine as a history entry; returns the new download id. */
    suspend fun downloadAgain(historyId: String): String
}

sealed interface ConnectionResult {
    data class Connected(
        val version: String,
        val ytDlpVersion: String? = null,
        val platforms: List<String> = emptyList(),
    ) : ConnectionResult

    data object Unreachable : ConnectionResult
    data object TokenRejected : ConnectionResult
    data object TooOld : ConnectionResult
    data object NotOpenTakez : ConnectionResult
}

sealed interface UpdateResult {
    data class Updated(val version: String) : UpdateResult
    data object AlreadyLatest : UpdateResult
    data class Failed(val message: String?) : UpdateResult
}

sealed interface DeviceEngineState {
    data object Initializing : DeviceEngineState
    data object Ready : DeviceEngineState
    data class Failed(val message: String?) : DeviceEngineState
}

/**
 * The token is write-only for the UI: it can only learn that one is set. [token] = null keeps the
 * stored token, "" removes it. An empty [serverUrl] means no server (and clears the token).
 */
interface SettingsPort {
    val serverUrl: Flow<String>
    val tokenSet: Flow<Boolean>
    val defaultEngine: Flow<Engine>

    /** "" (follow the system), "en", or "de". The UI applies it with AppCompatDelegate. */
    val language: Flow<String>
    val ytDlpVersion: Flow<String?>
    val deviceEngine: Flow<DeviceEngineState>

    suspend fun saveServer(serverUrl: String, token: String?)
    suspend fun setDefaultEngine(engine: Engine)
    suspend fun setLanguage(tag: String)

    /** Tests the typed values; [token] = null tests with the stored token. Never throws. */
    suspend fun testConnection(serverUrl: String, token: String?): ConnectionResult

    /** Never throws. */
    suspend fun updateYtDlp(): UpdateResult
}

interface RoutingPort {
    /** Photo links with a server configured go to the server; everything else to the device. */
    fun defaultEngine(url: String, serverConfigured: Boolean): Engine
}

interface AppServices {
    val resolve: ResolvePort
    val downloads: DownloadsPort
    val settings: SettingsPort
    val routing: RoutingPort
}

/** Implemented by the `Application`; [io.github.timhofmann27.opentakez.MainActivity] reads it. */
interface AppServicesProvider {
    val services: AppServices
}
