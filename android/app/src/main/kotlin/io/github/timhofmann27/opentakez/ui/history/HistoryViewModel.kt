package io.github.timhofmann27.opentakez.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.timhofmann27.opentakez.core.model.EngineError
import io.github.timhofmann27.opentakez.ui.ports.DownloadsPort
import io.github.timhofmann27.opentakez.ui.ports.HistoryEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface HistoryEvent {
    data object DownloadQueued : HistoryEvent
}

class HistoryViewModel(private val downloads: DownloadsPort) : ViewModel() {
    /** Null until the first list arrives. */
    val items: StateFlow<List<HistoryEntry>?> =
        downloads.history.map<List<HistoryEntry>, List<HistoryEntry>?> { it }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private val _notice = MutableStateFlow<EngineError?>(null)
    val notice: StateFlow<EngineError?> = _notice

    private val _events = Channel<HistoryEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun remove(id: String) = act { downloads.removeHistory(id) }

    fun clear() = act { downloads.clearHistory() }

    fun downloadAgain(id: String) = act {
        downloads.downloadAgain(id)
        _events.send(HistoryEvent.DownloadQueued)
    }

    fun dismissNotice() {
        _notice.value = null
    }

    private fun act(block: suspend () -> Unit) {
        _notice.value = null
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: EngineError) {
                _notice.value = e
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
