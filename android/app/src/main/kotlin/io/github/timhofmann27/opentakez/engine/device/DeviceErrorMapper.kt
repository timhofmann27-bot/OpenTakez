package io.github.timhofmann27.opentakez.engine.device

import io.github.timhofmann27.opentakez.core.model.EngineError

/** Maps yt-dlp's stderr to the app's errors, with the server's rules from `YtDlpProvider._error`. */
object DeviceErrorMapper {
    // Only clear login cases. Vaguer messages ("content isn't available") also mean deleted posts.
    private val loginHints = listOf(
        "login required",
        "log in",
        "registered users",
        "not granting access",
        "empty media response",
        "there is no video in this post",
    )

    // The server's COOKIE_PLATFORMS: where a login can help.
    private val cookiePlatforms = setOf("instagram", "tiktok", "facebook")

    private val ansi = Regex("\u001b\\[[0-9;]*m")

    fun map(stderr: String, platform: String?): EngineError {
        val message = lastErrorLine(stderr)
        val lowered = message.lowercase()
        return when {
            "no space left" in lowered -> EngineError.StorageFull()
            "rate-limit" in lowered -> EngineError.RateLimited(null)
            platform == "youtube" && isBlock(lowered) -> EngineError.YoutubeBlocked()
            "unsupported url" in lowered -> EngineError.Unsupported()
            platform in cookiePlatforms && loginHints.any { it in lowered } -> EngineError.LoginRequired(platform)
            else -> EngineError.Platform(message)
        }
    }

    /** Same cleanup as `_error_message`: no colour codes, no "ERROR: " prefix. */
    internal fun clean(line: String): String = ansi.replace(line, "").trim().removePrefix("ERROR: ").trim()

    private fun isBlock(lowered: String) =
        "sign in to confirm" in lowered || "http error 403" in lowered

    private fun lastErrorLine(stderr: String): String {
        val lines = ansi.replace(stderr, "").lines().map { it.trim() }.filter { it.isNotEmpty() }
        val line = lines.lastOrNull { it.startsWith("ERROR:") } ?: lines.lastOrNull()
        return line?.let(::clean)?.ifEmpty { null } ?: "The download failed."
    }
}
