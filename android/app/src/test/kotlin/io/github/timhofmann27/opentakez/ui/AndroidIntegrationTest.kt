package io.github.timhofmann27.opentakez.ui

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.github.timhofmann27.opentakez.MainActivity
import io.github.timhofmann27.opentakez.MainViewModel
import io.github.timhofmann27.opentakez.core.model.MediaKind
import io.github.timhofmann27.opentakez.ui.fakes.SampleData
import io.github.timhofmann27.opentakez.ui.fakes.FakeServices
import io.github.timhofmann27.opentakez.ui.history.AndroidFileActions
import io.github.timhofmann27.opentakez.ui.ports.AppServicesProvider
import io.github.timhofmann27.opentakez.ui.ports.ShareInbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = TALL_SCREEN)
class ShareIntentsTest {
    private val inbox = ShareInbox()

    private fun send(text: String? = null, subject: String? = null, type: String = "text/plain", action: String = Intent.ACTION_SEND) =
        Intent(action).setType(type).apply {
            text?.let { putExtra(Intent.EXTRA_TEXT, it) }
            subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
        }

    @Test
    fun `a link in the text is offered cleaned`() {
        assertTrue(ShareIntents.handle(send("look https://youtu.be/x!"), inbox))
        assertEquals("https://youtu.be/x", inbox.events.value?.link)
    }

    @Test
    fun `the subject is used when the text has no link`() {
        ShareIntents.handle(send("hello", subject = "https://youtu.be/s"), inbox)
        assertEquals("https://youtu.be/s", inbox.events.value?.link)
    }

    @Test
    fun `a share without a link is still an event so the error can be shown`() {
        assertTrue(ShareIntents.handle(send("no link"), inbox))
        assertNull(inbox.events.value!!.link)
        ShareIntents.handle(send(), inbox)
        assertNull(inbox.events.value!!.link)
    }

    @Test
    fun `other intents are ignored`() {
        assertFalse(ShareIntents.handle(null, inbox))
        assertFalse(ShareIntents.handle(send("https://youtu.be/x", action = Intent.ACTION_VIEW), inbox))
        assertFalse(ShareIntents.handle(send("https://youtu.be/x", type = "image/png"), inbox))
        assertFalse(ShareIntents.handle(Intent(Intent.ACTION_MAIN), inbox))
        assertNull(inbox.events.value)
    }

    @Test
    fun `a huge shared text is read only up to a limit`() {
        val text = "x".repeat(200_000) + " https://youtu.be/late"
        ShareIntents.handle(send(text), inbox)
        assertNull(inbox.events.value!!.link)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = TALL_SCREEN)
class FileActionsTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val uri1 = "content://media/external/video/media/1"
    private val uri2 = "content://media/external/video/media/2"

    @Test
    fun `open views the first saved file with read permission`() {
        assertTrue(AndroidFileActions(app).open(SampleData.history(files = listOf(uri1, uri2))))

        val started = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(Uri.parse(uri1), started.data)
        assertEquals("video/*", started.type)
        assertTrue(started.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun `the type follows the media kind when the provider does not know it`() {
        AndroidFileActions(app).open(SampleData.history().copy(kind = MediaKind.AUDIO))
        assertEquals("audio/*", shadowOf(app).nextStartedActivity.type)
    }

    @Test
    fun `share sends one file with a chooser`() {
        assertTrue(AndroidFileActions(app).share(SampleData.history(files = listOf(uri1)), "Share"))

        val chooser = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals(Uri.parse(uri1), send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun `share sends several files together`() {
        assertTrue(AndroidFileActions(app).share(SampleData.history(files = listOf(uri1, uri2)), "Share"))

        val send = shadowOf(app).nextStartedActivity.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND_MULTIPLE, send.action)
        assertEquals(2, send.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)!!.size)
    }

    @Test
    fun `an entry without files cannot be opened or shared`() {
        val actions = AndroidFileActions(app)
        assertFalse(actions.open(SampleData.history(files = emptyList())))
        assertFalse(actions.share(SampleData.history(files = emptyList()), "Share"))
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun `no handler or a revoked grant is reported, not thrown`() {
        val refusing = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) = throw ActivityNotFoundException()
        }
        assertFalse(AndroidFileActions(refusing).open(SampleData.history()))
        val denying: Context = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) = throw SecurityException()
        }
        assertFalse(AndroidFileActions(denying).share(SampleData.history(), "Share"))
    }
}

/** The real activity with test services: the share intent reaches Preview. */
class TestApp : Application(), AppServicesProvider {
    override val services = FakeServices()
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = TALL_SCREEN, application = TestApp::class)
class MainActivityTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun sendIntent(text: String) =
        Intent(app, MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)

    @Test
    fun `launch from the launcher shows Home`() {
        ActivityScenario.launch(MainActivity::class.java).use {
            rule.onNodeWithText("Link").assertExists()
        }
    }

    @Test
    fun `launching with a SEND intent lands in Preview with the clean link`() {
        ActivityScenario.launch<MainActivity>(sendIntent("look https://youtu.be/x!")).use {
            rule.waitForIdle()
            rule.onNodeWithText("A very calm video about a lake").assertExists()
            rule.onNodeWithText("https://youtu.be/x").assertExists()
        }
    }

    @Test
    fun `launching with a SEND intent without a link shows the no-link error`() {
        ActivityScenario.launch<MainActivity>(sendIntent("nothing to see")).use {
            rule.waitForIdle()
            rule.onNodeWithText("No link found in that text.").assertExists()
        }
    }

    @Test
    fun `a second share reaches the running activity through onNewIntent`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val inbox = ViewModelProvider(activity)[MainViewModel::class.java].shareInbox

            controller.newIntent(sendIntent("https://youtu.be/second"))

            assertEquals("https://youtu.be/second", inbox.events.value?.link)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `rotating keeps the preview and does not read the share intent again`() {
        ActivityScenario.launch<MainActivity>(sendIntent("https://youtu.be/x")).use { scenario ->
            rule.waitForIdle()
            scenario.onActivity { activity ->
                val inbox = androidx.lifecycle.ViewModelProvider(activity)[io.github.timhofmann27.opentakez.MainViewModel::class.java].shareInbox
                assertNull(inbox.events.value)
            }
            scenario.recreate()
            rule.waitForIdle()
            scenario.onActivity { activity ->
                val inbox = androidx.lifecycle.ViewModelProvider(activity)[io.github.timhofmann27.opentakez.MainViewModel::class.java].shareInbox
                assertNull("the old intent must not be offered again", inbox.events.value)
            }
            rule.onNodeWithText("A very calm video about a lake").assertExists()
        }
    }
}
