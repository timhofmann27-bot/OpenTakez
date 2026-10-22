package io.github.timhofmann27.opentakez.core.model

/**
 * Everything the UI can show as a failure, mapped like web/src/lib/api.ts and errors.ts.
 * Cancelling is not an error: it is a coroutine `CancellationException`.
 */
sealed class EngineError(message: String? = null) : Exception(message) {
    /** The server or the internet could not be reached (HTTP status 0 in the PWA). */
    class Network(message: String? = null) : EngineError(message)

    /** 401: the server token is wrong or missing. Opens the token dialog. */
    class Unauthorized : EngineError()

    /** 404 on a job: the server restarted and forgot it. */
    class Lost : EngineError()

    /** 409: the job is still running, or the download is not finished. */
    class Conflict : EngineError()

    class NoLink : EngineError()
    class Unsupported : EngineError()

    /** The content needs a login. The server path points to cookies in the web app. */
    class LoginRequired(val platform: String?) : EngineError()

    class RateLimited(val retryAfterSeconds: Int?) : EngineError()

    /** 503: the server is busy or its queue is full. */
    class Busy : EngineError()

    /** Photos and carousels need the user's server, and none is configured. */
    class NeedsServer : EngineError()

    /** YouTube refused (bot check or 403) even with a JavaScript runtime. */
    class YoutubeBlocked : EngineError()

    class StorageFull : EngineError()

    /** A platform error the server or yt-dlp explained in words (HTTP 422, or yt-dlp stderr). */
    class Platform(message: String) : EngineError(message)

    /** Anything else; [status] is the HTTP status, or 0 for a device-side failure. */
    class Other(val status: Int, message: String? = null) : EngineError(message)
}
