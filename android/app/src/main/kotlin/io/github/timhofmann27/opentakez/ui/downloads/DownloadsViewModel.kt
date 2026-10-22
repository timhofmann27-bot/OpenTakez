package io.github.timhofmann27.opentakez.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.timhofmann27.opentakez.core.model.EngineError
import io.github.timhofmann27.opentakez.ui.ports.ActiveDownload
import io.github.timhofmann27.opentakez.ui.ports.DownloadStatus
import io.github.timhofmann27.opentakez.ui.ports.DownloadsPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DownloadsViewModel(private val downloads: DownloadsPort) : ViewModel() {
    /** Null until the first list arrives, so the empty text does not flash. */
    val items: StateFlow<List<ActiveDownload>?> =
        downloads.active.map<List<ActiveDownload>, List<ActiveDownload>?> { it }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** Queued and running downloads, for the badge in the navigation bar. */
    val runningCount: StateFlow<Int> =
        downloads.active.map { list -> list.count { it.status != DownloadStatus.FAILED } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0)

    private val _notice = MutableStateFlow<EngineError?>(null)

    /** The last action that failed (for example a conflict on remove), shown until dismissed. */
    val notice: StateFlow<EngineError?> = _notice

    fun cancel(id: String) = act { downloads.cancel(id) }
    fun retry(id: String) = act { downloads.retry(id) }
    fun remove(id: String) = act { downloads.remove(id) }
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
