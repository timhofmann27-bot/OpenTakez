package io.github.timhofmann27.opentakez.ui

import android.content.Intent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.github.timhofmann27.opentakez.core.model.Engine
import io.github.timhofmann27.opentakez.ui.fakes.FakeServices
import io.github.timhofmann27.opentakez.ui.fakes.FakeSettingsPort
import io.github.timhofmann27.opentakez.ui.fakes.SampleData
import io.github.timhofmann27.opentakez.ui.history.FileActions
import io.github.timhofmann27.opentakez.ui.ports.ConnectionResult
import io.github.timhofmann27.opentakez.ui.ports.HistoryEntry
import io.github.timhofmann27.opentakez.ui.ports.ShareInbox
import io.github.timhofmann27.opentakez.ui.ports.UpdateResult
import io.github.timhofmann27.opentakez.ui.theme.OpenTakezTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The whole UI wired to fake ports: navigation, the share flow, permissions, settings. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = TALL_SCREEN)
class AppFlowTest {
    @get:Rule
    val rule = createComposeRule()

    private val inbox = ShareInbox()
    private var settings = FakeSettingsPort()
    private val services by lazy { FakeServices(settings = settings) }

    private val noFiles = object : FileActions {
        override fun open(entry: HistoryEntry) = true
        override fun share(entry: HistoryEntry, chooserTitle: String) = true
    }

    private fun show(sdk: Int = 30, granted: (String) -> Boolean = { true }) {
        rule.setContent {
            OpenTakezTheme(dynamicColor = false) {
                AppRoot(services, inbox, fileActions = noFiles, sdkInt = sdk, isGranted = granted)
            }
        }
    }

    private fun sendIntent(text: String?, subject: String? = null) =
        Intent(Intent.ACTION_SEND).setType("text/plain").apply {
            text?.let { putExtra(Intent.EXTRA_TEXT, it) }
            subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
        }

    private fun button(text: String) =
        hasText(text) and hasClickAction() and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    private fun tab(text: String) = hasText(text) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    private fun passwordField() = SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)

    private fun openTab(text: String) {
        rule.onNode(tab(text)).performClick()
    }

    // ---- navigation ----

    @Test
    fun `four tabs, Download first`() {
        show()
        listOf("Download", "Downloads", "History", "Settings").forEach { rule.onNode(tab(it)).assertExists() }
        rule.onNodeWithText("Link").assertExists()
    }

    @Test
    fun `each tab shows its screen`() {
        show()
        openTab("Downloads")
        rule.onNodeWithText("No downloads yet. Start one from the Download tab.").assertExists()
        openTab("History")
        rule.onNodeWithText("Finished downloads show up here.").assertExists()
        openTab("Settings")
        rule.onNodeWithText("Test connection").assertExists()
        openTab("Download")
        rule.onNodeWithText("Link").assertExists()
    }

    @Test
    fun `running downloads are counted on the Downloads tab`() {
        show()
        services.downloads.activeState.value = listOf(SampleData.running("a"), SampleData.running("b"), SampleData.failed("c"))
        rule.waitForIdle()
        rule.onNodeWithContentDescription("2 running", useUnmergedTree = true).assertExists()
    }

    // ---- share target ----

    @Test
    fun `a shared message with a link opens Preview with the clean link`() {
        show()

        ShareIntents.handle(sendIntent("look https://youtu.be/x!"), inbox)
        rule.waitForIdle()

        assertEquals(listOf("https://youtu.be/x" to Engine.DEVICE), services.resolve.calls)
        rule.onNodeWithText("A very calm video about a lake").assertExists()
        rule.onNodeWithText("https://youtu.be/x").assertExists()
        rule.onNodeWithText("Best").assertExists()
        assertEquals(null, inbox.events.value)
    }

    @Test
    fun `a share that arrived before the UI was composed is still handled`() {
        ShareIntents.handle(sendIntent("https://youtu.be/early"), inbox)

        show()
        rule.waitForIdle()

        assertEquals("https://youtu.be/early", services.resolve.calls.single().first)
        rule.onNodeWithText("A very calm video about a lake").assertExists()
    }

    @Test
    fun `a shared text without a link shows Home with the no-link error`() {
        show()

        ShareIntents.handle(sendIntent("see you tomorrow"), inbox)
        rule.waitForIdle()

        rule.onNodeWithText("No link found in that text.").assertExists()
        assertTrue(services.resolve.calls.isEmpty())
    }

    @Test
    fun `a share while another tab is open switches to Home`() {
        show()
        openTab("Settings")

        ShareIntents.handle(sendIntent("https://youtu.be/x"), inbox)
        rule.waitForIdle()

        rule.onNodeWithText("A very calm video about a lake").assertExists()
        rule.onNodeWithText("Test connection").assertDoesNotExist()
    }

    @Test
    fun `sharing the same link twice resolves twice`() {
        show()
        ShareIntents.handle(sendIntent("https://youtu.be/x"), inbox)
        rule.waitForIdle()
        ShareIntents.handle(sendIntent("https://youtu.be/x"), inbox)
        rule.waitForIdle()
        assertEquals(2, services.resolve.calls.size)
    }

    // ---- starting a download and permissions ----

    @Test
    fun `pressing Download queues the job and shows the Downloads tab`() {
        show()
        ShareIntents.handle(sendIntent("https://youtu.be/x"), inbox)
        rule.waitForIdle()

        rule.onNode(button("Download")).performClick()
        rule.waitForIdle()

        assertEquals(1, services.downloads.started.size)
        rule.onNodeWithText("No downloads yet. Start one from the Download tab.").assertExists()
    }

    @Test
    fun `notifications are explained before the first download and declining does not block it`() {
        show(sdk = 34, granted = { false })
        ShareIntents.handle(sendIntent("https://youtu.be/x"), inbox)
        rule.waitForIdle()

        rule.onNode(button("Download")).performClick()
        rule.onNodeWithText("Downloads show their progress as a notification.").assertExists()
        assertTrue(services.downloads.started.isEmpty())

        rule.onNode(button("Not now")).performClick()
        rule.waitForIdle()

        assertEquals(1, services.downloads.started.size)
        rule.onNodeWithText("Downloads show their progress as a notification.").assertDoesNotExist()
    }

    @Test
    fun `no prompt when notifications are already allowed`() {
        show(sdk = 34, granted = { true })
        ShareIntents.handle(sendIntent("https://youtu.be/x"), inbox)
        rule.waitForIdle()

        rule.onNode(button("Download")).performClick()
        rule.waitForIdle()

        assertEquals(1, services.downloads.started.size)
    }

    @Test
    fun `no prompt on Android 10 to 12`() {
        show(sdk = 31, granted = { false })
        ShareIntents.handle(sendIntent("https://youtu.be/x"), inbox)
        rule.waitForIdle()

        rule.onNode(button("Download")).performClick()
        rule.waitForIdle()

        assertEquals(1, services.downloads.started.size)
    }

    @Test
    fun `permission rules per Android version`() {
        val none: (String) -> Boolean = { false }
        assertEquals(listOf("android.permission.WRITE_EXTERNAL_STORAGE"), DownloadPermissions.missing(26, none))
        assertEquals(listOf("android.permission.WRITE_EXTERNAL_STORAGE"), DownloadPermissions.missing(28, none))
        assertTrue(DownloadPermissions.missing(29, none).isEmpty())
        assertTrue(DownloadPermissions.missing(32, none).isEmpty())
        assertEquals(listOf("android.permission.POST_NOTIFICATIONS"), DownloadPermissions.missing(33, none))
        assertEquals(listOf("android.permission.POST_NOTIFICATIONS"), DownloadPermissions.missing(36, none))
        assertTrue(DownloadPermissions.missing(36) { true }.isEmpty())
        assertTrue(DownloadPermissions.missing(27) { true }.isEmpty())
    }

    // ---- history ----

    @Test
    fun `download again from History queues it and shows Downloads`() {
        show()
        services.downloads.historyState.value = listOf(SampleData.history("h1"))
        openTab("History")

        rule.onNode(button("Download again")).performClick()
        rule.waitForIdle()

        assertEquals(listOf("h1"), services.downloads.repeated)
        rule.onNodeWithText("No downloads yet. Start one from the Download tab.").assertExists()
    }

    // ---- settings ----

    @Test
    fun `test connection reports a success with versions and platforms`() {
        settings = FakeSettingsPort(serverUrl = "https://dl.example.com")
        show()
        openTab("Settings")

        rule.onNode(button("Test connection")).performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Connected: OpenTakez 1.0.0, yt-dlp 2026.10.21").assertExists()
        rule.onNodeWithText("Platforms: youtube").assertExists()
        assertEquals("https://dl.example.com", settings.lastTestedUrl)
    }

    private fun outcome(result: ConnectionResult, expected: String) {
        settings = FakeSettingsPort(serverUrl = "https://dl.example.com", tokenSet = true)
        settings.connectionResult = result
        show()
        openTab("Settings")
        rule.onNode(button("Test connection")).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(expected).assertExists()
    }

    @Test
    fun `test connection - unreachable`() =
        outcome(ConnectionResult.Unreachable, "Server unreachable. Check your connection or the server URL in Settings.")

    @Test
    fun `test connection - token rejected`() = outcome(ConnectionResult.TokenRejected, "That token was not accepted.")

    @Test
    fun `test connection - server too old`() =
        outcome(ConnectionResult.TooOld, "This server is too old. OpenTakez needs server version 1.0.0 or newer.")

    @Test
    fun `test connection - not an OpenTakez server`() =
        outcome(ConnectionResult.NotOpenTakez, "That address doesn’t look like an OpenTakez server.")

    @Test
    fun `test connection is off without a server address`() {
        show()
        openTab("Settings")
        rule.onNode(button("Test connection")).assertIsNotEnabledCompat()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertIsNotEnabledCompat() {
        assertTrue(fetchSemanticsNode().config.contains(SemanticsProperties.Disabled))
    }

    @Test
    fun `an invalid address is flagged and cannot be saved`() {
        show()
        openTab("Settings")

        rule.onNode(hasSetText() and !passwordField()).performTextInput("http://dl.example.com")

        rule.onNodeWithText("Enter a full URL such as https://dl.example.com").assertExists()
        assertTrue(rule.onNode(button("Save")).fetchSemanticsNode().config.contains(SemanticsProperties.Disabled))
    }

    private fun hasSetText() = SemanticsMatcher.keyIsDefined(SemanticsActions.SetText)

    @Test
    fun `saving stores the address and the typed token, then empties the token field`() {
        show()
        openTab("Settings")

        rule.onNode(hasSetText() and !passwordField()).performTextInput("https://dl.example.com/")
        rule.onNode(passwordField()).performTextInput("s3cret-token")
        rule.onNode(passwordField()).assertMasked("s3cret-token")
        rule.onNode(button("Save")).performClick()
        rule.waitForIdle()

        assertEquals("https://dl.example.com", settings.serverUrlState.value)
        assertEquals("s3cret-token", settings.lastSavedToken)
        rule.onNodeWithText("Saved").assertExists()
        rule.onNodeWithText("A token is saved. Leave this empty to keep it.", substring = true).assertExists()
    }

    @Test
    fun `the token field is masked and the stored token is never shown`() {
        settings = FakeSettingsPort(serverUrl = "https://dl.example.com", tokenSet = true)
        show()
        openTab("Settings")

        rule.onNode(passwordField()).performTextInput("hunter2")
        rule.onNode(passwordField()).assertMasked("hunter2")
    }

    @Test
    fun `a stored token can be removed`() {
        settings = FakeSettingsPort(serverUrl = "https://dl.example.com", tokenSet = true)
        show()
        openTab("Settings")

        rule.onNode(button("Remove token")).performClick()
        rule.waitForIdle()

        assertEquals("", settings.lastSavedToken)
        rule.onNodeWithText("Token removed").assertExists()
        rule.onNodeWithText("Remove token").assertDoesNotExist()
    }

    @Test
    fun `the default engine can be chosen only once a server is saved`() {
        show()
        openTab("Settings")
        val server = rule.onAllNodesWithText("Via my server")[0]
        assertTrue(server.fetchSemanticsNode().config.contains(SemanticsProperties.Disabled))
    }

    @Test
    fun `choosing the server as default is saved`() {
        settings = FakeSettingsPort(serverUrl = "https://dl.example.com")
        show()
        openTab("Settings")

        rule.onNodeWithText("Via my server").performClick()
        rule.waitForIdle()

        assertEquals(Engine.SERVER, settings.engineState.value)
    }

    @Test
    fun `yt-dlp shows its version and reports the update outcome`() {
        settings = FakeSettingsPort()
        settings.updateResult = UpdateResult.Updated("2026.11.01")
        show()
        openTab("Settings")
        rule.onNodeWithText("2026.10.21").assertExists()

        rule.onNode(button("Update")).performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Updated to 2026.11.01").assertExists()
        rule.onNodeWithText("2026.11.01").assertExists()
    }

    @Test
    fun `yt-dlp update failure and already latest`() {
        settings = FakeSettingsPort()
        settings.updateResult = UpdateResult.Failed("offline")
        show()
        openTab("Settings")
        rule.onNode(button("Update")).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Update failed. Check your connection.").assertExists()

        settings.updateResult = UpdateResult.AlreadyLatest
        rule.onNode(button("Update")).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("yt-dlp is already up to date.").assertExists()
    }

    @Test
    fun `About shows the app version and licenses`() {
        show()
        openTab("Settings")
        rule.onNodeWithText("About").assertExists()
        rule.onNodeWithText("Open-source licenses").assertExists()
        rule.onNodeWithText("Version", substring = true).assertExists()
    }

    @Test
    fun `choosing a language is saved`() {
        show()
        openTab("Settings")
        rule.onNodeWithText("Deutsch").performClick()
        rule.waitForIdle()
        assertEquals("de", settings.languageState.value)
    }
}
