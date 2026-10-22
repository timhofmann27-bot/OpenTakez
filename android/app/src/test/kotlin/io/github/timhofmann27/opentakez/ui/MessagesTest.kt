package io.github.timhofmann27.opentakez.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.timhofmann27.opentakez.core.model.EngineError
import io.github.timhofmann27.opentakez.ui.ports.ConnectionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class MessagesTest {
    private fun context(language: String): Context {
        RuntimeEnvironment.setQualifiers("+$language")
        return ApplicationProvider.getApplicationContext()
    }

    private val samples: Map<kotlin.reflect.KClass<out EngineError>, EngineError> = listOf(
        EngineError.Network(),
        EngineError.Unauthorized(),
        EngineError.Lost(),
        EngineError.Conflict(),
        EngineError.NoLink(),
        EngineError.Unsupported(),
        EngineError.LoginRequired("instagram"),
        EngineError.RateLimited(12),
        EngineError.Busy(),
        EngineError.NeedsServer(),
        EngineError.YoutubeBlocked(),
        EngineError.StorageFull(),
        EngineError.Platform("Video unavailable"),
        EngineError.Other(500),
    ).associateBy { it::class }

    @Test
    fun `every EngineError subtype has a sample, so none is left without a message`() {
        assertEquals(EngineError::class.sealedSubclasses.toSet(), samples.keys)
    }

    @Test
    fun `English texts match the PWA wording`() {
        val c = context("en")
        assertEquals("Server unreachable. Check your connection or the server URL in Settings.", EngineError.Network().toMessage(c))
        assertEquals("That token was not accepted.", EngineError.Unauthorized().toMessage(c))
        assertEquals("The server restarted and lost this download.", EngineError.Lost().toMessage(c))
        assertEquals("Still running, remove it when it is finished.", EngineError.Conflict().toMessage(c))
        assertEquals("No link found in that text.", EngineError.NoLink().toMessage(c))
        assertEquals("This site isn’t supported yet.", EngineError.Unsupported().toMessage(c))
        assertEquals("The server is busy. Try again in a moment.", EngineError.Busy().toMessage(c))
        assertEquals("Photos and carousels need your own server. Add it in Settings.", EngineError.NeedsServer().toMessage(c))
        assertEquals(
            "YouTube blocked this request. Update yt-dlp in Settings, or try again later.",
            EngineError.YoutubeBlocked().toMessage(c),
        )
        assertEquals("Not enough free space on this device.", EngineError.StorageFull().toMessage(c))
    }

    @Test
    fun `German texts match the PWA wording`() {
        val c = context("de")
        assertEquals("In diesem Text wurde kein Link gefunden.", EngineError.NoLink().toMessage(c))
        assertEquals("Diese Seite wird noch nicht unterstützt.", EngineError.Unsupported().toMessage(c))
        assertEquals("Der Server ist ausgelastet. Versuch es gleich noch einmal.", EngineError.Busy().toMessage(c))
        assertEquals("Nicht genug freier Speicherplatz auf diesem Gerät.", EngineError.StorageFull().toMessage(c))
        assertEquals(
            "Fotos und Karussells brauchen deinen eigenen Server. Richte ihn in den Einstellungen ein.",
            EngineError.NeedsServer().toMessage(c),
        )
    }

    @Test
    fun `rate limit uses the server's seconds and defaults to 60`() {
        val en = context("en")
        assertEquals("Too many requests. Try again in 12 s.", EngineError.RateLimited(12).toMessage(en))
        assertEquals("Too many requests. Try again in 60 s.", EngineError.RateLimited(null).toMessage(en))
        val de = context("de")
        assertEquals("Zu viele Anfragen. Versuch es in 60 s noch einmal.", EngineError.RateLimited(null).toMessage(de))
    }

    @Test
    fun `login required names the platform, with a generic fallback`() {
        val en = context("en")
        assertEquals(
            "This needs a login. Upload cookies for Instagram in your server’s web app.",
            EngineError.LoginRequired("instagram").toMessage(en),
        )
        assertEquals(
            "This needs a login. Upload cookies in your server’s web app.",
            EngineError.LoginRequired(null).toMessage(en),
        )
        assertEquals(
            "This needs a login. Upload cookies in your server’s web app.",
            EngineError.LoginRequired(" ").toMessage(en),
        )
        val de = context("de")
        assertEquals(
            "Dafür ist ein Login nötig. Lade Cookies für TikTok in der Web-App deines Servers hoch.",
            EngineError.LoginRequired("tiktok").toMessage(de),
        )
    }

    @Test
    fun `platform errors are shown as the engine worded them, blank ones fall back`() {
        val c = context("en")
        assertEquals("Video unavailable", EngineError.Platform("Video unavailable").toMessage(c))
        assertEquals("Something went wrong (?).", EngineError.Platform(" ").toMessage(c))
        assertEquals("Something went wrong (503).", EngineError.Other(503).toMessage(c))
        assertEquals("Boom", EngineError.Other(500, "Boom").toMessage(c))
    }

    @Test
    fun `every subtype yields a non-blank message in both languages`() {
        listOf("en", "de").forEach { language ->
            val c = context(language)
            samples.values.forEach { error ->
                assertTrue("${error::class.simpleName} in $language", error.toMessage(c).isNotBlank())
            }
        }
    }

    @Test
    fun `unknown platform names are shown as given`() {
        assertEquals("YouTube", platformName("youtube"))
        assertEquals("example", platformName("example"))
    }

    @Test
    fun `connection failures map to a text, a success to none`() {
        assertNull(ConnectionResult.Connected("1.0.0").failureMessage())
        val c = context("en")
        listOf(
            ConnectionResult.Unreachable,
            ConnectionResult.TokenRejected,
            ConnectionResult.TooOld,
            ConnectionResult.NotOpenTakez,
        ).forEach { assertTrue(c.getString(it.failureMessage()!!).isNotBlank()) }
    }
}
