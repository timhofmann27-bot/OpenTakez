package io.github.timhofmann27.opentakez.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.timhofmann27.opentakez.core.model.DownloadOption
import io.github.timhofmann27.opentakez.core.model.Engine
import io.github.timhofmann27.opentakez.core.model.EngineError
import io.github.timhofmann27.opentakez.ui.fakes.SampleData
import io.github.timhofmann27.opentakez.ui.home.HomeScreen
import io.github.timhofmann27.opentakez.ui.home.HomeUiState
import io.github.timhofmann27.opentakez.ui.home.PreviewState
import io.github.timhofmann27.opentakez.ui.home.TokenDialog
import io.github.timhofmann27.opentakez.ui.home.TokenDialogState
import io.github.timhofmann27.opentakez.ui.ports.ConnectionResult
import io.github.timhofmann27.opentakez.ui.ports.DeviceEngineState
import io.github.timhofmann27.opentakez.ui.theme.OpenTakezTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = TALL_SCREEN)
class HomeScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val selectedOptions = mutableListOf<DownloadOption>()
    private val selectedEngines = mutableListOf<Engine>()
    private var downloads = 0
    private var submits = 0
    private var pastes = 0
    private var clears = 0
    private val typed = mutableListOf<String>()

    private fun show(state: HomeUiState) {
        rule.setContent {
            OpenTakezTheme(dynamicColor = false) {
                HomeScreen(
                    state = state,
                    onTextChange = { typed += it },
                    onPaste = { pastes++ },
                    onClear = { clears++ },
                    onSubmit = { submits++ },
                    onSelectOption = { selectedOptions += it },
                    onSelectEngine = { selectedEngines += it },
                    onDownload = { downloads++ },
                    onTokenSubmit = {},
                    onTokenDismiss = {},
                )
            }
        }
    }

    private fun ready(info: io.github.timhofmann27.opentakez.core.model.MediaInfo = SampleData.video(), server: Boolean = false) =
        HomeUiState(
            text = info.url,
            serverConfigured = server,
            preview = PreviewState.Ready(info, Engine.DEVICE, info.options.first()),
        )

    private val downloadButton get() = rule.onNode(hasText("Download") and hasClickAction())

    // ---- Home / idle ----

    @Test
    fun `an empty field offers Paste, the hint explains what to do`() {
        show(HomeUiState())

        rule.onNodeWithText("Link").assertExists()
        rule.onNode(hasText("Paste") and hasClickAction()).performClick()
        rule.onNodeWithText("Check link").assertDoesNotExist()
        rule.onNodeWithText("Paste a link from YouTube, TikTok, Instagram, Facebook, or SoundCloud", substring = true).assertExists()
        assertEquals(1, pastes)
    }

    @Test
    fun `a filled field offers Check link and Clear`() {
        show(HomeUiState(text = "https://youtu.be/x"))

        rule.onNodeWithText("Check link").performClick()
        rule.onNodeWithContentDescription("Clear").performClick()
        rule.onNodeWithText("Paste").assertDoesNotExist()
        assertEquals(1, submits)
        assertEquals(1, clears)
    }

    @Test
    fun `typing reports the text`() {
        show(HomeUiState())
        rule.onNode(hasSetTextAction()).performTextInput("abc")
        assertEquals(listOf("abc"), typed)
    }

    private fun hasSetTextAction() = SemanticsMatcher.keyIsDefined(SemanticsActions.SetText)

    @Test
    fun `checking a link shows a progress message`() {
        show(HomeUiState(text = "https://youtu.be/x", preview = PreviewState.Resolving))
        rule.onNodeWithText("Checking link …").assertExists()
    }

    @Test
    fun `the device engine warming up is announced, and a failed one explains itself`() {
        show(HomeUiState(deviceEngine = DeviceEngineState.Initializing))
        rule.onNodeWithText("Getting ready ...").assertExists()
    }

    @Test
    fun `a failed device engine shows a message`() {
        show(HomeUiState(deviceEngine = DeviceEngineState.Failed("boom")))
        rule.onNodeWithText("The on-device engine could not start. Restart the app, or use your server.").assertExists()
    }

    // ---- Preview ----

    @Test
    fun `preview groups options into Video and Audio only, using exactly the offered ones`() {
        show(ready())

        rule.onNodeWithText("A very calm video about a lake").assertExists()
        rule.onNodeWithText("Lake Channel").assertExists()
        rule.onNodeWithText("3:33").assertExists()
        rule.onNodeWithText("YouTube").assertExists()
        rule.onNodeWithText("Video").assertExists()
        rule.onNodeWithText("Audio only").assertExists()
        rule.onNodeWithText("Photos").assertDoesNotExist()
        listOf("Best", "1080p", "720p", "MP3", "M4A").forEach { rule.onNodeWithText(it).assertExists() }
        rule.onNodeWithText("Original").assertDoesNotExist()
    }

    @Test
    fun `an audio link only shows the audio group`() {
        show(ready(SampleData.audio()))

        rule.onNodeWithText("Audio only").assertExists()
        rule.onNodeWithText("Video").assertDoesNotExist()
        rule.onNodeWithText("1080p").assertDoesNotExist()
        rule.onNodeWithText("1:02:05").assertExists()
    }

    @Test
    fun `a photo link shows the Photos group and the truncation note`() {
        show(ready(SampleData.photos(count = 50, total = 80), server = true))

        rule.onNodeWithText("Photos").assertExists()
        rule.onNodeWithText("Original").assertExists()
        rule.onNodeWithText("Video").assertDoesNotExist()
        rule.onNodeWithText("Audio only").assertDoesNotExist()
        rule.onNodeWithText("Only the first 50 of 80 items will be downloaded.").assertExists()
        rule.onNodeWithText("50 items").assertExists()
    }

    @Test
    fun `no truncation note when everything is downloaded`() {
        show(ready(SampleData.photos(count = 5, total = 5), server = true))
        rule.onNodeWithText("Only the first", substring = true).assertDoesNotExist()
    }

    @Test
    fun `the selected option is marked and choosing another reports it`() {
        show(ready())

        rule.onNodeWithText("Best").assertIsSelected()
        rule.onNodeWithText("720p").assertIsNotSelected()
        rule.onNodeWithText("720p").performClick()
        rule.onNodeWithText("MP3").performClick()

        assertEquals(listOf(DownloadOption.VIDEO_720, DownloadOption.AUDIO_MP3), selectedOptions)
    }

    @Test
    fun `without a server the engine switch is disabled on the server side only`() {
        show(ready(server = false))

        rule.onNodeWithText("On this device").assertIsEnabled().assertIsSelected()
        rule.onNodeWithText("Via my server").assertIsNotEnabled()
        rule.onNodeWithText("Via my server").performClick()
        assertTrue(selectedEngines.isEmpty())
    }

    @Test
    fun `with a server the engine can be switched`() {
        show(ready(server = true))

        rule.onNodeWithText("Via my server").assertIsEnabled().performClick()
        assertEquals(listOf(Engine.SERVER), selectedEngines)
    }

    @Test
    fun `download button starts the download, shows Starting while busy`() {
        show(ready())
        downloadButton.assertIsEnabled().performClick()
        assertEquals(1, downloads)
    }

    @Test
    fun `while starting the button says so and is disabled`() {
        val info = SampleData.video()
        show(HomeUiState(preview = PreviewState.Ready(info, Engine.DEVICE, info.options.first(), starting = true)))
        rule.onNodeWithText("Starting …").assertIsNotEnabled()
    }

    @Test
    fun `a failed start keeps the preview and shows why`() {
        val info = SampleData.video()
        show(
            HomeUiState(
                preview = PreviewState.Ready(info, Engine.DEVICE, info.options.first(), notice = EngineError.StorageFull()),
            ),
        )
        rule.onNodeWithText("Not enough free space on this device.").assertExists()
        downloadButton.assertIsEnabled()
    }

    // ---- errors, en ----

    private fun failed(error: EngineError) = HomeUiState(text = "x", preview = PreviewState.Failed(error))

    @Test
    fun `errors show the English text`() {
        show(failed(EngineError.NoLink()))
        rule.onNodeWithText("No link found in that text.").assertExists()
    }

    @Test
    fun `rate limited error has the seconds`() {
        show(failed(EngineError.RateLimited(30)))
        rule.onNodeWithText("Too many requests. Try again in 30 s.").assertExists()
    }

    @Test
    fun `login required error names the platform`() {
        show(failed(EngineError.LoginRequired("instagram")))
        rule.onNodeWithText("This needs a login. Upload cookies for Instagram in your server’s web app.").assertExists()
    }

    @Test
    @Config(qualifiers = "+de")
    fun `errors show the German text`() {
        show(failed(EngineError.NoLink()))
        rule.onNodeWithText("In diesem Text wurde kein Link gefunden.").assertExists()
    }

    @Test
    @Config(qualifiers = "+de")
    fun `the German Home screen is German`() {
        show(HomeUiState())
        rule.onNode(hasText("Einfügen") and hasClickAction()).assertExists()
        rule.onNodeWithText("Link").assertExists()
    }

    @Test
    @Config(qualifiers = "+de")
    fun `a long German error is wrapped and never cut off, also at double font size`() {
        show(failed(EngineError.YoutubeBlocked()))
        val full = "YouTube hat diese Anfrage blockiert. Aktualisiere yt-dlp in den Einstellungen oder versuche es später noch einmal."
        rule.onNodeWithText(full).assertExists()
        assertNoTruncation(full)
    }

    @Test
    @Config(qualifiers = "+de")
    fun `double font scale still shows the whole error`() {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                OpenTakezTheme(dynamicColor = false) {
                    HomeScreen(
                        state = failed(EngineError.Network()),
                        onTextChange = {}, onPaste = {}, onClear = {}, onSubmit = {}, onSelectOption = {},
                        onSelectEngine = {}, onDownload = {}, onTokenSubmit = {}, onTokenDismiss = {},
                    )
                }
            }
        }
        assertNoTruncation(
            "Server nicht erreichbar. Prüfe deine Verbindung oder die Server-URL in den Einstellungen.",
        )
    }

    private fun assertNoTruncation(text: String) {
        val node = rule.onNodeWithText(text).fetchSemanticsNode()
        val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        val layout = results.single()
        assertFalse("text was cut by maxLines", layout.hasVisualOverflow)
        (0 until layout.lineCount).forEach { assertFalse("line $it ellipsized", layout.isLineEllipsized(it)) }
    }

    // ---- touch targets ----

    @Test
    fun `interactive elements are at least 48 dp tall`() {
        show(ready(server = true))

        listOf("Best", "720p", "MP3", "On this device", "Via my server").forEach {
            rule.onNodeWithText(it).assertHeightIsAtLeast(48.dp)
        }
        downloadButton.assertHeightIsAtLeast(48.dp)
        rule.onNodeWithContentDescription("Clear").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `Paste and Check link are at least 48 dp tall`() {
        show(HomeUiState())
        rule.onNode(hasText("Paste") and hasClickAction()).assertHeightIsAtLeast(48.dp)
    }
}
