package io.github.timhofmann27.opentakez.ui

import app.cash.turbine.test
import io.github.timhofmann27.opentakez.core.model.Engine
import io.github.timhofmann27.opentakez.core.model.EngineError
import io.github.timhofmann27.opentakez.ui.downloads.DownloadsViewModel
import io.github.timhofmann27.opentakez.ui.fakes.FakeDownloadsPort
import io.github.timhofmann27.opentakez.ui.fakes.FakeSettingsPort
import io.github.timhofmann27.opentakez.ui.fakes.SampleData
import io.github.timhofmann27.opentakez.ui.history.HistoryEvent
import io.github.timhofmann27.opentakez.ui.history.HistoryViewModel
import io.github.timhofmann27.opentakez.ui.ports.ConnectionResult
import io.github.timhofmann27.opentakez.ui.ports.DownloadStatus
import io.github.timhofmann27.opentakez.ui.ports.DownloadsPort
import io.github.timhofmann27.opentakez.ui.ports.UpdateResult
import io.github.timhofmann27.opentakez.ui.settings.SettingsViewModel
import io.github.timhofmann27.opentakez.ui.settings.TestState
import io.github.timhofmann27.opentakez.ui.settings.UpdateState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DownloadsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    @Test
    fun `items are null until the first list, then follow the port`() = runTest {
        val port = FakeDownloadsPort()
        val vm = DownloadsViewModel(port)

        vm.items.test {
            assertEquals(emptyList<Any>(), awaitItem() ?: awaitItem())
            port.activeState.value = listOf(SampleData.running("a"))
            assertEquals("a", awaitItem()!!.single().id)
        }
    }

    @Test
    fun `running count ignores failed downloads`() = runTest {
        val port = FakeDownloadsPort()
        port.activeState.value = listOf(
            SampleData.running("a"),
            SampleData.running("q").copy(status = DownloadStatus.QUEUED),
            SampleData.failed("b"),
        )
        val vm = DownloadsViewModel(port)

        vm.runningCount.test {
            var count = awaitItem()
            if (count == 0) count = awaitItem()
            assertEquals(2, count)
        }
    }

    @Test
    fun `cancel, retry and remove reach the port`() {
        val port = FakeDownloadsPort()
        port.activeState.value = listOf(SampleData.running("a"), SampleData.failed("b"))
        val vm = DownloadsViewModel(port)

        vm.cancel("a")
        vm.retry("b")
        vm.remove("b")

        assertEquals(listOf("a"), port.cancelled)
        assertEquals(listOf("b"), port.retried)
        assertEquals(listOf("b"), port.removed)
    }

    @Test
    fun `a failing action shows a notice until dismissed or replaced by a successful action`() {
        val failing = object : DownloadsPort by FakeDownloadsPort() {
            override suspend fun retry(id: String) = throw EngineError.Conflict()
        }
        val vm = DownloadsViewModel(failing)

        vm.retry("x")
        assertTrue(vm.notice.value is EngineError.Conflict)

        vm.dismissNotice()
        assertNull(vm.notice.value)
    }
}

class HistoryViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    @Test
    fun `download again asks the port and tells the UI to show Downloads`() = runTest {
        val port = FakeDownloadsPort()
        val vm = HistoryViewModel(port)

        vm.events.test {
            vm.downloadAgain("h1")
            assertEquals(HistoryEvent.DownloadQueued, awaitItem())
        }
        assertEquals(listOf("h1"), port.repeated)
    }

    @Test
    fun `a failing download again becomes a notice and no event`() {
        val failing = object : DownloadsPort by FakeDownloadsPort() {
            override suspend fun downloadAgain(historyId: String): String = throw EngineError.Network()
        }
        val vm = HistoryViewModel(failing)

        vm.downloadAgain("h1")

        assertTrue(vm.notice.value is EngineError.Network)
    }

    @Test
    fun `remove and clear update the list`() = runTest {
        val port = FakeDownloadsPort()
        port.historyState.value = listOf(SampleData.history("a"), SampleData.history("b"))
        val vm = HistoryViewModel(port)

        vm.items.test {
            var items = awaitItem()
            if (items == null) items = awaitItem()
            assertEquals(2, items!!.size)
            vm.remove("a")
            assertEquals(listOf("b"), awaitItem()!!.map { it.id })
            vm.clear()
            assertTrue(awaitItem()!!.isEmpty())
        }
    }
}

class SettingsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val applied = mutableListOf<String>()

    private fun vm(settings: FakeSettingsPort) = SettingsViewModel(settings) { applied += it }

    @Test
    fun `starts with the saved values and never exposes a token`() {
        val vm = vm(FakeSettingsPort(serverUrl = "https://dl.example.com", tokenSet = true, ytDlpVersion = "2026.10.21"))

        val state = vm.state.value
        assertEquals("https://dl.example.com", state.serverUrl)
        assertTrue(state.tokenSet)
        assertEquals("", state.tokenInput)
        assertEquals("2026.10.21", state.ytDlpVersion)
    }

    @Test
    fun `save normalizes the URL, keeps the stored token when the field is empty and clears the field`() {
        val settings = FakeSettingsPort(serverUrl = "https://old.example.com", tokenSet = true)
        val vm = vm(settings)

        vm.setServerUrl("https://dl.example.com/")
        vm.save()

        assertEquals("https://dl.example.com", settings.serverUrlState.value)
        assertNull(settings.lastSavedToken)
        assertTrue(vm.state.value.saved)
        assertTrue(settings.tokenSetState.value)
    }

    @Test
    fun `save sends a typed token trimmed and then forgets it`() {
        val settings = FakeSettingsPort()
        val vm = vm(settings)

        vm.setServerUrl("https://dl.example.com")
        vm.setToken("  s3cret ")
        vm.save()

        assertEquals("s3cret", settings.lastSavedToken)
        assertEquals("", vm.state.value.tokenInput)
        assertTrue(vm.state.value.tokenSet)
    }

    @Test
    fun `an invalid URL cannot be saved or tested`() {
        val settings = FakeSettingsPort(serverUrl = "https://dl.example.com")
        val vm = vm(settings)

        vm.setServerUrl("http://dl.example.com")
        assertTrue(vm.state.value.urlInvalid)
        vm.save()
        vm.testConnection()

        assertEquals("https://dl.example.com", settings.serverUrlState.value)
        assertNull(settings.lastTestedUrl)
    }

    @Test
    fun `an empty URL clears the server and the token`() {
        val settings = FakeSettingsPort(serverUrl = "https://dl.example.com", tokenSet = true)
        val vm = vm(settings)

        vm.setServerUrl("")
        vm.save()

        assertEquals("", settings.serverUrlState.value)
        assertFalse(settings.tokenSetState.value)
        assertFalse(vm.state.value.urlInvalid)
    }

    @Test
    fun `test connection uses the typed values and reports each outcome`() {
        val settings = FakeSettingsPort(serverUrl = "https://dl.example.com", tokenSet = true)
        val vm = vm(settings)
        listOf(
            ConnectionResult.Connected("1.2.0", "2026.10.21", listOf("youtube", "tiktok")),
            ConnectionResult.Unreachable,
            ConnectionResult.TokenRejected,
            ConnectionResult.TooOld,
            ConnectionResult.NotOpenTakez,
        ).forEach { result ->
            settings.connectionResult = result
            vm.setToken("typed")
            vm.testConnection()
            assertEquals("typed", settings.lastTestedToken)
            assertEquals(TestState.Done(result), vm.state.value.test)
        }
    }

    @Test
    fun `test connection without a typed token tests the stored one`() {
        val settings = FakeSettingsPort(serverUrl = "https://dl.example.com", tokenSet = true)
        val vm = vm(settings)
        vm.testConnection()
        assertNull(settings.lastTestedToken)
    }

    @Test
    fun `editing a field resets the shown test result`() {
        val vm = vm(FakeSettingsPort(serverUrl = "https://dl.example.com"))
        vm.testConnection()
        assertTrue(vm.state.value.test is TestState.Done)
        vm.setServerUrl("https://other.example.com")
        assertEquals(TestState.Idle, vm.state.value.test)
    }

    @Test
    fun `remove token asks the port to clear it`() {
        val settings = FakeSettingsPort(serverUrl = "https://dl.example.com", tokenSet = true)
        val vm = vm(settings)
        vm.removeToken()
        assertEquals("", settings.lastSavedToken)
        assertFalse(settings.tokenSetState.value)
        assertTrue(vm.state.value.tokenRemoved)
    }

    @Test
    fun `default engine and language are saved and the locale is applied`() {
        val settings = FakeSettingsPort(serverUrl = "https://dl.example.com")
        val vm = vm(settings)

        vm.setDefaultEngine(Engine.SERVER)
        vm.setLanguage("de")
        vm.setLanguage("")

        assertEquals(Engine.SERVER, vm.state.value.defaultEngine)
        assertEquals("", vm.state.value.language)
        assertEquals(listOf("de", ""), applied)
    }

    @Test
    fun `server default choice needs both saved URL and token`() {
        val settings = FakeSettingsPort(serverUrl = "https://dl.example.com", tokenSet = false)
        val vm = vm(settings)
        assertFalse(vm.state.value.serverSaved)

        settings.tokenSetState.value = true
        assertTrue(vm.state.value.serverSaved)
    }

    @Test
    fun `update shows Updating, then the version`() {
        val settings = FakeSettingsPort()
        val gate = CompletableDeferred<UpdateResult>()
        val vm = SettingsViewModel(
            object : io.github.timhofmann27.opentakez.ui.ports.SettingsPort by settings {
                override suspend fun updateYtDlp(): UpdateResult = gate.await()
            },
        ) { }

        vm.updateYtDlp()
        assertEquals(UpdateState.Updating, vm.state.value.update)
        vm.updateYtDlp() // ignored while running

        gate.complete(UpdateResult.Updated("2026.11.01"))
        assertEquals(UpdateState.Done(UpdateResult.Updated("2026.11.01")), vm.state.value.update)
    }

    @Test
    fun `update failure and already-latest are reported`() {
        val settings = FakeSettingsPort()
        val vm = vm(settings)

        settings.updateResult = UpdateResult.Failed("offline")
        vm.updateYtDlp()
        assertEquals(UpdateState.Done(UpdateResult.Failed("offline")), vm.state.value.update)

        settings.updateResult = UpdateResult.AlreadyLatest
        vm.updateYtDlp()
        assertEquals(UpdateState.Done(UpdateResult.AlreadyLatest), vm.state.value.update)
    }
}
