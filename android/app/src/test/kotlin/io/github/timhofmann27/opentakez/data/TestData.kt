package io.github.timhofmann27.opentakez.data

import androidx.room.Room
import io.github.timhofmann27.opentakez.core.model.DownloadOption
import io.github.timhofmann27.opentakez.core.model.MediaInfo
import io.github.timhofmann27.opentakez.core.model.MediaKind
import org.robolectric.RuntimeEnvironment

fun inMemoryDatabase(): AppDatabase =
    Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()

fun mediaInfo(
    title: String = "A title",
    kind: MediaKind = MediaKind.VIDEO,
    options: List<DownloadOption> = listOf(DownloadOption.VIDEO_BEST),
) = MediaInfo(
    url = "https://www.youtube.com/watch?v=abc",
    platform = "youtube",
    kind = kind,
    title = title,
    uploader = "Someone",
    thumbnail = "https://i.ytimg.com/vi/abc/hq.jpg",
    duration = 12.5,
    options = options,
)

/** A deterministic clock: every call returns the next tick. */
class TickClock(start: Long = 1_000) : () -> Long {
    private var now = start
    override fun invoke(): Long = now++
}

fun sequentialIds(): () -> String {
    var n = 0
    return { "id-${n++}" }
}
