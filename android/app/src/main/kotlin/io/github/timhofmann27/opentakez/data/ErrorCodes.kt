package io.github.timhofmann27.opentakez.data

import io.github.timhofmann27.opentakez.core.model.EngineError
import io.github.timhofmann27.opentakez.storage.StoragePermissionException

/** Stable codes stored in `downloads.errorCode`; the UI maps them to texts. */
object ErrorCodes {
    const val NETWORK = "network"
    const val UNAUTHORIZED = "unauthorized"
    const val LOST = "lost"
    const val CONFLICT = "conflict"
    const val NO_LINK = "no_link"
    const val UNSUPPORTED = "unsupported"
    const val LOGIN_REQUIRED = "login_required"
    const val RATE_LIMITED = "rate_limited"
    const val BUSY = "busy"
    const val NEEDS_SERVER = "needs_server"
    const val YOUTUBE_BLOCKED = "youtube_blocked"
    const val STORAGE_FULL = "storage_full"
    const val PLATFORM = "platform"
    const val OTHER = "other"

    /** The save step lacked the storage permission (API 26-28). */
    const val STORAGE_PERMISSION = "storage_permission"

    /** Android stopped the job (for example the dataSync time limit); Retry starts it again. */
    const val STOPPED = "stopped"

    private const val MAX_MESSAGE = 300

    fun codeOf(t: Throwable): String = when (t) {
        is EngineError.Network -> NETWORK
        is EngineError.Unauthorized -> UNAUTHORIZED
        is EngineError.Lost -> LOST
        is EngineError.Conflict -> CONFLICT
        is EngineError.NoLink -> NO_LINK
        is EngineError.Unsupported -> UNSUPPORTED
        is EngineError.LoginRequired -> LOGIN_REQUIRED
        is EngineError.RateLimited -> RATE_LIMITED
        is EngineError.Busy -> BUSY
        is EngineError.NeedsServer -> NEEDS_SERVER
        is EngineError.YoutubeBlocked -> YOUTUBE_BLOCKED
        is EngineError.StorageFull -> STORAGE_FULL
        is EngineError.Platform -> PLATFORM
        is EngineError.Other -> OTHER
        is StoragePermissionException -> STORAGE_PERMISSION
        else -> OTHER
    }

    /**
     * A short, single-line message for the UI. Never a stack trace; only the first line of the
     * message, capped, so a runaway stderr dump does not end up in the database.
     */
    fun messageOf(t: Throwable): String? {
        val firstLine = t.message?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
        return when {
            firstLine.isNullOrEmpty() -> if (t is EngineError) null else t.javaClass.simpleName
            firstLine.length > MAX_MESSAGE -> firstLine.take(MAX_MESSAGE) + "..."
            else -> firstLine
        }
    }
}
