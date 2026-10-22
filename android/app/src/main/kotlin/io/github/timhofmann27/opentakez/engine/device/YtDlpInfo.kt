package io.github.timhofmann27.opentakez.engine.device

import io.github.timhofmann27.opentakez.core.Platforms
import io.github.timhofmann27.opentakez.core.model.DownloadOption
import io.github.timhofmann27.opentakez.core.model.EngineError
import io.github.timhofmann27.opentakez.core.model.MediaInfo
import io.github.timhofmann27.opentakez.core.model.MediaKind
import java.net.URI
import java.net.URISyntaxException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable
private class FormatDto(val vcodec: String? = null, val height: Double? = null)

@Serializable
private class ThumbnailDto(val url: String? = null)

/** The parts of `--dump-single-json` output the app reads; entries of a flat playlist share the shape. */
@Serializable
private class InfoDto(
    val id: String? = null,
    val title: String? = null,
    val uploader: String? = null,
    val channel: String? = null,
    @SerialName("webpage_url") val webpageUrl: String? = null,
    val thumbnail: String? = null,
    val thumbnails: List<ThumbnailDto?>? = null,
    val duration: Double? = null,
    @SerialName("_type") val type: String? = null,
    @SerialName("extractor_key") val extractorKey: String? = null,
    @SerialName("ie_key") val ieKey: String? = null,
    @SerialName("playlist_count") val playlistCount: Int? = null,
    val entries: List<InfoDto?>? = null,
    val formats: List<FormatDto?>? = null,
    // Some extractors (Instagram) put the codec on the item itself instead of in `formats`.
    val vcodec: String? = null,
    val height: Double? = null,
) {
    val isPlaylist get() = type == "playlist" || type == "multi_video"
    val entryKey get() = ieKey ?: extractorKey

    val hasVideo: Boolean
        get() {
            val all = formats?.filterNotNull()?.takeIf { it.isNotEmpty() } ?: listOf(FormatDto(vcodec, height))
            return all.any { (it.vcodec != null && it.vcodec != "none") || (it.vcodec == null && it.height.isPositive()) }
        }

    val firstThumbnail: String?
        get() = thumbnail?.takeIf { it.isNotEmpty() } ?: thumbnails?.lastOrNull()?.url

    private fun Double?.isPositive() = this != null && this > 0
}

/** Per platform: which yt-dlp extractors may appear, as in the providers of backend/app/providers/__init__.py. */
private class Rules(val leaves: Set<String>, val playlists: Set<String>? = null)

private val rulesByPlatform = mapOf(
    "tiktok" to Rules(setOf("TikTok", "TikTokVM")),
    "instagram" to Rules(setOf("Instagram")),
    "facebook" to Rules(setOf("Facebook", "FacebookReel")),
    "youtube" to Rules(setOf("Youtube"), playlists = setOf("YoutubeTab")),
    // Profiles list plain tracks too; only sets and albums count as playlists.
    "soundcloud" to Rules(setOf("Soundcloud"), playlists = setOf("SoundcloudSet")),
)

private const val CHANNELS_UNSUPPORTED =
    "Channels and profiles aren't supported yet. Share a single post, video, or playlist."

// Flat playlist entries carry no formats; these extractors only have audio.
private val audioOnlyExtractors = setOf("Soundcloud")

private val json = Json { ignoreUnknownKeys = true }

internal val VIDEO_OPTIONS = listOf(
    DownloadOption.VIDEO_BEST,
    DownloadOption.VIDEO_1080,
    DownloadOption.VIDEO_720,
    DownloadOption.AUDIO_MP3,
    DownloadOption.AUDIO_M4A,
)
internal val AUDIO_OPTIONS = listOf(DownloadOption.AUDIO_MP3, DownloadOption.AUDIO_M4A)

/** What a `--dump-single-json` run said, checked against the platform's rules. */
internal class ParsedInfo private constructor(private val info: InfoDto, private val platform: String) {
    /** Playlist entries that passed the checks, or null for a single item. */
    private val entries: List<InfoDto>? =
        if (info.isPlaylist) validatedEntries(info, platform) else null

    fun toMediaInfo(requestedUrl: String): MediaInfo {
        val title = info.title.orEmpty().ifEmpty { info.id.orEmpty() }.ifEmpty { requestedUrl }
        val uploader = (info.uploader ?: info.channel)?.takeIf { it.isNotEmpty() }
        val url = info.webpageUrl?.takeIf { it.isNotEmpty() } ?: requestedUrl

        val list = entries
        if (list == null) {
            val video = info.hasVideo
            return MediaInfo(
                url = url,
                platform = platform,
                kind = if (video) MediaKind.VIDEO else MediaKind.AUDIO,
                title = title,
                uploader = uploader,
                thumbnail = Platforms.trustedThumbnail(info.firstThumbnail, platform),
                duration = info.duration,
                options = if (video) VIDEO_OPTIONS else AUDIO_OPTIONS,
            )
        }

        val total = info.playlistCount ?: list.size
        val audioOnly = list.all { it.entryKey in audioOnlyExtractors } ||
            (list.all { it.formats != null } && list.none { it.hasVideo })
        val thumbnail = info.firstThumbnail ?: list.firstNotNullOfOrNull { it.firstThumbnail }
        return MediaInfo(
            url = url,
            platform = platform,
            kind = if (audioOnly) MediaKind.AUDIO else MediaKind.VIDEO,
            title = title,
            uploader = uploader,
            thumbnail = Platforms.trustedThumbnail(thumbnail, platform),
            options = if (audioOnly) AUDIO_OPTIONS else VIDEO_OPTIONS,
            itemCount = minOf(total, YtDlpArgs.MAX_ITEMS),
            totalItems = total,
        )
    }

    companion object {
        /** Throws [EngineError] for output that is unreadable, a channel or profile, or an empty playlist. */
        fun parse(stdout: String, platform: String): ParsedInfo {
            val dto = try {
                json.decodeFromString<InfoDto>(stdout)
            } catch (e: SerializationException) {
                throw EngineError.Other(0, "yt-dlp returned unreadable data.")
            } catch (e: IllegalArgumentException) {
                throw EngineError.Other(0, "yt-dlp returned unreadable data.")
            }
            return ParsedInfo(dto, platform)
        }

        private fun validatedEntries(info: InfoDto, platform: String): List<InfoDto> {
            val rules = rulesByPlatform.getValue(platform)
            if (rules.playlists != null && info.extractorKey !in rules.playlists) {
                throw EngineError.Platform(CHANNELS_UNSUPPORTED)
            }
            // Channel tabs share YouTube's playlist extractor; only /playlist?list= is a playlist.
            if (info.extractorKey == "YoutubeTab" && pathOf(info.webpageUrl) != "/playlist") {
                throw EngineError.Platform(CHANNELS_UNSUPPORTED)
            }
            val entries = info.entries.orEmpty().filterNotNull()
            if (entries.any { it.isPlaylist || it.entryKey !in rules.leaves }) {
                throw EngineError.Platform(CHANNELS_UNSUPPORTED)
            }
            if (entries.isEmpty()) throw EngineError.Platform("This playlist is empty.")
            return entries
        }

        private fun pathOf(url: String?): String? = try {
            url?.let { URI(it).path }
        } catch (_: URISyntaxException) {
            null
        }
    }
}
