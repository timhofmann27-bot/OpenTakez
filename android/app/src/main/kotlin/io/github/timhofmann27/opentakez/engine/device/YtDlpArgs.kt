package io.github.timhofmann27.opentakez.engine.device

import io.github.timhofmann27.opentakez.core.model.DownloadOption
import java.io.File
import java.net.URI

/** The yt-dlp command lines of docs/design/m6-contract.md section 4, as `format_options` on the server. */
internal object YtDlpArgs {
    const val MAX_ITEMS = 50

    fun resolve(url: String): List<String> = listOf(
        "--dump-single-json",
        "--flat-playlist", "--playlist-end", MAX_ITEMS.toString(),
    ) + singleItemArgs(url) + listOf(
        "--no-warnings",
        "--socket-timeout", "20",
        url,
    )

    fun download(url: String, option: DownloadOption, workDir: File): List<String> =
        formatArgs(option) + commonArgs(workDir) + singleItemArgs(url) + url

    private fun singleItemArgs(url: String): List<String> {
        val uri = runCatching { URI(url) }.getOrNull()
        val host = uri?.host?.lowercase().orEmpty()
        val path = uri?.path.orEmpty()
        val youtubePlaylist = (host == "youtube.com" || host.endsWith(".youtube.com")) && path == "/playlist"
        val soundcloudSet = (host == "soundcloud.com" || host.endsWith(".soundcloud.com")) &&
            path.split('/').filter { it.isNotEmpty() }.getOrNull(1) == "sets"
        return if (youtubePlaylist || soundcloudSet) emptyList() else listOf("--no-playlist")
    }

    private fun formatArgs(option: DownloadOption): List<String> = when (option) {
        DownloadOption.VIDEO_BEST -> video("ext:mp4:m4a")
        DownloadOption.VIDEO_1080 -> video("res:1080,ext:mp4:m4a")
        DownloadOption.VIDEO_720 -> video("res:720,ext:mp4:m4a")
        DownloadOption.AUDIO_M4A -> audio("ba[ext=m4a]/ba/b", "m4a")
        DownloadOption.AUDIO_MP3 -> audio("ba/b", "mp3")
        DownloadOption.IMAGE_ORIGINAL -> throw IllegalArgumentException("yt-dlp can't download ${option.wire}")
    }

    // Prefers mp4/m4a because phones play them without conversion.
    private fun video(sort: String) =
        listOf("-f", "bv*+ba/b", "-S", sort, "--merge-output-format", "mp4")

    private fun audio(selector: String, codec: String) = listOf(
        "-f", selector,
        "-x", "--audio-format", codec, "--audio-quality", "0",
        "--embed-metadata", "--embed-thumbnail", "--convert-thumbnails", "jpg",
    )

    private fun commonArgs(workDir: File) = listOf(
        // '%' in the directory would be read as a template field.
        "-o", workDir.path.replace("%", "%%") + "/%(playlist_index&{:02d} |)s%(title).150B [%(id)s].%(ext)s",
        "--windows-filenames",
        "--playlist-items", "1:$MAX_ITEMS",
        // One unavailable item must not abort the rest of a playlist.
        "--ignore-errors",
        "--socket-timeout", "20",
        "--retries", "3",
        "--newline",
        "--no-warnings",
    )
}
