package io.github.timhofmann27.opentakez.ui.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.timhofmann27.opentakez.core.model.Engine
import io.github.timhofmann27.opentakez.ui.ports.ConnectionResult
import io.github.timhofmann27.opentakez.ui.ports.SettingsPort
import io.github.timhofmann27.opentakez.ui.ports.UpdateResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Same accepted address and canonical form as engine/server/ServerUrl, plus "" for no server. */
object ServerUrl {
    /** "" for empty input, the origin plus path without a trailing slash, or null if it is not a usable URL. */
    fun normalize(input: String): String? {
        val text = input.trim()
        if (text.isEmpty()) return ""
        if (text.substringBefore("://", missingDelimiterValue = "").lowercase() != "https") return null
        // OkHttp treats backslashes as slashes and repairs extra slashes; reject ambiguous input.
        if ('?' in text || '#' in text || '\\' in text) return null
        val authority = text.substringAfter("://").substringBefore('/')
        if (authority.isEmpty() || '@' in authority) return null

        val url = text.toHttpUrlOrNull() ?: return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null

        val path = url.encodedPath.trimEnd('/').removeSuffix("/api").trimEnd('/')
        return url.newBuilder().encodedPath(path.ifEmpty { "/" }).build().toString().trimEnd('/')
    }
}

sealed interface TestState {
    data object Idle : TestState
    data object Testing : TestState
    data class Done(val result: ConnectionResult) : TestState
}

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Updating : UpdateState
    data class Done(val result: UpdateResult) : UpdateState
}

data class SettingsUiState(
    val serverUrl: String = "",
    /** The saved server; the default-engine choice depends on it, not on what is typed. */
    val savedServerUrl: String = "",
    /** Typed but not saved; never persisted by the UI and cleared once saved. */
    val tokenInput: String = "",
    val tokenSet: Boolean = false,
    val defaultEngine: Engine = Engine.DEVICE,
    val language: String = "",
    val ytDlpVersion: String? = null,
    val test: TestState = TestState.Idle,
    val saved: Boolean = false,
    val tokenRemoved: Boolean = false,
    val update: UpdateState = UpdateState.Idle,
) {
    /** Null when the typed URL is not usable. "" means no server. */
    val normalizedUrl: String? get() = ServerUrl.normalize(serverUrl)
    val urlInvalid: Boolean get() = normalizedUrl == null
    val serverSaved: Boolean get() = savedServerUrl.isNotEmpty() && tokenSet
}

class SettingsViewModel(
    private val settings: SettingsPort,
    /** Applies the app language; the default hands it to AppCompat, tests record it. */
    private val applyLocale: (tag: String) -> Unit = ::applyAppLocale,
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state

    init {
        viewModelScope.launch {
            val url = settings.serverUrl.first()
            _state.update { it.copy(serverUrl = url, savedServerUrl = url) }
            settings.serverUrl.collect { saved -> _state.update { it.copy(savedServerUrl = saved) } }
        }
        viewModelScope.launch { settings.tokenSet.collect { v -> _state.update { it.copy(tokenSet = v) } } }
        viewModelScope.launch { settings.defaultEngine.collect { v -> _state.update { it.copy(defaultEngine = v) } } }
        viewModelScope.launch { settings.language.collect { v -> _state.update { it.copy(language = v) } } }
        viewModelScope.launch { settings.ytDlpVersion.collect { v -> _state.update { it.copy(ytDlpVersion = v) } } }
    }

    fun setServerUrl(value: String) =
        _state.update { it.copy(serverUrl = value, saved = false, tokenRemoved = false, test = TestState.Idle) }

    fun setToken(value: String) =
        _state.update { it.copy(tokenInput = value, saved = false, tokenRemoved = false, test = TestState.Idle) }

    fun save() {
        val current = _state.value
        val url = current.normalizedUrl ?: return
        viewModelScope.launch {
            settings.saveServer(url, current.tokenInput.trim().ifEmpty { null })
            _state.update { it.copy(serverUrl = url, tokenInput = "", saved = true) }
        }
    }

    fun removeToken() {
        viewModelScope.launch {
            settings.saveServer(_state.value.savedServerUrl, "")
            _state.update { it.copy(tokenInput = "", tokenRemoved = true, saved = false) }
        }
    }

    fun testConnection() {
        val current = _state.value
        val url = current.normalizedUrl?.takeIf { it.isNotEmpty() } ?: return
        if (current.test == TestState.Testing) return
        _state.update { it.copy(test = TestState.Testing) }
        viewModelScope.launch {
            val result = settings.testConnection(url, current.tokenInput.trim().ifEmpty { null })
            _state.update { it.copy(test = TestState.Done(result)) }
        }
    }

    fun setDefaultEngine(engine: Engine) {
        viewModelScope.launch { settings.setDefaultEngine(engine) }
    }

    fun setLanguage(tag: String) {
        viewModelScope.launch {
            settings.setLanguage(tag)
            applyLocale(tag)
        }
    }

    fun updateYtDlp() {
        if (_state.value.update == UpdateState.Updating) return
        _state.update { it.copy(update = UpdateState.Updating) }
        viewModelScope.launch {
            val result = settings.updateYtDlp()
            _state.update { it.copy(update = UpdateState.Done(result)) }
        }
    }
}

/** "" follows the system language. AppCompat recreates the activity and keeps the choice itself. */
fun applyAppLocale(tag: String) {
    AppCompatDelegate.setApplicationLocales(
        if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag),
    )
}
