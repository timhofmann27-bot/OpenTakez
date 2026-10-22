package io.github.timhofmann27.opentakez.engine.device

import io.github.timhofmann27.opentakez.core.model.JobProgress
import io.github.timhofmann27.opentakez.core.model.Phase

/** Turns yt-dlp output lines into progress. One instance per download: it remembers the playlist item. */
internal class ProgressParser {
    private var item = 0
    private var itemCount = 0

    fun parse(rawLine: String): JobProgress? {
        val line = rawLine.trim()
        itemRegex.matchEntire(line)?.let {
            item = it.groupValues[1].toInt()
            itemCount = it.groupValues[2].toInt()
            return null
        }
        if (processingPrefixes.any { line.startsWith(it) }) return JobProgress(Phase.PROCESSING)
        val match = percentRegex.find(line) ?: return null

        val own = (match.groupValues[1].toDouble() / 100).coerceIn(0.0, 1.0)
        val fraction = if (itemCount > 1 && item in 1..itemCount) (item - 1 + own) / itemCount else own
        return JobProgress(
            phase = Phase.DOWNLOADING,
            fraction = fraction,
            speedBytesPerSecond = speedRegex.find(line)?.let { toBytes(it.groupValues[1], it.groupValues[2]) },
            etaSeconds = etaRegex.find(line)?.let { toSeconds(it.groupValues[1]) },
        )
    }

    private fun toBytes(number: String, unit: String): Double? {
        val factor = when (unit) {
            "B" -> 1.0
            "KiB", "kB", "KB" -> 1024.0
            "MiB", "MB" -> 1024.0 * 1024
            "GiB", "GB" -> 1024.0 * 1024 * 1024
            else -> return null
        }
        return number.toDouble() * factor
    }

    private fun toSeconds(time: String): Int? {
        val parts = time.split(':').map { it.toIntOrNull() ?: return null }
        return parts.fold(0) { total, part -> total * 60 + part }
    }

    private companion object {
        val itemRegex = Regex("""\[download] Downloading item (\d+) of (\d+)""")
        val percentRegex = Regex("""^\[download]\s+(\d+(?:\.\d+)?)%""")
        val speedRegex = Regex("""\bat\s+(\d+(?:\.\d+)?)\s*([A-Za-z]+)/s""")
        val etaRegex = Regex("""\bETA\s+(\d+(?::\d+){1,2})""")
        val processingPrefixes = listOf("[Merger]", "[ExtractAudio]", "[EmbedThumbnail]", "[FixupM4a]")
    }
}
