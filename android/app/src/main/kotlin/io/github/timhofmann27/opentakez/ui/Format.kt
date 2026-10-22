package io.github.timhofmann27.opentakez.ui

import java.util.Locale
import kotlin.math.roundToInt

/** Ports of web/src/lib/format.ts. They return "" for unknown values so callers can filter. */
object Format {
    fun duration(seconds: Double?): String {
        if (seconds == null || !seconds.isFinite()) return ""
        val total = seconds.roundToInt()
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        return if (h > 0) fmt("%d:%02d:%02d", h, m, s) else fmt("%d:%02d", m, s)
    }

    fun duration(seconds: Int?): String = duration(seconds?.toDouble())

    fun speed(bytesPerSecond: Double?): String {
        if (bytesPerSecond == null || bytesPerSecond <= 0.0) return ""
        val units = listOf("B/s", "KB/s", "MB/s", "GB/s")
        var value = bytesPerSecond
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        val digits = if (value < 10 && unit > 0) 1 else 0
        return fmt("%.${digits}f %s", value, units[unit])
    }

    fun percent(fraction: Double?): String =
        if (fraction == null) "" else "${(fraction.coerceIn(0.0, 1.0) * 100).toInt()} %"

    private fun fmt(pattern: String, vararg args: Any) = String.format(Locale.getDefault(), pattern, *args)
}
