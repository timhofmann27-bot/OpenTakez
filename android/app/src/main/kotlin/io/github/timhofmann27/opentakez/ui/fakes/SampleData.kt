package io.github.timhofmann27.opentakez.ui.fakes

import io.github.timhofmann27.opentakez.core.model.DownloadOption
import io.github.timhofmann27.opentakez.core.model.Engine
import io.github.timhofmann27.opentakez.core.model.EngineError
import io.github.timhofmann27.opentakez.core.model.JobProgress
import io.github.timhofmann27.opentakez.core.model.MediaInfo
import io.github.timhofmann27.opentakez.core.model.MediaKind
import io.github.timhofmann27.opentakez.core.model.Phase
import io.github.timhofmann27.opentakez.ui.ports.ActiveDownload
import io.github.timhofmann27.opentakez.ui.ports.DownloadStatus
import io.github.timhofmann27.opentakez.ui.ports.HistoryEntry

/** Sample content for previews and tests. No thumbnails, so nothing touches the network. */
object SampleData {
    fun video(url: String = "https://youtu.be/abc") = MediaInfo(
        url = url,
        platform = "youtube",
        kind = MediaKind.VIDEO,
        title = "A very calm video about a lake",
        uploader = "Lake Channel",
        duration = 213.0,
        options = listOf(
            DownloadOption.VIDEO_BEST,
            DownloadOption.VIDEO_1080,
            DownloadOption.VIDEO_720,
            DownloadOption.AUDIO_MP3,
            DownloadOption.AUDIO_M4A,
        ),
    )

    fun audio(url: String = "https://soundcloud.com/a/b") = MediaInfo(
        url = url,
        platform = "soundcloud",
        kind = MediaKind.AUDIO,
        title = "Evening set",
        uploader = "DJ Quiet",
        duration = 3725.0,
        options = listOf(DownloadOption.AUDIO_MP3, DownloadOption.AUDIO_M4A),
    )

    fun photos(url: String = "https://www.instagram.com/p/xyz/", count: Int = 50, total: Int = 80) = MediaInfo(
        url = url,
        platform = "instagram",
        kind = MediaKind.IMAGE,
        title = "Holiday carousel",
        uploader = "someone",
        options = listOf(DownloadOption.IMAGE_ORIGINAL),
        itemCount = count,
        totalItems = total,
    )

    fun running(id: String = "a") = ActiveDownload(
        id = id,
        url = "https://youtu.be/abc",
        title = "A very calm video about a lake",
        uploader = "Lake Channel",
        thumbnail = null,
        platform = "youtube",
        kind = MediaKind.VIDEO,
        option = DownloadOption.VIDEO_1080,
        engine = Engine.DEVICE,
        status = DownloadStatus.RUNNING,
        progress = JobProgress(Phase.DOWNLOADING, 0.42, 2_621_440.0, 75),
    )

    fun failed(id: String = "b", error: EngineError = EngineError.NeedsServer()) = running(id).copy(
        title = "A photo post",
        status = DownloadStatus.FAILED,
        progress = null,
        error = error,
    )

    fun history(id: String = "h1", files: List<String> = listOf("content://media/external/video/media/1")) =
        HistoryEntry(
            id = id,
            url = "https://youtu.be/abc",
            title = "A very calm video about a lake",
            uploader = "Lake Channel",
            thumbnail = null,
            platform = "youtube",
            kind = MediaKind.VIDEO,
            option = DownloadOption.VIDEO_1080,
            engine = Engine.DEVICE,
            files = files,
            finishedAtMillis = 1_700_000_000_000,
        )
}
