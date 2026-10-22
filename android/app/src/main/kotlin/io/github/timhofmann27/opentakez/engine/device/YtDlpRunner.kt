package io.github.timhofmann27.opentakez.engine.device

enum class UpdateOutcome { UPDATED, ALREADY_LATEST }

/** What a yt-dlp process printed. A non-zero [exitCode] is a result, not an exception. */
class RunResult(val exitCode: Int, val stdout: String, val stderr: String)

/**
 * Everything the device engine needs from youtubedl-android, so the engine is testable on the JVM.
 * Failures other than a non-zero exit code are thrown as exceptions.
 */
interface YtDlpRunner {
    /** Extracts yt-dlp, Python and ffmpeg. Blocking work belongs on an IO dispatcher inside. */
    suspend fun init()

    suspend fun version(): String?

    /** Downloads the newest stable yt-dlp. Throws if the network or the install fails. */
    suspend fun update(): UpdateOutcome

    /**
     * Runs yt-dlp with [args]. When [redirectErrorStream] is true, stderr is merged into stdout,
     * so [onLine] sees progress and errors together. Cancelling the caller does not stop the
     * process by itself; call [cancel] with [processId].
     */
    suspend fun execute(
        args: List<String>,
        processId: String,
        redirectErrorStream: Boolean,
        onLine: (String) -> Unit,
    ): RunResult

    /** Kills the process started with [processId], if any. Safe to call from any thread. */
    fun cancel(processId: String)
}
