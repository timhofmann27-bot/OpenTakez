package io.github.timhofmann27.opentakez.ui

import app.cash.turbine.test
import io.github.timhofmann27.opentakez.core.model.DownloadOption
import io.github.timhofmann27.opentakez.core.model.Engine
import io.github.timhofmann27.opentakez.core.model.EngineError
import io.github.timhofmann27.opentakez.ui.fakes.FakeServices
import io.github.timhofmann27.opentakez.ui.fakes.FakeDownloadsPort
import io.github.timhofmann27.opentakez.ui.fakes.FakeSettingsPort
import io.github.timhofmann27.opentakez.ui.fakes.SampleData
import io.github.timhofmann27.opentakez.ui.home.HomeEvent
import io.github.timhofmann27.opentakez.ui.home.HomeViewModel
import io.github.timhofmann27.opentakez.ui.home.PreviewState
import io.github.timhofmann27.opentakez.ui.ports.ConnectionResult
import io.github.timhofmann27.opentakez.ui.ports.DeviceEngineState
import io.github.timhofmann27.opentakez.ui.ports.DownloadsPort
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HomeViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private fun services(settings: FakeSettingsPort = FakeSettingsPort()) = FakeServices(settings = settings)

    private fun HomeViewModel_(s: FakeServices) = HomeViewModel(s.resolve, s.downloads, s.settings, s.routing)

    private val HomeViewModel.ready get() = state.value.preview as PreviewState.Ready
    private val HomeViewModel.failure get() = (state.value.preview as PreviewState.Failed).error

    @Test
    fun `a shared link is cleaned, resolved and previewed with its first option`() {
        val s = services()
        val vm = HomeViewModel_(s)

        vm.openShared("https://youtu.be/x")

        assertEquals(listOf("https://youtu.be/x" to Engine.DEVICE), s.resolve.calls)
        assertEquals("https://youtu.be/x", vm.state.value.text)
        assertEquals(DownloadOption.VIDEO_BEST, vm.ready.option)
        assertEquals(Engine.DEVICE, vm.ready.engine)
    }

    @Test
    fun `typed text with a link inside resolves the clean link`() {
        val s = services()
        val vm = HomeViewModel_(s)

        vm.setText("look https://youtu.be/x!")
        vm.submit()

        assertEquals("https://youtu.be/x", s.resolve.calls.single().first)
    }

    @Test
    fun `text without a link fails with NoLink and never asks the engine`() {
        val s = services()
        val vm = HomeViewModel_(s)

        vm.setText("hello there")
        vm.submit()

        assertTrue(vm.failure is EngineError.NoLink)
        assertTrue(s.resolve.calls.isEmpty())
    }

    @Test
    fun `a share without a link lands on Home with the NoLink error`() {
        val vm = HomeViewModel_(services())

        vm.openShared(null)

        assertTrue(vm.failure is EngineError.NoLink)
        assertEquals("", vm.state.value.text)
    }

    @Test
    fun `blank submit does nothing`() {
        val s = services()
        val vm = HomeViewModel_(s)
        vm.setText("   ")
        vm.submit()
        assertEquals(PreviewState.Idle, vm.state.value.preview)
        assertTrue(s.resolve.calls.isEmpty())
    }

    @Test
    fun `paste fills the field and checks the link right away, an empty clipboard does nothing`() {
        val s = services()
        val vm = HomeViewModel_(s)

        vm.paste(null)
        vm.paste("  ")
        assertTrue(s.resolve.calls.isEmpty())

        vm.paste("https://soundcloud.com/a/b")
        assertEquals("https://soundcloud.com/a/b", vm.state.value.text)
        assertEquals(1, s.resolve.calls.size)
    }

    @Test
    fun `shows Resolving while the engine works, then the preview`() {
        val s = services()
        val gate = CompletableDeferred<Unit>()
        s.resolve.handler = { url, _ -> gate.await(); SampleData.video(url) }
        val vm = HomeViewModel_(s)

        vm.openShared("https://youtu.be/x")
        assertEquals(PreviewState.Resolving, vm.state.value.preview)

        gate.complete(Unit)
        assertTrue(vm.state.value.preview is PreviewState.Ready)
    }

    @Test
    fun `a failed resolve shows the error and clear resets everything`() {
        val s = services()
        s.resolve.handler = { _, _ -> throw EngineError.Unsupported() }
        val vm = HomeViewModel_(s)

        vm.openShared("https://example.com/x")
        assertTrue(vm.failure is EngineError.Unsupported)

        vm.clear()
        assertEquals("", vm.state.value.text)
        assertEquals(PreviewState.Idle, vm.state.value.preview)
    }

    @Test
    fun `clear while resolving drops the late answer`() {
        val s = services()
        val gate = CompletableDeferred<Unit>()
        s.resolve.handler = { url, _ -> gate.await(); SampleData.video(url) }
        val vm = HomeViewModel_(s)

        vm.openShared("https://youtu.be/x")
        vm.clear()
        gate.complete(Unit)

        assertEquals(PreviewState.Idle, vm.state.value.preview)
    }

    @Test
    fun `an unexpected exception becomes a generic error without leaking its message`() {
        val s = services()
        s.resolve.handler = { _, _ -> error("token=secret https://u:p@host") }
        val vm = HomeViewModel_(s)

        vm.openShared("https://youtu.be/x")

        val error = vm.failure
        assertTrue(error is EngineError.Other)
        assertNull(error.message)
    }

    @Test
    fun `login required without a platform gets one guessed from the link`() {
        val s = services()
        s.resolve.handler = { _, _ -> throw EngineError.LoginRequired(null) }
        val vm = HomeViewModel_(s)

        vm.openShared("https://www.instagram.com/p/abc/")

        assertEquals("instagram", (vm.failure as EngineError.LoginRequired).platform)
    }

    // ---- engine choice ----

    @Test
    fun `photo links go to the server when one is configured`() {
        val s = services(FakeSettingsPort(serverUrl = "https://dl.example.com"))
        s.resolve.handler = { url, _ -> SampleData.photos(url) }
        val vm = HomeViewModel_(s)

        vm.openShared("https://www.instagram.com/p/abc/")

        assertEquals(Engine.SERVER, s.resolve.calls.single().second)
        assertEquals(Engine.SERVER, vm.ready.engine)
    }

    @Test
    fun `photo links without a server surface NeedsServer from the engine`() {
        val s = services()
        s.resolve.handler = { _, engine ->
            if (engine == Engine.DEVICE) throw EngineError.NeedsServer() else SampleData.photos()
        }
        val vm = HomeViewModel_(s)

        vm.openShared("https://www.instagram.com/p/abc/")

        assertEquals(Engine.DEVICE, s.resolve.calls.single().second)
        assertTrue(vm.failure is EngineError.NeedsServer)
    }

    @Test
    fun `a saved server URL without a token does not enable server routing`() {
        val settings = FakeSettingsPort(
            serverUrl = "https://dl.example.com",
            tokenSet = false,
            engine = Engine.SERVER,
        )
        val s = services(settings)
        val vm = HomeViewModel_(s)

        vm.openShared("https://youtu.be/x")
        vm.selectEngine(Engine.SERVER)

        assertFalse(vm.state.value.serverConfigured)
        assertEquals(listOf("https://youtu.be/x" to Engine.DEVICE), s.resolve.calls)
        assertEquals(Engine.DEVICE, vm.ready.engine)
    }

    @Test
    fun `the default engine applies to ordinary links when a server exists`() {
        val settings = FakeSettingsPort(serverUrl = "https://dl.example.com", engine = Engine.SERVER)
        val s = services(settings)
        val vm = HomeViewModel_(s)

        vm.openShared("https://youtu.be/x")
        assertEquals(Engine.SERVER, s.resolve.calls.last().second)

        settings.engineState.value = Engine.DEVICE
        vm.openShared("https://youtu.be/y")
        assertEquals(Engine.DEVICE, s.resolve.calls.last().second)
    }

    @Test
    fun `without a server the device is used even if SERVER is the stored default`() {
        val s = services(FakeSettingsPort(serverUrl = "", engine = Engine.SERVER))
        val vm = HomeViewModel_(s)
        vm.openShared("https://youtu.be/x")
        assertEquals(Engine.DEVICE, s.resolve.calls.single().second)
    }

    @Test
    fun `switching the engine looks the link up again and keeps the option if it still exists`() {
        val s = services(FakeSettingsPort(serverUrl = "https://dl.example.com"))
        s.resolve.handler = { url, engine ->
            if (engine == Engine.SERVER) SampleData.video(url).copy(options = listOf(DownloadOption.VIDEO_720, DownloadOption.AUDIO_MP3))
            else SampleData.video(url)
        }
        s.settings.engineState.value = Engine.DEVICE
        val vm = HomeViewModel_(s)
        vm.openShared("https://youtu.be/x")
        vm.selectOption(DownloadOption.VIDEO_720)

        vm.selectEngine(Engine.SERVER)

        assertEquals(Engine.SERVER, vm.ready.engine)
        assertEquals(DownloadOption.VIDEO_720, vm.ready.option)
        assertEquals(listOf(DownloadOption.VIDEO_720, DownloadOption.AUDIO_MP3), vm.ready.info.options)
        assertFalse(vm.ready.switching)
    }

    @Test
    fun `an option that the other engine lacks falls back to its first option`() {
        val s = services(FakeSettingsPort(serverUrl = "https://dl.example.com"))
        s.resolve.handler = { url, engine ->
            if (engine == Engine.SERVER) SampleData.video(url).copy(options = listOf(DownloadOption.AUDIO_MP3)) else SampleData.video(url)
        }
        val vm = HomeViewModel_(s)
        vm.openShared("https://youtu.be/x")
        vm.selectOption(DownloadOption.VIDEO_1080)

        vm.selectEngine(Engine.SERVER)

        assertEquals(DownloadOption.AUDIO_MP3, vm.ready.option)
    }

    @Test
    fun `a failed switch keeps the old preview and shows the error`() {
        val s = services(FakeSettingsPort(serverUrl = "https://dl.example.com"))
        s.resolve.handler = { url, engine -> if (engine == Engine.SERVER) throw EngineError.Network() else SampleData.video(url) }
        val vm = HomeViewModel_(s)
        vm.openShared("https://youtu.be/x")

        vm.selectEngine(Engine.SERVER)

        assertEquals(Engine.DEVICE, vm.ready.engine)
        assertTrue(vm.ready.notice is EngineError.Network)
        assertFalse(vm.ready.switching)
    }

    @Test
    fun `the server cannot be selected when none is configured`() {
        val s = services()
        val vm = HomeViewModel_(s)
        vm.openShared("https://youtu.be/x")

        vm.selectEngine(Engine.SERVER)

        assertEquals(1, s.resolve.calls.size)
        assertEquals(Engine.DEVICE, vm.ready.engine)
    }

    // ---- starting ----

    @Test
    fun `start queues the chosen option and engine, resets Home and tells the UI`() = runTest {
        val s = services()
        val vm = HomeViewModel_(s)
        vm.openShared("https://youtu.be/x")
        vm.selectOption(DownloadOption.AUDIO_M4A)

        vm.events.test {
            vm.start()
            assertEquals(HomeEvent.DownloadQueued, awaitItem())
        }

        val started = s.downloads.started.single()
        assertEquals("https://youtu.be/x", started.url)
        assertEquals(DownloadOption.AUDIO_M4A, started.option)
        assertEquals(Engine.DEVICE, started.engine)
        assertEquals(PreviewState.Idle, vm.state.value.preview)
        assertEquals("", vm.state.value.text)
    }

    @Test
    fun `a failing start keeps the preview and shows the reason`() {
        val s = services()
        s.downloads.startFailure = EngineError.StorageFull()
        val vm = HomeViewModel_(s)
        vm.openShared("https://youtu.be/x")

        vm.start()

        assertTrue(vm.ready.notice is EngineError.StorageFull)
        assertFalse(vm.ready.starting)
    }

    @Test
    fun `a queued old link does not erase a newer shared preview`() = runTest {
        val s = services()
        val release = CompletableDeferred<Unit>()
        val pending = object : DownloadsPort by FakeDownloadsPort() {
            override suspend fun start(
                url: String,
                info: io.github.timhofmann27.opentakez.core.model.MediaInfo,
                option: DownloadOption,
                engine: Engine,
            ): String {
                release.await()
                return "queued"
            }
        }
        val vm = HomeViewModel(s.resolve, pending, s.settings, s.routing)
        vm.openShared("https://youtu.be/old")
        vm.start()
        vm.openShared("https://youtu.be/new")

        vm.events.test {
            release.complete(Unit)
            assertEquals("https://youtu.be/new", vm.ready.info.url)
            assertEquals("https://youtu.be/new", vm.state.value.text)
            expectNoEvents()
        }
    }

    @Test
    fun `start without a preview is ignored`() {
        val s = services()
        val vm = HomeViewModel_(s)
        vm.start()
        assertTrue(s.downloads.started.isEmpty())
    }

    // ---- state from settings ----

    @Test
    fun `follows the server setting and the device engine state`() {
        val settings = FakeSettingsPort(deviceEngine = DeviceEngineState.Initializing)
        val vm = HomeViewModel_(services(settings))
        assertFalse(vm.state.value.serverConfigured)
        assertEquals(DeviceEngineState.Initializing, vm.state.value.deviceEngine)

        settings.serverUrlState.value = "https://dl.example.com"
        settings.deviceEngineState.value = DeviceEngineState.Ready

        assertFalse(vm.state.value.serverConfigured)
        settings.tokenSetState.value = true

        assertTrue(vm.state.value.serverConfigured)
        assertEquals(DeviceEngineState.Ready, vm.state.value.deviceEngine)
    }

    // ---- token dialog ----

    @Test
    fun `a 401 opens the token dialog, a good token is saved and the link is checked again`() {
        val settings = FakeSettingsPort(serverUrl = "https://dl.example.com", engine = Engine.SERVER)
        val s = services(settings)
        var attempts = 0
        s.resolve.handler = { url, _ -> if (attempts++ == 0) throw EngineError.Unauthorized() else SampleData.video(url) }
        val vm = HomeViewModel_(s)
        vm.openShared("https://youtu.be/x")
        assertNotNull(vm.state.value.tokenDialog)

        vm.submitToken("  good-token ")

        assertEquals("good-token", settings.lastTestedToken)
        assertEquals("good-token", settings.lastSavedToken)
        assertNull(vm.state.value.tokenDialog)
        assertTrue(vm.state.value.preview is PreviewState.Ready)
    }

    @Test
    fun `a rejected token keeps the dialog open with the reason`() {
        val settings = FakeSettingsPort(serverUrl = "https://dl.example.com", engine = Engine.SERVER)
        settings.connectionResult = ConnectionResult.TokenRejected
        val s = services(settings)
        s.resolve.handler = { _, _ -> throw EngineError.Unauthorized() }
        val vm = HomeViewModel_(s)
        vm.openShared("https://youtu.be/x")

        vm.submitToken("bad")

        assertEquals(ConnectionResult.TokenRejected, vm.state.value.tokenDialog?.failure)
        assertEquals("unset", settings.lastSavedToken)
    }

    @Test
    fun `dismissing the token dialog leaves the error on screen`() {
        val s = services(FakeSettingsPort(serverUrl = "https://dl.example.com", engine = Engine.SERVER))
        s.resolve.handler = { _, _ -> throw EngineError.Unauthorized() }
        val vm = HomeViewModel_(s)
        vm.openShared("https://youtu.be/x")

        vm.dismissTokenDialog()

        assertNull(vm.state.value.tokenDialog)
        assertTrue(vm.failure is EngineError.Unauthorized)
    }
}
