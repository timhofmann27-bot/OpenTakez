package io.github.timhofmann27.opentakez.ui

import android.content.Context
import androidx.annotation.StringRes
import io.github.timhofmann27.opentakez.R
import io.github.timhofmann27.opentakez.core.model.DownloadOption
import io.github.timhofmann27.opentakez.core.model.EngineError
import io.github.timhofmann27.opentakez.ui.ports.ConnectionResult
import java.util.Locale

private val GUESS_HOSTS = listOf(
    Regex("""(^|\.)instagram\.com$""") to "instagram",
    Regex("""(^|\.)tiktok\.com$""") to "tiktok",
    Regex("""(^|\.)(facebook\.com|fb\.watch|fb\.com)$""") to "facebook",
)

/** Best-effort platform from a link, for errors raised before the engine could say (login required). */
fun guessPlatform(link: String): String {
    val host = runCatching { java.net.URI(link).host }.getOrNull()?.lowercase(Locale.ROOT) ?: return ""
    return GUESS_HOSTS.firstOrNull { (pattern, _) -> pattern.containsMatchIn(host) }?.second ?: ""
}

/** The string for a failed connection test or token check; null for a success. */
@StringRes
fun ConnectionResult.failureMessage(): Int? = when (this) {
    is ConnectionResult.Connected -> null
    ConnectionResult.Unreachable -> R.string.error_network
    ConnectionResult.TokenRejected -> R.string.token_wrong
    ConnectionResult.TooOld -> R.string.settings_serverTooOld
    ConnectionResult.NotOpenTakez -> R.string.settings_notOpenTakez
}

/** Same wording and fallbacks as web/src/lib/errors.ts; server and yt-dlp explanations are shown as-is. */
fun EngineError.toMessage(context: Context): String = when (this) {
    is EngineError.Network -> context.getString(R.string.error_network)
    is EngineError.Unauthorized -> context.getString(R.string.token_wrong)
    is EngineError.Lost -> context.getString(R.string.job_lost)
    is EngineError.Conflict -> context.getString(R.string.job_stillRunning)
    is EngineError.NoLink -> context.getString(R.string.error_noLink)
    is EngineError.Unsupported -> context.getString(R.string.error_unsupported)
    is EngineError.LoginRequired ->
        if (platform.isNullOrBlank()) {
            context.getString(R.string.error_loginRequiredServerGeneric)
        } else {
            context.getString(R.string.error_loginRequiredServer, platformName(platform))
        }
    is EngineError.RateLimited ->
        context.getString(R.string.error_rateLimited, retryAfterSeconds ?: DEFAULT_RETRY_SECONDS)
    is EngineError.Busy -> context.getString(R.string.error_busy)
    is EngineError.NeedsServer -> context.getString(R.string.error_needsServer)
    is EngineError.YoutubeBlocked -> context.getString(R.string.error_youtubeBlocked)
    is EngineError.StorageFull -> context.getString(R.string.error_storageFull)
    is EngineError.Platform -> message?.takeIf { it.isNotBlank() } ?: context.getString(R.string.error_unknown, "?")
    is EngineError.Other ->
        message?.takeIf { it.isNotBlank() } ?: context.getString(R.string.error_unknown, status.toString())
}

private const val DEFAULT_RETRY_SECONDS = 60

private val PLATFORM_NAMES = mapOf(
    "youtube" to "YouTube",
    "soundcloud" to "SoundCloud",
    "tiktok" to "TikTok",
    "instagram" to "Instagram",
    "facebook" to "Facebook",
    "spotify" to "Spotify",
)

fun platformName(platform: String): String = PLATFORM_NAMES[platform.lowercase(Locale.ROOT)] ?: platform

@StringRes
fun DownloadOption.label(): Int = when (this) {
    DownloadOption.VIDEO_BEST -> R.string.option_video_best
    DownloadOption.VIDEO_1080 -> R.string.option_video_1080
    DownloadOption.VIDEO_720 -> R.string.option_video_720
    DownloadOption.AUDIO_MP3 -> R.string.option_audio_mp3
    DownloadOption.AUDIO_M4A -> R.string.option_audio_m4a
    DownloadOption.IMAGE_ORIGINAL -> R.string.option_image_original
}
