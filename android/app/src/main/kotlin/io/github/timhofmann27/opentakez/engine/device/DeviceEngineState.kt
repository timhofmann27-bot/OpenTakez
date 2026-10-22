package io.github.timhofmann27.opentakez.engine.device

sealed interface DeviceEngineState {
    /** Extracting yt-dlp, Python and ffmpeg; the UI shows "Getting ready ...". */
    data object Initializing : DeviceEngineState

    data class Ready(val ytDlpVersion: String?) : DeviceEngineState

    data class Failed(val message: String) : DeviceEngineState
}

sealed interface UpdateResult {
    data class Updated(val version: String?) : UpdateResult

    data class AlreadyLatest(val version: String?) : UpdateResult

    data class Failed(val message: String) : UpdateResult
}

/** Remembers when yt-dlp was last updated (DataStore in the app, a fake in tests). */
interface YtDlpUpdateStore {
    /** Null on a fresh install: the bundled yt-dlp is too old for YouTube. */
    suspend fun lastUpdateMillis(): Long?

    suspend fun setLastUpdateMillis(millis: Long)
}
