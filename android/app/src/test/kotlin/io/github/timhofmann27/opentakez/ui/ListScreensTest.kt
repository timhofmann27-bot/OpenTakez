package io.github.timhofmann27.opentakez.ui

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.github.timhofmann27.opentakez.core.model.EngineError
import io.github.timhofmann27.opentakez.core.model.JobProgress
import io.github.timhofmann27.opentakez.core.model.Phase
import io.github.timhofmann27.opentakez.ui.downloads.DownloadsScreen
import io.github.timhofmann27.opentakez.ui.fakes.SampleData
import io.github.timhofmann27.opentakez.ui.history.FileActions
import io.github.timhofmann27.opentakez.ui.history.HistoryScreen
import io.github.timhofmann27.opentakez.ui.ports.ActiveDownload
import io.github.timhofmann27.opentakez.ui.ports.DownloadStatus
import io.github.timhofmann27.opentakez.ui.ports.HistoryEntry
import io.github.timhofmann27.opentakez.ui.theme.OpenTakezTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = TALL_SCREEN)
class DownloadsScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val cancelled = mutableListOf<String>()
    private val retried = mutableListOf<String>()
    private val removed = mutableListOf<String>()

    private fun show(items: List<ActiveDownload>?, notice: EngineError? = null) {
        rule.setContent {
            OpenTakezTheme(dynamicColor = false) {
                DownloadsScreen(items, notice, { cancelled += it }, { retried += it }, { removed += it })
            }
        }
    }

    @Test
    fun `empty list explains where to start`() {
        show(emptyList())
        rule.onNodeWithText("No downloads yet. Start one from the Download tab.").assertExists()
    }

    @Test
    fun `nothing is shown before the first list arrives`() {
        show(null)
        rule.onNodeWithText("No downloads yet", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a running download shows phase, percent, speed and eta with a determinate bar`() {
        show(listOf(SampleData.running()))

        rule.onNodeWithText("A very calm video about a lake").assertExists()
        rule.onNodeWithText("YouTube").assertExists()
        rule.onNodeWithText("1080p").assertExists()
        rule.onNodeWithText("Downloading …").assertExists()
        rule.onNodeWithText("42 % · 2.5 MB/s · 1:15").assertExists()
        rule.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo(0.42f, 0f..1f),
            ),
        ).assertExists()
    }

    @Test
    fun `progress without a fraction is an indeterminate bar with only the phase`() {
        show(listOf(SampleData.running().copy(progress = JobProgress(Phase.DOWNLOADING))))
        rule.onNodeWithText("Downloading …").assertExists()
        rule.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate),
        ).assertExists()
    }

    @Test
    fun `processing and queued have their own text`() {
        show(
            listOf(
                SampleData.running("p").copy(title = "Processing one", progress = JobProgress(Phase.PROCESSING, 1.0)),
                SampleData.running("q").copy(title = "Queued one", status = DownloadStatus.QUEUED, progress = null),
            ),
        )
        rule.onNodeWithText("Processing …").assertExists()
        rule.onNodeWithText("Waiting …").assertExists()
    }

    @Test
    @Config(qualifiers = "+de")
    fun `German phase text`() {
        show(listOf(SampleData.running()))
        rule.onNodeWithText("Wird geladen …").assertExists()
        rule.onNodeWithContentDescription("Abbrechen: A very calm video about a lake").assertExists()
    }

    @Test
    fun `cancel stops a running download and is a big enough target`() {
        show(listOf(SampleData.running("a")))
        val cancel = rule.onNodeWithContentDescription("Cancel: A very calm video about a lake")
        cancel.assertHeightIsAtLeast(48.dp)
        cancel.performClick()
        assertEquals(listOf("a"), cancelled)
        rule.onNodeWithText("Retry").assertDoesNotExist()
    }

    @Test
    fun `a failed download shows its error with Retry and Remove`() {
        show(listOf(SampleData.failed("b", EngineError.NeedsServer())))

        rule.onNodeWithText("Photos and carousels need your own server. Add it in Settings.").assertExists()
        rule.onNode(hasText("Retry") and hasClickAction()).assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithContentDescription("Remove: A photo post").assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithText("Downloading …").assertDoesNotExist()
        assertEquals(listOf("b"), retried)
        assertEquals(listOf("b"), removed)
    }

    @Test
    fun `a lost server job explains itself`() {
        show(listOf(SampleData.failed("b", EngineError.Lost())))
        rule.onNodeWithText("The server restarted and lost this download.").assertExists()
    }

    @Test
    fun `a failed action shows a notice above the list`() {
        show(listOf(SampleData.failed("b")), notice = EngineError.Conflict())
        rule.onNodeWithText("Still running, remove it when it is finished.").assertExists()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = TALL_SCREEN)
class HistoryScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private class RecordingActions(var succeed: Boolean = true) : FileActions {
        val opened = mutableListOf<String>()
        val shared = mutableListOf<String>()
        override fun open(entry: HistoryEntry): Boolean = succeed.also { opened += entry.id }
        override fun share(entry: HistoryEntry, chooserTitle: String): Boolean = succeed.also { shared += entry.id }
    }

    private val actions = RecordingActions()
    private val again = mutableListOf<String>()
    private val removed = mutableListOf<String>()
    private var cleared = 0

    private fun show(items: List<HistoryEntry>?, notice: EngineError? = null) {
        rule.setContent {
            OpenTakezTheme(dynamicColor = false) {
                HistoryScreen(items, notice, actions, { again += it }, { removed += it }, { cleared++ })
            }
        }
    }

    @Test
    fun `empty history`() {
        show(emptyList())
        rule.onNodeWithText("Finished downloads show up here.").assertExists()
        rule.onNodeWithText("Clear history").assertDoesNotExist()
    }

    @Test
    fun `an entry shows title, platform and option`() {
        show(listOf(SampleData.history()))
        rule.onNodeWithText("A very calm video about a lake").assertExists()
        rule.onNodeWithText("YouTube").assertExists()
        rule.onNodeWithText("1080p").assertExists()
    }

    @Test
    fun `Open and Share hand the entry to the file actions`() {
        show(listOf(SampleData.history("h1")))

        rule.onNode(hasText("Open") and hasClickAction()).assertHeightIsAtLeast(48.dp).performClick()
        rule.onNode(hasText("Share") and hasClickAction()).assertHeightIsAtLeast(48.dp).performClick()

        assertEquals(listOf("h1"), actions.opened)
        assertEquals(listOf("h1"), actions.shared)
        rule.onNodeWithText("No app can open this file.").assertDoesNotExist()
    }

    @Test
    fun `when no app can open the file the user is told`() {
        actions.succeed = false
        show(listOf(SampleData.history("h1")))

        rule.onNode(hasText("Open") and hasClickAction()).performClick()

        rule.onNodeWithText("No app can open this file.").assertExists()
    }

    @Test
    fun `download again, remove and clear history report`() {
        show(listOf(SampleData.history("h1"), SampleData.history("h2").copy(title = "Second")))

        rule.onNodeWithContentDescription("Remove: Second").performClick()
        rule.onAllNodes(hasText("Download again") and hasClickAction())[0].performClick()
        rule.onNode(hasText("Clear history") and hasClickAction()).assertHeightIsAtLeast(48.dp).performClick()

        assertEquals(listOf("h2"), removed)
        assertEquals(listOf("h1"), again)
        assertEquals(1, cleared)
    }

    @Test
    fun `several files and skipped items are mentioned`() {
        show(
            listOf(
                SampleData.history("h1", files = listOf("content://a/1", "content://a/2", "content://a/3"))
                    .copy(skipped = 2),
            ),
        )
        rule.onNodeWithText("3 files").assertExists()
        rule.onNodeWithText("2 items couldn't be downloaded").assertExists()
    }

    @Test
    fun `an entry whose files are gone can still be downloaded again but not opened`() {
        show(listOf(SampleData.history("h1", files = emptyList())))
        rule.onNodeWithText("Open").assertDoesNotExist()
        rule.onNodeWithText("Share").assertDoesNotExist()
        rule.onNodeWithText("Download again").assertExists()
    }

    @Test
    @Config(qualifiers = "+de")
    fun `German history`() {
        show(listOf(SampleData.history()))
        rule.onNode(hasText("Öffnen") and hasClickAction()).assertExists()
        rule.onNode(hasText("Teilen") and hasClickAction()).assertExists()
        rule.onNodeWithText("Erneut laden").assertExists()
        rule.onNodeWithText("Verlauf leeren").assertExists()
    }
}
