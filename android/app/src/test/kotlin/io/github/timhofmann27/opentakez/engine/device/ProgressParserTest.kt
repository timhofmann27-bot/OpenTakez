package io.github.timhofmann27.opentakez.engine.device

import io.github.timhofmann27.opentakez.core.model.JobProgress
import io.github.timhofmann27.opentakez.core.model.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProgressParserTest {
    private val parser = ProgressParser()

    @Test
    fun `download line gives fraction, speed and eta`() {
        val p = parser.parse("[download]  42.0% of   10.00MiB at    1.50MiB/s ETA 00:05")!!

        assertEquals(Phase.DOWNLOADING, p.phase)
        assertEquals(0.42, p.fraction!!, 1e-9)
        assertEquals(1.5 * 1024 * 1024, p.speedBytesPerSecond!!, 1.0)
        assertEquals(5, p.etaSeconds)
    }

    @Test
    fun `estimated sizes and fragment counters are accepted`() {
        val p = parser.parse("[download]  50.0% of ~  20.00MiB at  512.00KiB/s ETA 01:02:03 (frag 3/10)")!!

        assertEquals(0.5, p.fraction!!, 1e-9)
        assertEquals(512.0 * 1024, p.speedBytesPerSecond!!, 1.0)
        assertEquals(3723, p.etaSeconds)
    }

    @Test
    fun `final line without eta keeps the speed`() {
        val p = parser.parse("[download] 100% of   10.00MiB in 00:00:08 at 1.25MiB/s")!!

        assertEquals(1.0, p.fraction!!, 1e-9)
        assertEquals(1.25 * 1024 * 1024, p.speedBytesPerSecond!!, 1.0)
        assertNull(p.etaSeconds)
    }

    @Test
    fun `unknown speed and eta become null`() {
        val p = parser.parse("[download]   3.1% of ~   5.00MiB at  Unknown B/s ETA Unknown")!!

        assertEquals(0.031, p.fraction!!, 1e-9)
        assertNull(p.speedBytesPerSecond)
        assertNull(p.etaSeconds)
    }

    @Test
    fun `byte and gibibyte units are converted`() {
        assertEquals(
            900.0,
            parser.parse("[download]  1.0% of 1.00GiB at 900.00B/s ETA 10:00")!!.speedBytesPerSecond!!,
            0.001,
        )
        assertEquals(
            2.0 * 1024 * 1024 * 1024,
            parser.parse("[download]  1.0% of 9.00GiB at 2.00GiB/s ETA 00:10")!!.speedBytesPerSecond!!,
            1.0,
        )
    }

    @Test
    fun `post processing markers switch to processing`() {
        listOf(
            "[Merger] Merging formats into \"/x/a.mp4\"",
            "[ExtractAudio] Destination: /x/a.mp3",
            "[EmbedThumbnail] ffmpeg: Adding thumbnail to \"/x/a.mp3\"",
            "[FixupM4a] Correcting container of \"/x/a.m4a\"",
        ).forEach {
            assertEquals(it, JobProgress(Phase.PROCESSING), parser.parse(it))
        }
    }

    @Test
    fun `other lines are ignored`() {
        assertNull(parser.parse("[youtube] Extracting URL: https://www.youtube.com/watch?v=abc"))
        assertNull(parser.parse("[info] abc: Downloading 1 format(s): 137+251"))
        assertNull(parser.parse("[download] Destination: /x/a.f137.mp4"))
        assertNull(parser.parse("[ThumbnailsConvertor] Converting thumbnail"))
        assertNull(parser.parse(""))
    }

    @Test
    fun `playlist position scales the fraction across items`() {
        assertNull(parser.parse("[download] Downloading item 2 of 4"))

        val p = parser.parse("[download]  50.0% of 10.00MiB at 1.00MiB/s ETA 00:05")!!

        assertEquals((1 + 0.5) / 4, p.fraction!!, 1e-9)
    }

    @Test
    fun `a trailing carriage return does not matter`() {
        assertEquals(0.1, parser.parse("[download]  10.0% of 1.00MiB at 1.00MiB/s ETA 00:01\r")!!.fraction!!, 1e-9)
    }
}
