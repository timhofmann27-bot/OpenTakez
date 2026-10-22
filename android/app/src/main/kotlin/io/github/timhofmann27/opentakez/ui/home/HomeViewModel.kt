package io.github.timhofmann27.opentakez.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.timhofmann27.opentakez.core.model.DownloadOption
import io.github.timhofmann27.opentakez.core.model.Engine
import io.github.timhofmann27.opentakez.core.model.EngineError
import io.github.timhofmann27.opentakez.core.model.MediaInfo
import io.github.timhofmann27.opentakez.ui.guessPlatform
import io.github.timhofmann27.opentakez.ui.ports.ConnectionResult
import io.github.timhofmann27.opentakez.ui.ports.DeviceEngineState
import io.github.timhofmann27.opentakez.ui.ports.DownloadsPort
import io.github.timhofmann27.opentakez.ui.ports.ResolvePort
import io.github.timhofmann27.opentakez.ui.ports.RoutingPort
import io.github.timhofmann27.opentakez.ui.ports.SettingsPort
import io.github.timhofmann27.opentakez.ui.ports.SharedLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface PreviewState {
    data object Idle : PreviewState
    data object Resolving : PreviewState

    data class Ready(
        val info: MediaInfo,
        val engine: Engine,
        val option: DownloadOption,
        /** True while the link is looked up again for the other engine. */
        val switching: Boolean = false,
        val starting: Boolean = false,
        /** A failed engine switch or a failed start; the preview stays usable. */
        val notice: EngineError? = null,
    ) : PreviewState

    data class Failed(val error: EngineError) : PreviewState
}

data class TokenDialogState(val checking: Boolean = false, val failure: ConnectionResult? = null)

data class HomeUiState(
    val text: String = "",
    val preview: PreviewState = PreviewState.Idle,
    val serverConfigured: Boolean = false,
    val deviceEngine: DeviceEngineState = DeviceEngineState.Ready,
    /** Non-null while the token dialog is open (a 401 happened). */
    val tokenDialog: TokenDialogState? = null,
)

sealed interface HomeEvent {
    /** A download was queued; the UI moves to the Downloads tab. */
    data object DownloadQueued : HomeEvent
}

class HomeViewModel(
    private val resolver: ResolvePort,
    private val downloads: DownloadsPort,
    private val settings: SettingsPort,
    private val routing: RoutingPort,
) : ViewModel() {
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state

    private val _events = Channel<HomeEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var resolveJob: Job? = null
    /** Invalidates a download result when the user has moved on to another link. */
    private var previewRevision = 0L

    init {
        viewModelScope.launch {
            settings.serverUrl.combine(settings.tokenSet) { url, tokenSet ->
                url.isNotEmpty() && tokenSet
            }.collect { configured -> _state.update { it.copy(serverConfigured = configured) } }
        }
        viewModelScope.launch {
            settings.deviceEngine.collect { engine -> _state.update { it.copy(deviceEngine = engine) } }
        }
    }

    fun setText(text: String) = _state.update { it.copy(text = text) }

    fun clear() {
        previewRevision++
        resolveJob?.cancel()
        _state.update { it.copy(text = "", preview = PreviewState.Idle, tokenDialog = null) }
    }

    fun submit() = resolve(_state.value.text)

    /** Text from the clipboard; checked right away like the PWA's Paste button. */
    fun paste(clip: String?) {
        if (clip.isNullOrBlank()) return
        setText(clip)
        resolve(clip)
    }

    /** A share from another app. Without a link the user lands on Home with the "no link" error. */
    fun openShared(link: String?) {
        if (link == null) {
            previewRevision++
            resolveJob?.cancel()
            _state.update { it.copy(text = "", preview = PreviewState.Failed(EngineError.NoLink())) }
        } else {
            setText(link)
            resolve(link)
        }
    }

    fun selectOption(option: DownloadOption) = updateReady { it.copy(option = option) }

    /** Options can differ per engine (photos), so the link is looked up again. */
    fun selectEngine(engine: Engine) {
        val ready = _state.value.preview as? PreviewState.Ready ?: return
        if (ready.engine == engine || ready.switching || ready.starting) return
        if (engine == Engine.SERVER && !_state.value.serverConfigured) return
        updateReady { it.copy(switching = true, notice = null) }
        val revision = previewRevision
        resolveJob?.cancel()
        resolveJob = viewModelScope.launch {
            try {
                val info = resolver.resolve(ready.info.url, engine)
                if (revision == previewRevision) {
                    updateReady {
                        it.copy(
                            info = info,
                            engine = engine,
                            option = it.option.takeIf { o -> o in info.options } ?: info.options.first(),
                            switching = false,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (revision == previewRevision) {
                    updateReady { it.copy(switching = false, notice = e.asEngineError()) }
                }
            }
        }
    }

    fun start() {
        val ready = _state.value.preview as? PreviewState.Ready ?: return
        if (ready.starting || ready.switching) return
        val revision = previewRevision
        updateReady { it.copy(starting = true, notice = null) }
        viewModelScope.launch {
            try {
                downloads.start(ready.info.url, ready.info, ready.option, ready.engine)
                if (revision == previewRevision) {
                    _state.update { it.copy(text = "", preview = PreviewState.Idle) }
                    _events.send(HomeEvent.DownloadQueued)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (revision == previewRevision) {
                    updateReady { it.copy(starting = false, notice = e.asEngineError(ready.info.platform)) }
                }
            }
        }
    }

    fun dismissTokenDialog() = _state.update { it.copy(tokenDialog = null) }

    /** Tests the token against the saved server; on success saves it and checks the link again. */
    fun submitToken(token: String) {
        val candidate = token.trim()
        if (candidate.isEmpty()) return
        _state.update { it.copy(tokenDialog = TokenDialogState(checking = true)) }
        viewModelScope.launch {
            val url = settings.serverUrl.first()
            val result = settings.testConnection(url, candidate)
            if (result is ConnectionResult.Connected) {
                settings.saveServer(url, candidate)
                _state.update { it.copy(tokenDialog = null) }
                resolve(_state.value.text)
            } else {
                _state.update { it.copy(tokenDialog = TokenDialogState(failure = result)) }
            }
        }
    }

    private fun resolve(text: String) {
        previewRevision++
        val revision = previewRevision
        resolveJob?.cancel()
        if (text.isBlank()) {
            _state.update { it.copy(preview = PreviewState.Idle, tokenDialog = null) }
            return
        }
        val link = SharedLink.extract(text)
        if (link == null) {
            _state.update { it.copy(preview = PreviewState.Failed(EngineError.NoLink())) }
            return
        }
        _state.update { it.copy(preview = PreviewState.Resolving, tokenDialog = null) }
        resolveJob = viewModelScope.launch {
            try {
                val engine = chooseEngine(link)
                val info = resolver.resolve(link, engine)
                if (revision == previewRevision) {
                    _state.update {
                        it.copy(preview = PreviewState.Ready(info, engine, info.options.first()))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = e.asEngineError(guessPlatform(link))
                if (revision == previewRevision) {
                    _state.update {
                        it.copy(
                            preview = PreviewState.Failed(error),
                            tokenDialog = if (error is EngineError.Unauthorized) TokenDialogState() else null,
                        )
                    }
                }
            }
        }
    }

    /** Photo links go to the server when there is one; otherwise the user's default applies. */
    private suspend fun chooseEngine(link: String): Engine {
        val serverConfigured = settings.serverUrl.combine(settings.tokenSet) { url, tokenSet ->
            url.isNotEmpty() && tokenSet
        }.first()
        val routed = routing.defaultEngine(link, serverConfigured)
        if (routed == Engine.SERVER || !serverConfigured) return routed
        return settings.defaultEngine.first()
    }

    private inline fun updateReady(transform: (PreviewState.Ready) -> PreviewState.Ready) {
        _state.update { state ->
            val ready = state.preview as? PreviewState.Ready ?: return@update state
            state.copy(preview = transform(ready))
        }
    }

    private fun Exception.asEngineError(platformGuess: String? = null): EngineError = when (this) {
        is EngineError.LoginRequired -> if (platform == null) EngineError.LoginRequired(platformGuess?.ifEmpty { null }) else this
        is EngineError -> this
        else -> EngineError.Other(0, null)
    }
}
