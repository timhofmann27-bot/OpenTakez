package io.github.timhofmann27.opentakez.ui.fakes

import io.github.timhofmann27.opentakez.core.model.DownloadOption
import io.github.timhofmann27.opentakez.core.model.Engine
import io.github.timhofmann27.opentakez.core.model.EngineError
import io.github.timhofmann27.opentakez.core.model.MediaInfo
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/*
 * In-memory implementations of the ports, for JVM tests, Compose previews, and a MainActivity that
 * runs without the real services. They are plain and scriptable on purpose.
 */

class FakeResolvePort(
    /** Called for every resolve; throw an [EngineError] to fail. */
    var handler: suspend (url: String, engine: Engine) -> MediaInfo = { url, _ -> SampleData.video(url) },
) : ResolvePort {
    val calls = mutableListOf<Pair<String, Engine>>()

    override suspend fun resolve(url: String, engine: Engine): MediaInfo {
        calls += url to engine
        return handler(url, engine)
    }
}

class FakeDownloadsPort : DownloadsPort {
    data class Started(val url: String, val info: MediaInfo, val option: DownloadOption, val engine: Engine)

    val started = mutableListOf<Started>()
    var startFailure: EngineError? = null
    private var counter = 0

    val activeState = MutableStateFlow<List<ActiveDownload>>(emptyList())
    val historyState = MutableStateFlow<List<HistoryEntry>>(emptyList())
    override val active get() = activeState
    override val history get() = historyState

    val cancelled = mutableListOf<String>()
    val retried = mutableListOf<String>()
    val removed = mutableListOf<String>()
    val repeated = mutableListOf<String>()

    override suspend fun start(url: String, info: MediaInfo, option: DownloadOption, engine: Engine): String {
        startFailure?.let { throw it }
        started += Started(url, info, option, engine)
        return "job-${++counter}"
    }

    override suspend fun cancel(id: String) {
        cancelled += id
        activeState.update { list -> list.filterNot { it.id == id } }
    }

    override suspend fun retry(id: String) {
        retried += id
        activeState.update { list ->
            list.map { if (it.id == id) it.copy(status = DownloadStatus.QUEUED, error = null) else it }
        }
    }

    override suspend fun remove(id: String) {
        removed += id
        activeState.update { list -> list.filterNot { it.id == id } }
    }

    override suspend fun removeHistory(id: String) {
        historyState.update { list -> list.filterNot { it.id == id } }
    }

    override suspend fun clearHistory() {
        historyState.value = emptyList()
    }

    override suspend fun downloadAgain(historyId: String): String {
        repeated += historyId
        return "job-${++counter}"
    }
}

class FakeSettingsPort(
    serverUrl: String = "",
    tokenSet: Boolean = serverUrl.isNotEmpty(),
    engine: Engine = Engine.DEVICE,
    language: String = "en",
    ytDlpVersion: String? = "2026.10.21",
    deviceEngine: DeviceEngineState = DeviceEngineState.Ready,
) : SettingsPort {
    val serverUrlState = MutableStateFlow(serverUrl)
    val tokenSetState = MutableStateFlow(tokenSet)
    val engineState = MutableStateFlow(engine)
    val languageState = MutableStateFlow(language)
    val ytDlpState = MutableStateFlow(ytDlpVersion)
    val deviceEngineState = MutableStateFlow(deviceEngine)

    override val serverUrl get() = serverUrlState
    override val tokenSet get() = tokenSetState
    override val defaultEngine get() = engineState
    override val language get() = languageState
    override val ytDlpVersion get() = ytDlpState
    override val deviceEngine get() = deviceEngineState

    var connectionResult: ConnectionResult = ConnectionResult.Connected("1.0.0", "2026.10.21", listOf("youtube"))
    var updateResult: UpdateResult = UpdateResult.AlreadyLatest

    /** The last values handed to [saveServer] and [testConnection], for assertions. */
    var lastSavedToken: String? = "unset"
    var lastTestedToken: String? = "unset"
    var lastTestedUrl: String? = null

    override suspend fun saveServer(serverUrl: String, token: String?) {
        lastSavedToken = token
        serverUrlState.value = serverUrl
        when {
            serverUrl.isEmpty() -> tokenSetState.value = false
            token == null -> Unit
            else -> tokenSetState.value = token.isNotEmpty()
        }
    }

    override suspend fun setDefaultEngine(engine: Engine) {
        engineState.value = engine
    }

    override suspend fun setLanguage(tag: String) {
        languageState.value = tag
    }

    override suspend fun testConnection(serverUrl: String, token: String?): ConnectionResult {
        lastTestedUrl = serverUrl
        lastTestedToken = token
        return connectionResult
    }

    override suspend fun updateYtDlp(): UpdateResult {
        val result = updateResult
        if (result is UpdateResult.Updated) ytDlpState.value = result.version
        return result
    }
}

/** Mirrors the contract: photo links go to the server when one is configured. */
class FakeRoutingPort : RoutingPort {
    override fun defaultEngine(url: String, serverConfigured: Boolean): Engine {
        val photo = Regex("""tiktok\.com/@[^/]+/photo/\d+|instagram\.com/(?:[^/]+/)?p/[^/]+/?$""").containsMatchIn(url)
        return if (photo && serverConfigured) Engine.SERVER else Engine.DEVICE
    }
}

class FakeServices(
    override val resolve: FakeResolvePort = FakeResolvePort(),
    override val downloads: FakeDownloadsPort = FakeDownloadsPort(),
    override val settings: FakeSettingsPort = FakeSettingsPort(),
    override val routing: RoutingPort = FakeRoutingPort(),
) : AppServices
