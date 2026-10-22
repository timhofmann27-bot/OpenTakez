package io.github.timhofmann27.opentakez.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What a link contains. `IMAGE` also covers carousels that mix photos and videos. */
@Serializable
enum class MediaKind {
    @SerialName("video") VIDEO,
    @SerialName("audio") AUDIO,
    @SerialName("image") IMAGE,
}

/** The six formats the server offers; the wire names are the server's (backend/app/providers/base.py). */
@Serializable
enum class DownloadOption(val wire: String) {
    @SerialName("video-best") VIDEO_BEST("video-best"),
    @SerialName("video-1080") VIDEO_1080("video-1080"),
    @SerialName("video-720") VIDEO_720("video-720"),
    @SerialName("audio-mp3") AUDIO_MP3("audio-mp3"),
    @SerialName("audio-m4a") AUDIO_M4A("audio-m4a"),
    @SerialName("image-original") IMAGE_ORIGINAL("image-original"),
    ;

    val isAudio get() = this == AUDIO_MP3 || this == AUDIO_M4A
    val isVideo get() = this == VIDEO_BEST || this == VIDEO_1080 || this == VIDEO_720
}

/** Same shape as the server's `MediaInfo` (POST /api/resolve), so both engines return one type. */
@Serializable
data class MediaInfo(
    val url: String,
    val platform: String,
    val kind: MediaKind,
    val title: String,
    val uploader: String? = null,
    val thumbnail: String? = null,
    val duration: Double? = null,
    /** Always offer exactly these; the server rejects anything else with 422. */
    val options: List<DownloadOption>,
    @SerialName("item_count") val itemCount: Int? = null,
    @SerialName("total_items") val totalItems: Int? = null,
)

/** Where a download runs. */
enum class Engine { DEVICE, SERVER }

enum class Phase { DOWNLOADING, PROCESSING }

data class JobProgress(
    val phase: Phase,
    /** 0.0..1.0, or null while unknown. */
    val fraction: Double? = null,
    val speedBytesPerSecond: Double? = null,
    val etaSeconds: Int? = null,
)

/** A finished file in the job's work directory, before it is saved to shared storage. */
data class DownloadedFile(val file: java.io.File, val displayName: String)

/** Where the user's own server lives; the token is only ever sent in the Authorization header. */
data class ServerConfig(val baseUrl: String, val token: String)
