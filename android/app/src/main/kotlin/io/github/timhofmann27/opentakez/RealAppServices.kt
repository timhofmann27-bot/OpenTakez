package io.github.timhofmann27.opentakez

import io.github.timhofmann27.opentakez.core.RoutingPolicy
import io.github.timhofmann27.opentakez.core.model.DownloadOption
import io.github.timhofmann27.opentakez.core.model.Engine
import io.github.timhofmann27.opentakez.core.model.EngineError
import io.github.timhofmann27.opentakez.core.model.MediaInfo
import io.github.timhofmann27.opentakez.core.model.ServerConfig
import io.github.timhofmann27.opentakez.data.DownloadEntity
import io.github.timhofmann27.opentakez.data.DownloadStatus as StoredStatus
import io.github.timhofmann27.opentakez.data.ErrorCodes
import io.github.timhofmann27.opentakez.engine.device.DeviceEngine
import io.github.timhofmann27.opentakez.engine.device.DeviceEngineState as EngineState
import io.github.timhofmann27.opentakez.engine.device.UpdateResult as EngineUpdateResult
import io.github.timhofmann27.opentakez.engine.server.ConnectionResult as ServerConnectionResult
import io.github.timhofmann27.opentakez.engine.server.ServerConnectionTester
import io.github.timhofmann27.opentakez.engine.server.ServerUrl
import io.github.timhofmann27.opentakez.ui.ports.ActiveDownload
import io.github.timhofmann27.opentakez.ui.ports.AppServices
import io.github.timhofmann27.opentakez.ui.ports.ConnectionResult
import io.github.timhofmann27.opentakez.ui.ports.DeviceEngineState
import io.github.timhofmann27.opentakez.ui.ports.DownloadStatus
import io.github.timhofmann27.opentakez.ui.ports.DownloadsPort
import io.github.timhofmann27.opentakez.ui.ports.HistoryEntry
import io.github.timhofmann27.opentakez.ui.ports.ResolvePort
import io.github.timhofmann27.opentakez.ui.ports.RoutingPort
import io.github.timhofmann27.opentakez.ui.ports.SettingsPort
import io.github.timhofmann27.opentakez.ui.ports.UpdateResult
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** Connects the Compose UI to the real engines, Room, DataStore and WorkManager. */
class RealAppServices(
    private val container: AppContainer,
    private val deviceEngine: DeviceEngine,
    private val connectionTester: ServerConnectionTester = ServerConnectionTester(),
) : AppServices {
    override val resolve: ResolvePort = object : ResolvePort {
        override suspend fun resolve(url: String, engine: Engine): MediaInfo =
            container.engines.engineFor(engine).resolve(url)
    }

    override val routing: RoutingPort = object : RoutingPort {
        override fun defaultEngine(url: String, serverConfigured: Boolean): Engine =
            RoutingPolicy.defaultEngine(url, serverConfigured)
    }

    override val downloads: DownloadsPort = object : DownloadsPort {
        @OptIn(ExperimentalCoroutinesApi::class)
        override val active: Flow<List<ActiveDownload>> = container.repository.active.flatMapLatest { rows ->
            val visible = rows.filter { it.status != StoredStatus.CANCELLED }
            if (visible.isEmpty()) flowOf(emptyList()) else combine(visible.map { row ->
                container.scheduler.progress(row.id).map { progress -> row.toActive(progress) }
            }) { it.toList() }
        }

        override val history: Flow<List<HistoryEntry>> = container.repository.done.map { rows ->
            rows.map { it.toHistory() }
        }

        override suspend fun start(
            url: String,
            info: MediaInfo,
            option: DownloadOption,
            engine: Engine,
        ): String = container.startDownload(info.copy(url = url), option, engine).id

        override suspend fun cancel(id: String) = container.cancelDownload(id)

        override suspend fun retry(id: String) {
            if (!container.retryDownload(id)) throw EngineError.Conflict()
        }

        override suspend fun remove(id: String) = container.repository.remove(id)

        override suspend fun removeHistory(id: String) = container.repository.remove(id)

        override suspend fun clearHistory() = container.repository.clearDone()

        override suspend fun downloadAgain(historyId: String): String {
            val row = container.repository.get(historyId)?.takeIf { it.status == StoredStatus.DONE }
                ?: throw EngineError.Lost()
            val info = MediaInfo(
                url = row.url,
                platform = row.platform,
                kind = row.kind,
                title = row.title,
                uploader = row.uploader,
                thumbnail = row.thumbnail,
                options = listOf(row.option),
            )
            return container.startDownload(info, row.option, row.engine).id
        }
    }

    override val settings: SettingsPort = object : SettingsPort {
        override val serverUrl: Flow<String> = container.settings.serverUrl
        override val tokenSet: Flow<Boolean> = container.settings.token.map { it.isNotEmpty() }
        override val defaultEngine: Flow<Engine> = container.settings.defaultEngine
        override val language: Flow<String> = container.settings.language
        override val ytDlpVersion: Flow<String?> = this@RealAppServices.deviceEngine.state.map {
            (it as? EngineState.Ready)?.ytDlpVersion
        }
        override val deviceEngine: Flow<DeviceEngineState> = this@RealAppServices.deviceEngine.state.map { state ->
            when (state) {
                EngineState.Initializing -> DeviceEngineState.Initializing
                is EngineState.Ready -> DeviceEngineState.Ready
                is EngineState.Failed -> DeviceEngineState.Failed(state.message)
            }
        }

        override suspend fun saveServer(serverUrl: String, token: String?) {
            val normalized = if (serverUrl.isBlank()) "" else ServerUrl.normalize(serverUrl)
                ?: throw EngineError.Other(0, "Enter an HTTPS server URL")
            container.settings.setServerUrl(normalized)
            if (normalized.isEmpty()) container.settings.setToken("")
            else if (token != null) container.settings.setToken(token)
        }

        override suspend fun setDefaultEngine(engine: Engine) = container.settings.setDefaultEngine(engine)

        override suspend fun setLanguage(tag: String) = container.settings.setLanguage(tag)

        override suspend fun testConnection(serverUrl: String, token: String?): ConnectionResult {
            val normalized = ServerUrl.normalize(serverUrl) ?: return ConnectionResult.Unreachable
            val value = token ?: container.settings.token.first()
            if (value.isEmpty()) return ConnectionResult.TokenRejected
            return try {
                when (val result = connectionTester.test(ServerConfig(normalized, value))) {
                    is ServerConnectionResult.Connected -> ConnectionResult.Connected(result.version)
                    ServerConnectionResult.TokenRejected -> ConnectionResult.TokenRejected
                    is ServerConnectionResult.TooOld -> ConnectionResult.TooOld
                    ServerConnectionResult.NotOpenTakez -> ConnectionResult.NotOpenTakez
                    ServerConnectionResult.Unreachable -> ConnectionResult.Unreachable
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ConnectionResult.Unreachable
            }
        }

        override suspend fun updateYtDlp(): UpdateResult = when (
            val result = this@RealAppServices.deviceEngine.updateYtDlp()
        ) {
            is EngineUpdateResult.Updated -> UpdateResult.Updated(result.version.orEmpty())
            is EngineUpdateResult.AlreadyLatest -> UpdateResult.AlreadyLatest
            is EngineUpdateResult.Failed -> UpdateResult.Failed(result.message)
        }
    }
}

private fun DownloadEntity.toActive(progress: io.github.timhofmann27.opentakez.core.model.JobProgress?) =
    ActiveDownload(
        id = id,
        url = url,
        title = title,
        uploader = uploader,
        thumbnail = thumbnail,
        platform = platform,
        kind = kind,
        option = option,
        engine = engine,
        status = when (status) {
            StoredStatus.QUEUED -> DownloadStatus.QUEUED
            StoredStatus.RUNNING -> DownloadStatus.RUNNING
            else -> DownloadStatus.FAILED
        },
        progress = if (status == StoredStatus.RUNNING) progress else null,
        error = if (status == StoredStatus.FAILED) storedError() else null,
    )

private fun DownloadEntity.toHistory() = HistoryEntry(
    id = id,
    url = url,
    title = title,
    uploader = uploader,
    thumbnail = thumbnail,
    platform = platform,
    kind = kind,
    option = option,
    engine = engine,
    files = files,
    skipped = skipped,
    finishedAtMillis = finishedAt ?: createdAt,
)

/** Keep the machine-readable failure after process death; never expose the saved token. */
private fun DownloadEntity.storedError(): EngineError = when (errorCode) {
    ErrorCodes.NETWORK -> EngineError.Network(errorMessage)
    ErrorCodes.UNAUTHORIZED -> EngineError.Unauthorized()
    ErrorCodes.LOST -> EngineError.Lost()
    ErrorCodes.CONFLICT -> EngineError.Conflict()
    ErrorCodes.NO_LINK -> EngineError.NoLink()
    ErrorCodes.UNSUPPORTED -> EngineError.Unsupported()
    ErrorCodes.LOGIN_REQUIRED -> EngineError.LoginRequired(platform)
    ErrorCodes.RATE_LIMITED -> EngineError.RateLimited(null)
    ErrorCodes.BUSY -> EngineError.Busy()
    ErrorCodes.NEEDS_SERVER -> EngineError.NeedsServer()
    ErrorCodes.YOUTUBE_BLOCKED -> EngineError.YoutubeBlocked()
    ErrorCodes.STORAGE_FULL -> EngineError.StorageFull()
    ErrorCodes.PLATFORM -> EngineError.Platform(errorMessage.orEmpty())
    else -> EngineError.Other(0, errorMessage)
}
